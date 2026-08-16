package com.honor.share.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.honor.share.core.DeviceIdentity
import com.honor.share.core.LocalAddress
import com.honor.share.core.RadioMonitor
import com.honor.share.core.ShareError
import com.honor.share.discovery.NearbyDevice
import com.honor.share.discovery.NsdDiscovery
import com.honor.share.history.HistoryEntity
import com.honor.share.history.HistoryRepository
import com.honor.share.history.SharedFileEntity
import com.honor.share.protocol.ByteFormat
import com.honor.share.protocol.ErrorCode
import com.honor.share.protocol.FolderBrowser
import com.honor.share.protocol.PackageFile
import com.honor.share.protocol.PickerSelection
import com.honor.share.protocol.PackageFileStatus
import com.honor.share.protocol.PackageInvitation
import com.honor.share.protocol.PackageState
import com.honor.share.protocol.Sas
import com.honor.share.protocol.ShareLink
import com.honor.share.protocol.TransferPackage
import com.honor.share.protocol.TransferProgress
import com.honor.share.protocol.TransferState
import com.honor.share.storage.FileFilter
import com.honor.share.storage.FileScanner
import com.honor.share.storage.LibraryFile
import com.honor.share.storage.SafAccess
import com.honor.share.storage.SelectedFile
import com.honor.share.transfer.IncomingRequest
import com.honor.share.transfer.PendingPackageSend
import com.honor.share.transfer.TransferController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

enum class Screen {
    HOME, SELECTED, DEVICES, PAIRING, TRANSFER, HISTORY, FILES, RECEIVE, SCAN, INCOMING, PERMISSION, RADIO, PACKAGE, CODE
}

enum class ScanMode { RECEIVE_PACKAGE, SEND_NEARBY }

data class PairingPrompt(val peerName: String, val code: String, val display: String)

class ShareViewModel(
    application: Application,
    private val identity: DeviceIdentity,
    val discovery: NsdDiscovery,
    val transfer: TransferController,
    val history: HistoryRepository,
    val radio: RadioMonitor,
    val saf: SafAccess,
    private val scanner: FileScanner,
    val listenPort: () -> Int,
) : AndroidViewModel(application) {
    val screen = MutableStateFlow(Screen.HOME)
    val selected = MutableStateFlow<List<SelectedFile>>(emptyList())
    val pairing = MutableStateFlow<PairingPrompt?>(null)
    val incoming = MutableStateFlow<IncomingRequest?>(null)
    val currentPackage = MutableStateFlow<TransferPackage?>(null)
    val scanMode = MutableStateFlow(ScanMode.RECEIVE_PACKAGE)
    val scanFeedback = MutableStateFlow<Int?>(null)
    val preparing = MutableStateFlow(false)
    val receiving = MutableStateFlow(false)
    val lastSavedFolder = MutableStateFlow("")
    val libraryFolder = MutableStateFlow("")
    private var prepareGeneration = 0
    private var sasDeferred: CompletableDeferred<Boolean>? = null
    private var incomingDeferred: CompletableDeferred<Boolean>? = null
    private val scanTick = MutableStateFlow(0)

    val devices: StateFlow<List<NearbyDevice>> = discovery.devices
    val progress: StateFlow<TransferProgress?> = transfer.progress
    val transferError: StateFlow<ShareError?> = transfer.error
    val historyItems: StateFlow<List<HistoryEntity>> = history.dao.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val libraryQuery = MutableStateFlow("")
    val libraryFilter = MutableStateFlow(FileFilter.ALL)
    val libraryFiles: StateFlow<List<LibraryFile>> = combine(history.files.observe(), scanTick) { db, _ ->
        mergeLibrary(db, scanner.scanDownloads())
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            transfer.progress.collect { progress ->
                if (progress?.state == TransferState.COMPLETED) refreshLibrary()
            }
        }
    }

    val selectedTotal: Long
        get() = selected.value.sumOf { it.size }

    fun onUrisPicked(uris: List<Uri>) {
        uris.forEach { saf.persist(it) }
        val files = uris.map { saf.fromUri(it) }
        selected.value = PickerSelection.merge(selected.value, files)
        screen.value = Screen.SELECTED
    }

    fun preparePackageAndWait() {
        if (selected.value.isEmpty()) return
        if (!ensureReady()) return
        val generation = prepareGeneration
        receiving.value = false
        preparing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val hashed = selected.value.map { file ->
                try {
                    val hash = saf.hash(file.uri)
                    file.copy(hash = hash, mimeType = file.mimeType.ifBlank { "application/octet-stream" })
                } catch (_: Exception) {
                    file.copy(hash = null)
                }
            }
            if (generation != prepareGeneration) return@launch
            selected.value = hashed
            val packageFiles = hashed.map { file ->
                val available = file.hash != null
                PackageFile(
                    fileId = java.util.UUID.randomUUID().toString(),
                    name = file.name,
                    relativePath = file.name,
                    size = file.size,
                    mimeType = file.mimeType.ifBlank { "application/octet-stream" },
                    modifiedAt = file.modifiedAt,
                    hash = file.hash,
                    status = if (available) PackageFileStatus.PENDING else PackageFileStatus.UNAVAILABLE,
                )
            }
            val pkg = TransferPackage.create(
                sourceDeviceId = identity.deviceId,
                sourceDeviceName = identity.displayName,
                sourceOs = "android",
                files = packageFiles,
            ).copy(state = if (packageFiles.any { it.status == PackageFileStatus.UNAVAILABLE }) PackageState.FAILED else PackageState.READY)
            val readyFiles = hashed.filterIndexed { index, _ -> packageFiles[index].status != PackageFileStatus.UNAVAILABLE }
            var invite = newInvitation(pkg.packageId)
            if (invite == null) {
                repeat(25) {
                    delay(100)
                    invite = newInvitation(pkg.packageId)
                    if (invite != null) return@repeat
                }
            }
            val aligned = pkg.copy(
                files = packageFiles.filter { it.status != PackageFileStatus.UNAVAILABLE },
                state = PackageState.WAITING_FOR_RECEIVER,
                invitation = invite,
            )
            currentPackage.value = aligned
            transfer.pendingSend = PendingPackageSend(aligned, readyFiles)
            discovery.startAdvertising(listenPort(), aligned.invitation?.numericCode)
            if (generation != prepareGeneration) return@launch
            preparing.value = false
            screen.value = Screen.PACKAGE
        }
    }

    fun regenerateInvitation() {
        val pkg = currentPackage.value ?: return
        val invite = newInvitation(pkg.packageId) ?: return
        currentPackage.value = pkg.copy(invitation = invite, state = PackageState.WAITING_FOR_RECEIVER)
        transfer.pendingSend = transfer.pendingSend?.copy(pkg = currentPackage.value!!)
        discovery.startAdvertising(listenPort(), invite.numericCode)
    }

    private fun newInvitation(packageId: String): PackageInvitation? {
        val host = LocalAddress.ipv4(getApplication()) ?: return null
        val port = listenPort()
        if (port <= 0) return null
        return PackageInvitation.create(
            host = host,
            port = port,
            deviceId = identity.deviceId,
            os = "android",
            packageId = packageId,
        )
    }

    fun refreshLibrary() {
        scanTick.value = scanTick.value + 1
    }

    fun openFiles() {
        libraryFolder.value = ""
        refreshLibrary()
        screen.value = Screen.FILES
    }

    fun enterLibraryFolder(name: String) {
        libraryFolder.value = if (libraryFolder.value.isEmpty()) name else "${libraryFolder.value}/$name"
    }

    fun libraryBack() {
        val current = libraryFolder.value
        if (current.isEmpty()) {
            backHome()
        } else {
            libraryFolder.value = FolderBrowser.parentPath(current)
        }
    }

    fun devicesBack() {
        when {
            currentPackage.value != null -> screen.value = Screen.PACKAGE
            selected.value.isNotEmpty() -> screen.value = Screen.SELECTED
            else -> backHome()
        }
    }

    fun onSystemBack() {
        when (screen.value) {
            Screen.HOME -> Unit
            Screen.FILES -> libraryBack()
            Screen.PAIRING -> confirmPairing(false)
            Screen.INCOMING -> confirmIncoming(false)
            Screen.DEVICES -> devicesBack()
            else -> backHome()
        }
    }

    fun deleteFile(file: LibraryFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            runCatching { app.contentResolver.delete(file.uri, null, null) }
            if (file.uri.scheme == "file") {
                file.uri.path?.let { java.io.File(it).delete() }
            }
            runCatching { history.files.delete(file.id) }
            refreshLibrary()
        }
    }

    fun openSavedFolder() {
        FileOpener.openHonorShareFolder(getApplication())
    }

    fun removeAt(index: Int) {
        selected.value = selected.value.toMutableList().also { it.removeAt(index) }
        if (selected.value.isEmpty()) screen.value = Screen.SELECTED
    }

    fun clearSelected() {
        resetSession(keepScreen = false)
        screen.value = Screen.HOME
    }

    fun backHome() {
        resetSession(keepScreen = false)
        discovery.stopBrowse()
        discovery.startAdvertising(listenPort())
        screen.value = Screen.HOME
    }

    private fun resetSession(keepScreen: Boolean) {
        prepareGeneration += 1
        selected.value = emptyList()
        preparing.value = false
        transfer.pendingSend = null
        currentPackage.value = null
        pairing.value = null
        incoming.value = null
        receiving.value = false
        scanFeedback.value = null
        transfer.resetUi()
        if (!keepScreen) libraryFolder.value = ""
    }

    fun shareLink(): String {
        val host = LocalAddress.ipv4(getApplication()) ?: return ""
        val port = listenPort()
        if (port <= 0) return ""
        return ShareLink(
            host = host,
            port = port,
            id = identity.deviceId,
            name = identity.displayName,
            os = "android",
        ).encode()
    }

    fun openScan() {
        scanMode.value = ScanMode.SEND_NEARBY
        scanFeedback.value = null
        screen.value = Screen.SCAN
    }

    fun onLinkScanned(raw: String) {
        val invite = PackageInvitation.parse(raw)
        if (invite != null) {
            if (invite.isExpired()) {
                scanFeedback.value = R.string.error_invitation_expired
                return
            }
            scanFeedback.value = R.string.found_transfer
            receiveFromInvitation(invite)
            return
        }
        val link = ShareLink.parse(raw)
        if (link == null) {
            scanFeedback.value = R.string.error_invalid_invitation
            return
        }
        if (scanMode.value == ScanMode.RECEIVE_PACKAGE) {
            scanFeedback.value = R.string.error_invalid_invitation
            return
        }
        if (selected.value.isEmpty()) return
        sendTo(
            NearbyDevice(
                id = link.id,
                name = link.name,
                os = link.os,
                host = link.host,
                port = link.port,
            ),
        )
    }

    fun receiveFromInvitation(invite: PackageInvitation) {
        viewModelScope.launch {
            receiving.value = true
            screen.value = Screen.TRANSFER
            try {
                transfer.connectAndReceive(invite.host, invite.port)
                lastSavedFolder.value = transfer.lastSavedPath()
                screen.value = Screen.TRANSFER
            } catch (_: Exception) {
                screen.value = Screen.TRANSFER
            }
        }
    }

    private var codeLookupActive = false

    fun connectWithCode(raw: String) {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 6) {
            scanFeedback.value = R.string.error_invalid_invitation
            return
        }
        if (codeLookupActive) return
        codeLookupActive = true
        viewModelScope.launch {
            receiving.value = true
            discovery.startBrowse()
            scanFeedback.value = R.string.looking_for_mac
            val deadline = System.currentTimeMillis() + 10_000
            var match: NearbyDevice? = null
            while (System.currentTimeMillis() < deadline) {
                match = discovery.devices.value.firstOrNull {
                    it.inviteCode == digits && it.host.isNotBlank() && it.port > 0
                }
                if (match != null) break
                delay(400)
            }
            val found = match
            if (found == null) {
                scanFeedback.value = R.string.error_no_device
                receiving.value = false
                codeLookupActive = false
                return@launch
            }
            scanFeedback.value = R.string.found_transfer
            screen.value = Screen.TRANSFER
            try {
                transfer.connectAndReceive(found.host, found.port)
                lastSavedFolder.value = transfer.lastSavedPath()
                screen.value = Screen.TRANSFER
            } catch (_: Exception) {
                screen.value = Screen.TRANSFER
            } finally {
                codeLookupActive = false
            }
        }
    }

    fun openSend() {
        resetSession(keepScreen = false)
        screen.value = Screen.SELECTED
        discovery.startBrowse()
        discovery.startAdvertising(listenPort())
    }

    fun openReceive() {
        resetSession(keepScreen = false)
        scanMode.value = ScanMode.RECEIVE_PACKAGE
        scanFeedback.value = null
        discovery.startAdvertising(listenPort())
        screen.value = Screen.SCAN
    }

    fun openNearby() {
        discovery.startBrowse()
        discovery.startAdvertising(listenPort())
        screen.value = Screen.DEVICES
    }

    fun openLegacyReceive() {
        discovery.startAdvertising(listenPort())
        screen.value = Screen.RECEIVE
    }

    fun openHistory() {
        screen.value = Screen.HISTORY
    }

    fun clearHistory() {
        viewModelScope.launch {
            history.dao.clear()
            history.files.clear()
            refreshLibrary()
        }
    }

    fun chooseMac() {
        if (selected.value.isEmpty() && currentPackage.value == null) return
        if (!ensureReady()) return
        discovery.startBrowse()
        discovery.startAdvertising(listenPort())
        screen.value = Screen.DEVICES
    }

    fun sendTo(device: NearbyDevice) {
        viewModelScope.launch {
            receiving.value = false
            screen.value = Screen.TRANSFER
            try {
                transfer.sendTo(device.host, device.port, selected.value)
                screen.value = Screen.TRANSFER
            } catch (_: Exception) {
                screen.value = Screen.TRANSFER
            }
        }
    }

    fun confirmPairing(connect: Boolean) {
        sasDeferred?.complete(connect)
        sasDeferred = null
        pairing.value = null
        if (connect) screen.value = Screen.TRANSFER
    }

    fun confirmIncoming(accept: Boolean) {
        incomingDeferred?.complete(accept)
        incomingDeferred = null
        incoming.value = null
        if (accept) screen.value = Screen.TRANSFER
        else backHome()
    }

    fun cancelTransfer() {
        transfer.cancel()
    }

    fun retryDiscovery() {
        discovery.stopBrowse()
        discovery.startBrowse()
        discovery.startAdvertising(listenPort())
    }

    fun onNearbyPermissionGranted() {
        discovery.startAdvertising(listenPort(), currentPackage.value?.invitation?.numericCode)
        if (screen.value == Screen.SELECTED || screen.value == Screen.DEVICES || screen.value == Screen.RECEIVE || screen.value == Screen.PACKAGE || screen.value == Screen.SCAN) {
            discovery.stopBrowse()
            discovery.startBrowse()
        }
    }

    suspend fun onSas(sas: String, peerName: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        sasDeferred = deferred
        pairing.value = PairingPrompt(peerName, sas, Sas.display(sas))
        screen.value = Screen.PAIRING
        return deferred.await()
    }

    suspend fun onIncoming(request: IncomingRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        incomingDeferred = deferred
        receiving.value = true
        incoming.value = request
        screen.value = Screen.INCOMING
        val accepted = deferred.await()
        if (accepted) lastSavedFolder.value = transfer.lastSavedPath()
        return accepted
    }

    fun formatSize(bytes: Long): String = ByteFormat.humanSize(bytes)

    private fun ensureReady(): Boolean {
        if (!radio.wifiEnabled() && !radio.hasLocalNetwork()) {
            screen.value = Screen.RADIO
            return false
        }
        return true
    }

    override fun onCleared() {
        discovery.stopBrowse()
        super.onCleared()
    }
}

fun permissionDeniedError() = ShareError(ErrorCode.PERMISSION_DENIED, "permission")

private fun mergeLibrary(db: List<SharedFileEntity>, scanned: List<LibraryFile>): List<LibraryFile> {
    val fromDb = db.map { entity ->
        val match = scanned.find { it.uri.toString() == entity.uri || (it.name == entity.name && kotlin.math.abs(it.size - entity.size) < 2) }
        LibraryFile(
            id = entity.id,
            name = entity.name,
            mimeType = entity.mimeType,
            size = entity.size,
            uri = Uri.parse(entity.uri),
            direction = entity.direction,
            deviceName = entity.deviceName.ifBlank { match?.deviceName ?: "" },
            createdAt = entity.createdAt,
            relativePath = match?.relativePath ?: entity.name,
        )
    }
    val extra = scanned.filter { scan ->
        fromDb.none { it.uri == scan.uri || (it.name == scan.name && kotlin.math.abs(it.size - scan.size) < 2) }
    }
    return (fromDb + extra).sortedByDescending { it.createdAt }
}
