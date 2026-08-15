package com.honor.share.protocol

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class OutgoingFile(
    val meta: FileMeta,
    val open: () -> InputStream,
)

interface ReceiveSink {
    val bytesWritten: Long
    fun write(data: ByteArray)
    fun commit(expectedSha256: String)
    fun abort()
}

interface ReceiveSinkFactory {
    fun open(file: FileMeta, offset: Long): ReceiveSink
    fun hasSpace(bytes: Long): Boolean
}

data class TransferProgress(
    val transferId: String,
    val filesCompleted: Int,
    val filesTotal: Int,
    val bytesTransferred: Long,
    val bytesTotal: Long,
    val currentName: String,
    val currentBytes: Long,
    val currentSize: Long,
    val bytesPerSecond: Double,
    val etaSeconds: Long?,
    val state: TransferState,
)

data class ReceiveDecision(
    val accepted: Boolean,
    val skipFileIds: List<String> = emptyList(),
    val neededBytes: Long? = null,
)

class FileTransfer(
    private val session: ProtocolSession,
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
) {
    fun send(
        request: TransferRequestPayload,
        files: List<OutgoingFile>,
        onProgress: (TransferProgress) -> Unit,
    ) {
        session.send(session.typed(MessageType.TRANSFER_REQUEST, request))
        val response = session.receiveControl()
        val skipIds: Set<String> = when (response.messageType()) {
            MessageType.TRANSFER_ACCEPTED -> {
                runCatching { ProtocolJson.payload<TransferAcceptedPayload>(response).skipFileIds }
                    .getOrDefault(emptyList())
                    .toSet()
            }
            MessageType.TRANSFER_REJECTED -> {
                val reason = ProtocolJson.payload<TransferRejectedPayload>(response).reason
                val code = if (reason == "INSUFFICIENT_STORAGE") ErrorCode.DISK_FULL else ErrorCode.CANCELLED
                throw ProtocolException(code, reason)
            }
            MessageType.ERROR -> {
                val error = ProtocolJson.payload<ErrorPayload>(response)
                throw ProtocolException(ErrorCode.fromWire(error.code), error.code)
            }
            else -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected TRANSFER_ACCEPTED")
        }
        val outgoing = files.filter { it.meta.fileId !in skipIds }
        val skippedBytes = files.filter { it.meta.fileId in skipIds }.sumOf { it.meta.size }
        val estimator = SpeedEstimator()
        var overall = skippedBytes
        val alreadyDone = files.size - outgoing.size
        outgoing.forEachIndexed { index, file ->
            throwIfCancelled(request.transferId)
            sendOne(
                request.transferId,
                file,
                alreadyDone + index,
                files.size,
                overall,
                request.totalBytes,
                estimator,
                onProgress,
            )
            overall += file.meta.size
        }
        session.send(session.typed(MessageType.TRANSFER_COMPLETE, TransferIdPayload(request.transferId)))
        onProgress(
            TransferProgress(
                transferId = request.transferId,
                filesCompleted = files.size,
                filesTotal = files.size,
                bytesTransferred = request.totalBytes,
                bytesTotal = request.totalBytes,
                currentName = "",
                currentBytes = 0,
                currentSize = 0,
                bytesPerSecond = estimator.bytesPerSecond(),
                etaSeconds = 0,
                state = TransferState.COMPLETED,
            ),
        )
    }

    fun receive(
        sinkFactory: ReceiveSinkFactory,
        accept: (TransferRequestPayload) -> ReceiveDecision,
        onProgress: (TransferProgress) -> Unit,
    ): TransferRequestPayload {
        val envelope = session.receiveControl()
        if (envelope.messageType() != MessageType.TRANSFER_REQUEST) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected TRANSFER_REQUEST got ${envelope.type}")
        }
        val request = ProtocolJson.payload<TransferRequestPayload>(envelope)
        if (request.files.size > ProtocolConstants.MAX_FILES_PER_TRANSFER) {
            session.send(
                session.typed(
                    MessageType.TRANSFER_REJECTED,
                    TransferRejectedPayload(request.transferId, "TOO_MANY_FILES"),
                ),
            )
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "too many files")
        }
        val decision = accept(request)
        val skipIds = decision.skipFileIds.toSet()
        val needed = decision.neededBytes
            ?: request.files.filter { it.fileId !in skipIds }.sumOf { it.size }
        if (!decision.accepted) {
            session.send(
                session.typed(
                    MessageType.TRANSFER_REJECTED,
                    TransferRejectedPayload(request.transferId, "USER_DECLINED"),
                ),
            )
            throw ProtocolException(ErrorCode.CANCELLED, "user declined")
        }
        if (!sinkFactory.hasSpace(needed)) {
            session.send(
                session.typed(
                    MessageType.TRANSFER_REJECTED,
                    TransferRejectedPayload(request.transferId, "INSUFFICIENT_STORAGE"),
                ),
            )
            throw ProtocolException(ErrorCode.DISK_FULL, "not enough storage")
        }
        session.send(
            session.typed(
                MessageType.TRANSFER_ACCEPTED,
                TransferAcceptedPayload(request.transferId, decision.skipFileIds),
            ),
        )
        val estimator = SpeedEstimator()
        val skippedBytes = request.files.filter { it.fileId in skipIds }.sumOf { it.size }
        var overall = skippedBytes
        var completed = skipIds.size
        while (true) {
            throwIfCancelled(request.transferId)
            val next = session.receive()
            when (next) {
                is Frame.Control -> {
                    val control = ProtocolJson.parse(next.json)
                    when (control.messageType()) {
                        MessageType.FILE_START -> {
                            val start = ProtocolJson.payload<FileStartPayload>(control)
                            overall = receiveOne(
                                request,
                                start,
                                sinkFactory,
                                completed,
                                overall,
                                estimator,
                                onProgress,
                            )
                            completed++
                        }
                        MessageType.TRANSFER_COMPLETE -> {
                            onProgress(
                                TransferProgress(
                                    transferId = request.transferId,
                                    filesCompleted = request.files.size,
                                    filesTotal = request.files.size,
                                    bytesTransferred = request.totalBytes,
                                    bytesTotal = request.totalBytes,
                                    currentName = "",
                                    currentBytes = 0,
                                    currentSize = 0,
                                    bytesPerSecond = estimator.bytesPerSecond(),
                                    etaSeconds = 0,
                                    state = TransferState.COMPLETED,
                                ),
                            )
                            return request
                        }
                        MessageType.TRANSFER_CANCELLED -> throw ProtocolException(ErrorCode.CANCELLED, "peer cancelled")
                        MessageType.ERROR -> {
                            val error = ProtocolJson.payload<ErrorPayload>(control)
                            throw ProtocolException(ErrorCode.fromWire(error.code), error.code)
                        }
                        else -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "unexpected ${control.type}")
                    }
                }
                is Frame.Binary -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "binary without FILE_START")
            }
        }
    }

    fun cancel(transferId: String) {
        cancelled.set(true)
        try {
            session.send(
                session.typed(
                    MessageType.TRANSFER_CANCELLED,
                    TransferCancelledPayload(transferId),
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun sendOne(
        transferId: String,
        file: OutgoingFile,
        index: Int,
        totalFiles: Int,
        overallBefore: Long,
        totalBytes: Long,
        estimator: SpeedEstimator,
        onProgress: (TransferProgress) -> Unit,
    ) {
        session.send(
            session.typed(
                MessageType.FILE_START,
                FileStartPayload(
                    transferId = transferId,
                    fileId = file.meta.fileId,
                    name = file.meta.name,
                    size = file.meta.size,
                    mimeType = file.meta.mimeType,
                    offset = 0,
                ),
            ),
        )
        val digest = IncrementalSha256()
        var sent = 0L
        file.open().use { input ->
            val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE)
            while (true) {
                throwIfCancelled(transferId)
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                digest.update(chunk)
                session.sendBinary(UUID.fromString(file.meta.fileId), sent, chunk)
                sent += n
                val overall = overallBefore + sent
                val speed = estimator.onProgress(overall, System.currentTimeMillis())
                onProgress(
                    TransferProgress(
                        transferId = transferId,
                        filesCompleted = index,
                        filesTotal = totalFiles,
                        bytesTransferred = overall,
                        bytesTotal = totalBytes,
                        currentName = file.meta.name,
                        currentBytes = sent,
                        currentSize = file.meta.size,
                        bytesPerSecond = speed,
                        etaSeconds = estimator.etaSeconds(totalBytes - overall),
                        state = TransferState.TRANSFERRING,
                    ),
                )
            }
        }
        if (sent != file.meta.size) {
            throw ProtocolException(ErrorCode.FILE_UNAVAILABLE, "size changed while sending")
        }
        session.send(
            session.typed(
                MessageType.FILE_COMPLETE,
                FileCompletePayload(transferId, file.meta.fileId, sent, digest.hex()),
            ),
        )
    }

    private fun receiveOne(
        request: TransferRequestPayload,
        start: FileStartPayload,
        sinkFactory: ReceiveSinkFactory,
        completed: Int,
        overallBefore: Long,
        estimator: SpeedEstimator,
        onProgress: (TransferProgress) -> Unit,
    ): Long {
        val relative = FilenameConflict.sanitizeRelativePath(start.name)
        val meta = FileMeta(start.fileId, relative.substringAfterLast('/'), start.size, start.mimeType, relative)
        val sink = sinkFactory.open(meta, start.offset)
        val digest = IncrementalSha256()
        try {
            while (sink.bytesWritten < start.size) {
                throwIfCancelled(request.transferId)
                when (val frame = session.receive()) {
                    is Frame.Binary -> {
                        if (!sameFileId(frame.fileId, start.fileId)) {
                            throw ProtocolException(
                                ErrorCode.PROTOCOL_VIOLATION,
                                "unexpected file id start=${start.fileId} frame=${frame.fileId}",
                            )
                        }
                        digest.update(frame.data)
                        sink.write(frame.data)
                        val overall = overallBefore + sink.bytesWritten
                        val speed = estimator.onProgress(overall, System.currentTimeMillis())
                        onProgress(
                            TransferProgress(
                                transferId = request.transferId,
                                filesCompleted = completed,
                                filesTotal = request.files.size,
                                bytesTransferred = overall,
                                bytesTotal = request.totalBytes,
                                currentName = meta.name,
                                currentBytes = sink.bytesWritten,
                                currentSize = start.size,
                                bytesPerSecond = speed,
                                etaSeconds = estimator.etaSeconds(request.totalBytes - overall),
                                state = TransferState.TRANSFERRING,
                            ),
                        )
                    }
                    is Frame.Control -> {
                        val control = ProtocolJson.parse(frame.json)
                        if (control.messageType() == MessageType.FILE_COMPLETE) {
                            val complete = ProtocolJson.payload<FileCompletePayload>(control)
                            if (sink.bytesWritten != complete.bytes) {
                                throw ProtocolException(ErrorCode.CHECKSUM_MISMATCH, "size mismatch")
                            }
                            val actual = digest.hex()
                            if (!Checksums.equalsHex(complete.sha256, actual)) {
                                throw ProtocolException(ErrorCode.CHECKSUM_MISMATCH, "hash mismatch")
                            }
                            sink.commit(complete.sha256)
                            return overallBefore + sink.bytesWritten
                        }
                        if (control.messageType() == MessageType.TRANSFER_CANCELLED) {
                            sink.abort()
                            throw ProtocolException(ErrorCode.CANCELLED, "peer cancelled")
                        }
                        throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "unexpected ${control.type}")
                    }
                }
            }
            val complete = session.receiveControl()
            if (complete.messageType() != MessageType.FILE_COMPLETE) {
                throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected FILE_COMPLETE")
            }
            val payload = ProtocolJson.payload<FileCompletePayload>(complete)
            if (!Checksums.equalsHex(payload.sha256, digest.hex())) {
                throw ProtocolException(ErrorCode.CHECKSUM_MISMATCH, "hash mismatch")
            }
            sink.commit(payload.sha256)
            return overallBefore + sink.bytesWritten
        } catch (error: Exception) {
            sink.abort()
            throw error
        }
    }

    private fun sameFileId(frameId: UUID, startId: String): Boolean =
        frameId.toString().equals(startId, ignoreCase = true)

    private fun throwIfCancelled(transferId: String) {
        if (cancelled.get()) {
            session.send(
                session.typed(
                    MessageType.TRANSFER_CANCELLED,
                    TransferCancelledPayload(transferId),
                ),
            )
            throw ProtocolException(ErrorCode.CANCELLED, "cancelled")
        }
    }
}
