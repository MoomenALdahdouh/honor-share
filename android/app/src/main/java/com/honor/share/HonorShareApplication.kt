package com.honor.share

import android.app.Application
import com.honor.share.core.DeviceIdentityStore
import com.honor.share.core.RadioMonitor
import com.honor.share.core.ShareLog
import com.honor.share.core.TrustedPeerStore
import com.honor.share.discovery.NsdDiscovery
import com.honor.share.history.HistoryRepository
import com.honor.share.storage.DownloadsSinkFactory
import com.honor.share.storage.FileScanner
import com.honor.share.storage.SafAccess
import com.honor.share.transfer.IncomingRequest
import com.honor.share.transfer.TransferController

class CallbackBridge<A, B, R>(private val fallback: R) {
    @Volatile
    var impl: (suspend (A, B) -> R)? = null

    suspend fun invoke(a: A, b: B): R = impl?.invoke(a, b) ?: fallback
}

class AppContainer(app: Application) {
    val identity = DeviceIdentityStore(app).loadOrCreate()
    val trusted = TrustedPeerStore(app)
    val history = HistoryRepository(app)
    val radio = RadioMonitor(app)
    val saf = SafAccess(app)
    val downloads = DownloadsSinkFactory(app)
    val scanner = FileScanner(app)
    val discovery = NsdDiscovery(app, identity)
    val sasBridge = CallbackBridge<String, String, Boolean>(false)
    val incomingBridge = CallbackBridge<IncomingRequest, Unit, Boolean>(false)
    val transfer = TransferController(
        identity = identity,
        trusted = trusted,
        history = history,
        saf = saf,
        downloads = downloads,
        confirmSas = { sas, name -> sasBridge.invoke(sas, name) },
        confirmIncoming = { request -> incomingBridge.invoke(request, Unit) },
    )

    init {
        ShareLog.i("app", "identity ${identity.deviceId.take(8)}")
        transfer.startServer()
        discovery.startAdvertising(transfer.listenPort)
    }
}

class HonorShareApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ShareLog.debugEnabled = true
        container = AppContainer(this)
    }
}
