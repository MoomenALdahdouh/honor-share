package com.honor.share.transfer

import com.honor.share.core.DeviceIdentity
import com.honor.share.core.ShareError
import com.honor.share.core.ShareLog
import com.honor.share.core.TrustedPeerStore
import com.honor.share.history.HistoryEntity
import com.honor.share.history.HistoryRepository
import com.honor.share.history.SharedFileEntity
import com.honor.share.protocol.ConnectionMachine
import com.honor.share.protocol.ConnectionState
import com.honor.share.protocol.ErrorCode
import com.honor.share.protocol.FileTransfer
import com.honor.share.protocol.Handshake
import com.honor.share.protocol.LocalProfile
import com.honor.share.protocol.OutgoingFile
import com.honor.share.protocol.PeerCertCapture
import com.honor.share.protocol.ProtocolException
import com.honor.share.protocol.ProtocolSession
import com.honor.share.protocol.ComparisonEngine
import com.honor.share.protocol.ConflictAction
import com.honor.share.protocol.PackageFile
import com.honor.share.protocol.ProtocolConstants
import com.honor.share.protocol.ReceiveDecision
import com.honor.share.protocol.RemotePeer
import com.honor.share.protocol.TransferPackage
import com.honor.share.protocol.TransferProgress
import com.honor.share.protocol.TlsSockets
import com.honor.share.protocol.TransferRequestPayload
import com.honor.share.protocol.TransferState
import com.honor.share.storage.DownloadsSinkFactory
import com.honor.share.storage.SafAccess
import com.honor.share.storage.SelectedFile
import com.honor.share.storage.toMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

data class IncomingRequest(
    val peer: RemotePeer,
    val payload: TransferRequestPayload,
    val comparison: com.honor.share.protocol.ComparisonResult,
    val resolutions: MutableMap<String, ConflictAction> = mutableMapOf(),
)

data class PendingPackageSend(
    val pkg: TransferPackage,
    val files: List<SelectedFile>,
)

class TransferController(
    private val identity: DeviceIdentity,
    private val trusted: TrustedPeerStore,
    private val history: HistoryRepository,
    private val saf: SafAccess,
    private val downloads: DownloadsSinkFactory,
    private val confirmSas: suspend (sas: String, peerName: String) -> Boolean,
    private val confirmIncoming: suspend (IncomingRequest) -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connection = MutableStateFlow(ConnectionState.IDLE)
    val connection: StateFlow<ConnectionState> = _connection
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress
    private val _error = MutableStateFlow<ShareError?>(null)
    val error: StateFlow<ShareError?> = _error
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var activeTransfer: FileTransfer? = null
    private val cancelFlag = AtomicBoolean(false)
    @Volatile
    var pendingSend: PendingPackageSend? = null

    val listenPort: Int
        get() = server?.localPort ?: 0

    fun startServer() {
        if (server != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0), 8)
        server = socket
        ShareLog.i("transfer", "listening on ${socket.localPort}")
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                try {
                    val accepted = socket.accept()
                    launch { handleIncoming(accepted) }
                } catch (error: Exception) {
                    if (!socket.isClosed) ShareLog.e("transfer", "accept failed", error)
                }
            }
        }
    }

    fun stopServer() {
        acceptJob?.cancel()
        server?.close()
        server = null
    }

    fun cancel() {
        cancelFlag.set(true)
        activeTransfer?.cancel(_progress.value?.transferId ?: "")
    }

    fun resetUi() {
        cancel()
        cancelFlag.set(false)
        pendingSend = null
        _progress.value = null
        _error.value = null
        setState(ConnectionState.IDLE)
    }

    fun lastSavedPath(): String = downloads.lastSavedRelative

    fun clearError() {
        _error.value = null
    }

    suspend fun sendTo(host: String, port: Int, files: List<SelectedFile>, pin: String? = null) {
        cancelFlag.set(false)
        _error.value = null
        setState(ConnectionState.CONNECTING)
        withContext(Dispatchers.IO) {
            val capture = PeerCertCapture()
            val ssl = TlsSockets.context(identity.generated, capture, pin)
            Socket(host, port).use { plain ->
                plain.soTimeout = 30_000
                val tls = TlsSockets.wrapClient(plain, ssl, host, port)
                val session = openClientSession(tls, capture)
                sendPrepared(session, files, null)
            }
        }
    }

    suspend fun connectAndReceive(host: String, port: Int, pin: String? = null) {
        cancelFlag.set(false)
        _error.value = null
        setState(ConnectionState.CONNECTING)
        withContext(Dispatchers.IO) {
            val capture = PeerCertCapture()
            val ssl = TlsSockets.context(identity.generated, capture, pin)
            Socket(host, port).use { plain ->
                plain.soTimeout = 30_000
                val tls = TlsSockets.wrapClient(plain, ssl, host, port)
                val session = openClientSession(tls, capture)
                receivePrepared(session)
            }
        }
    }

    private fun openClientSession(tls: SSLSocket, capture: PeerCertCapture): Pair<ProtocolSession, RemotePeer> {
        tls.soTimeout = 30_000
        val session = ProtocolSession(tls.inputStream, tls.outputStream)
        val fingerprint = capture.fingerprint() ?: throw ProtocolException(ErrorCode.AUTH_FAILED, "missing peer cert")
        setState(ConnectionState.AUTHENTICATING)
        val peer = Handshake(
            session = session,
            local = localProfile(),
            capturedFingerprint = fingerprint,
            knownPin = { trusted.fingerprintFor(it) },
            confirmSas = { sas, name -> kotlinx.coroutines.runBlocking { confirmSas(sas, name) } },
        ).runAsClient()
        if (peer.newlyPaired) trusted.trust(peer.deviceId, peer.fingerprint, peer.name)
        setState(ConnectionState.CONNECTED)
        return session to peer
    }

    private suspend fun sendPrepared(
        opened: Pair<ProtocolSession, RemotePeer>,
        files: List<SelectedFile>,
        pkg: TransferPackage?,
    ) {
        val (session, peer) = opened
        val metas = if (pkg != null) pkg.files.map { it.toMeta() } else files.map { it.toMeta() }
        val request = TransferRequestPayload(
            transferId = UUID.randomUUID().toString(),
            files = metas,
            totalBytes = metas.sumOf { it.size },
            packageId = pkg?.packageId,
        )
        val outgoing = files.mapIndexed { index, selected ->
            OutgoingFile(metas[index]) { saf.open(selected.uri) }
        }
        val transfer = FileTransfer(session, cancelFlag)
        activeTransfer = transfer
        try {
            transfer.send(request, outgoing) { _progress.value = it }
            history.dao.insert(
                HistoryEntity(
                    id = request.transferId,
                    direction = "SENT",
                    deviceName = peer.name,
                    fileCount = files.size,
                    totalBytes = request.totalBytes,
                    status = "COMPLETED",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            recordShared(request.transferId, "SENT", peer.name, files.map { Triple(it.uri.toString(), it.name, it.size to it.mimeType) })
        } catch (error: ProtocolException) {
            fail(error)
            history.dao.insert(
                HistoryEntity(
                    id = request.transferId,
                    direction = "SENT",
                    deviceName = peer.name,
                    fileCount = files.size,
                    totalBytes = request.totalBytes,
                    status = if (error.code == ErrorCode.CANCELLED) "CANCELLED" else "FAILED",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            throw error
        } finally {
            activeTransfer = null
            setState(ConnectionState.DISCONNECTED)
        }
    }

    private suspend fun receivePrepared(opened: Pair<ProtocolSession, RemotePeer>) {
        receivePrepared(opened.first, opened.second)
    }

    private suspend fun receivePrepared(session: ProtocolSession, peer: RemotePeer) {
        downloads.subfolder = ProtocolConstants.receiveSubfolder(peer.name)
        val transfer = FileTransfer(session, cancelFlag)
        activeTransfer = transfer
        val request = transfer.receive(
            sinkFactory = downloads,
            accept = { payload ->
                val incoming = payload.files.map { meta ->
                    PackageFile(
                        fileId = meta.fileId,
                        name = meta.name,
                        relativePath = meta.relativePath,
                        size = meta.size,
                        mimeType = meta.mimeType,
                        modifiedAt = meta.modifiedAt,
                        hash = meta.sha256,
                    )
                }
                val dest = downloads.destinationIndex(incoming.map { it.size }.toSet())
                val comparison = ComparisonEngine.compare(incoming, dest)
                val incomingRequest = IncomingRequest(peer, payload, comparison)
                comparison.conflicts.forEach { conflict ->
                    incomingRequest.resolutions.putIfAbsent(conflict.incoming.fileId, ConflictAction.KEEP_BOTH)
                }
                val accepted = kotlinx.coroutines.runBlocking { confirmIncoming(incomingRequest) }
                incomingRequest.resolutions.forEach { (fileId, action) ->
                    if (action == ConflictAction.REPLACE) {
                        val name = incoming.firstOrNull { it.fileId == fileId }?.name
                        if (name != null) downloads.replaceNames += name
                    }
                }
                val resolved = comparison.withResolutions(incomingRequest.resolutions)
                ReceiveDecision(accepted, resolved.skipFileIds, resolved.neededBytes)
            },
            onProgress = { _progress.value = it },
        )
        history.dao.insert(
            HistoryEntity(
                id = request.transferId,
                direction = "RECEIVED",
                deviceName = peer.name,
                fileCount = request.files.size,
                totalBytes = request.totalBytes,
                status = "COMPLETED",
                createdAt = System.currentTimeMillis(),
            ),
        )
        recordShared(
            request.transferId,
            "RECEIVED",
            peer.name,
            downloads.drainPublished().map { Triple(it.uri.toString(), it.name, it.size to it.mimeType) },
        )
        activeTransfer = null
    }

    private suspend fun handleIncoming(plain: Socket) {
        val capture = PeerCertCapture()
        try {
            cancelFlag.set(false)
            plain.soTimeout = 30_000
            val ssl = TlsSockets.context(identity.generated, capture, null)
            val tls = TlsSockets.wrapServer(plain, ssl)
            tls.soTimeout = 30_000
            val session = ProtocolSession(tls.inputStream, tls.outputStream)
            val fingerprint = capture.fingerprint() ?: throw ProtocolException(ErrorCode.AUTH_FAILED, "missing peer cert")
            setState(ConnectionState.AUTHENTICATING)
            val peer = Handshake(
                session = session,
                local = localProfile(),
                capturedFingerprint = fingerprint,
                knownPin = { trusted.fingerprintFor(it) },
                confirmSas = { sas, name -> kotlinx.coroutines.runBlocking { confirmSas(sas, name) } },
            ).runAsServer()
            if (peer.newlyPaired) trusted.trust(peer.deviceId, peer.fingerprint, peer.name)
            setState(ConnectionState.CONNECTED)
            val waiting = pendingSend
            if (waiting != null) {
                sendPrepared(session to peer, waiting.files, waiting.pkg)
            } else {
                receivePrepared(session, peer)
            }
        } catch (error: ProtocolException) {
            fail(error)
        } catch (error: Exception) {
            fail(ProtocolException(ErrorCode.CONNECTION_LOST, error.message ?: "connection lost", error))
        } finally {
            activeTransfer = null
            try {
                plain.close()
            } catch (_: Exception) {
            }
            setState(ConnectionState.DISCONNECTED)
        }
    }

    private suspend fun recordShared(
        transferId: String,
        direction: String,
        deviceName: String,
        files: List<Triple<String, String, Pair<Long, String>>>,
    ) {
        val now = System.currentTimeMillis()
        history.files.insertAll(
            files.map { (uri, name, sizeMime) ->
                SharedFileEntity(
                    id = "$transferId:$name:${sizeMime.first}",
                    name = name,
                    mimeType = sizeMime.second,
                    size = sizeMime.first,
                    uri = uri,
                    direction = direction,
                    deviceName = deviceName,
                    transferId = transferId,
                    createdAt = now,
                )
            },
        )
    }

    private fun localProfile() = LocalProfile(
        deviceId = identity.deviceId,
        name = identity.displayName,
        os = "android",
        identity = identity.generated,
    )

    private fun setState(next: ConnectionState) {
        val current = _connection.value
        _connection.value = if (ConnectionMachine.canTransition(current, next)) {
            ConnectionMachine.transition(current, next)
        } else {
            next
        }
    }

    private fun fail(error: ProtocolException) {
        ShareLog.e("transfer", error.message ?: error.code.name, error)
        _error.value = ShareError.from(error.code, error.message ?: error.code.name, error)
        _progress.value = _progress.value?.copy(state = if (error.code == ErrorCode.CANCELLED) TransferState.CANCELLED else TransferState.FAILED)
        setState(ConnectionState.FAILED)
    }
}
