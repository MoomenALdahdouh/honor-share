package com.honor.share.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.honor.share.core.DeviceIdentity
import com.honor.share.core.LocalAddress
import com.honor.share.core.ShareLog
import com.honor.share.protocol.ProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class NearbyDevice(
    val id: String,
    val name: String,
    val os: String,
    val host: String,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis(),
    val inviteCode: String? = null,
)

class NsdDiscovery(
    context: Context,
    private val identity: DeviceIdentity,
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registered: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val seen = ConcurrentHashMap<String, NearbyDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val resolveAttempts = ConcurrentHashMap<String, Int>()
    private val serviceCallbacks = ConcurrentHashMap<String, Any>()
    private var resolving = false
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices

    private var advertiseGeneration = 0
    private var advertisePort = 0
    private var advertiseInvite: String? = null

    @Synchronized
    fun startAdvertising(port: Int, inviteCode: String? = null) {
        if (port <= 0) return
        advertisePort = port
        advertiseInvite = inviteCode
        val generation = ++advertiseGeneration
        if (registered != null) {
            stopAdvertising()
            handler.postDelayed({
                if (generation == advertiseGeneration) registerNow()
            }, 450)
        } else {
            registerNow()
        }
    }

    @Synchronized
    private fun registerNow() {
        val port = advertisePort
        if (port <= 0) return
        val inviteCode = advertiseInvite
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = inviteCode?.let { ProtocolConstants.inviteServiceName(it) }
                ?: ("HS-" + identity.deviceId.take(8))
            serviceType = ProtocolConstants.SERVICE_TYPE_ANDROID
            setPort(port)
            setAttribute(ProtocolConstants.TXT_VERSION, ProtocolConstants.VERSION.toString())
            setAttribute(ProtocolConstants.TXT_ID, identity.deviceId)
            setAttribute(ProtocolConstants.TXT_NAME, identity.displayName.take(40))
            setAttribute(ProtocolConstants.TXT_OS, "android")
            if (!inviteCode.isNullOrBlank()) {
                setAttribute(ProtocolConstants.TXT_INVITE, inviteCode)
            }
            LocalAddress.ipv4(app)?.let { host ->
                setAttribute(ProtocolConstants.TXT_HOST, host)
                setAttribute(ProtocolConstants.TXT_PORT, port.toString())
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                ShareLog.i("nsd", "advertising ${serviceInfo.serviceName} invite=${advertiseInvite ?: "-"}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                ShareLog.e("nsd", "advertise failed $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                ShareLog.i("nsd", "stopped advertising")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                ShareLog.w("nsd", "unregister failed $errorCode")
            }
        }
        registered = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stopAdvertising() {
        registered?.let {
            try {
                nsd.unregisterService(it)
            } catch (error: Exception) {
                ShareLog.w("nsd", "unregister: ${error.message}")
            }
        }
        registered = null
    }

    @Synchronized
    fun startBrowse() {
        if (discovery != null) return
        multicastLock = wifi.createMulticastLock("honor-share").apply {
            setReferenceCounted(true)
            acquire()
        }
        beginDiscovery(ProtocolConstants.SERVICE_TYPE_ANDROID)
    }

    @Synchronized
    private fun beginDiscovery(type: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                ShareLog.i("nsd", "browse started $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                ShareLog.i("nsd", "found $name type=${serviceInfo.serviceType}")
                if (name == "HS-" + identity.deviceId.take(8)) return
                val inviteFromName = ProtocolConstants.inviteCodeFromServiceName(name)
                seen[name] = seen[name]?.copy(lastSeen = System.currentTimeMillis())
                    ?: NearbyDevice(
                        id = name,
                        name = name,
                        os = "macos",
                        host = "",
                        port = 0,
                        inviteCode = inviteFromName,
                    )
                publish()
                watchOrResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                ShareLog.i("nsd", "lost ${serviceInfo.serviceName}")
                serviceInfo.serviceName?.let { key ->
                    unregisterCallback(key)
                    seen.remove(key)
                    seen.entries.removeIf { it.value.id == key || it.value.name == key }
                }
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                ShareLog.i("nsd", "browse stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                ShareLog.e("nsd", "browse failed $errorCode type=$serviceType")
                discovery = null
                if (type == ProtocolConstants.SERVICE_TYPE_ANDROID) {
                    handler.post { beginDiscovery(ProtocolConstants.SERVICE_TYPE) }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                ShareLog.w("nsd", "stop browse failed $errorCode")
            }
        }
        discovery = listener
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stopBrowse() {
        handler.removeCallbacksAndMessages(null)
        unregisterAllCallbacks()
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (error: Exception) {
                ShareLog.w("nsd", "stop browse: ${error.message}")
            }
        }
        discovery = null
        resolving = false
        resolveQueue.clear()
        resolveAttempts.clear()
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
        seen.clear()
        _devices.value = emptyList()
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val stale = seen.entries.filter { now - it.value.lastSeen > ProtocolConstants.DEVICE_STALE_MS }.map { it.key }
        stale.forEach { seen.remove(it) }
        publish()
    }

    @Suppress("NewApi")
    private fun watchOrResolve(info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            if (serviceCallbacks.containsKey(name)) {
                enqueueResolve(info)
                return
            }
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    ShareLog.w("nsd", "info callback failed $errorCode for $name")
                    serviceCallbacks.remove(name)
                    enqueueResolve(info)
                }

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    handleResolved(updated)
                }

                override fun onServiceLost() {
                    ShareLog.i("nsd", "callback lost $name")
                    seen.remove(name)
                    publish()
                }

                override fun onServiceInfoCallbackUnregistered() {
                    serviceCallbacks.remove(name)
                }
            }
            try {
                nsd.registerServiceInfoCallback(info, app.mainExecutor, callback)
                serviceCallbacks[name] = callback
                handler.postDelayed({
                    val current = seen[name]
                    if (current == null || current.port <= 0 || current.host.isBlank()) {
                        ShareLog.w("nsd", "callback slow, resolving $name")
                        enqueueResolve(info)
                    }
                }, 1500)
                return
            } catch (error: Exception) {
                ShareLog.w("nsd", "registerServiceInfoCallback: ${error.message}")
                serviceCallbacks.remove(name)
            }
        }
        enqueueResolve(info)
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(this) {
            resolveQueue.addLast(info)
        }
        pumpResolve()
    }

    @Suppress("DEPRECATION")
    private fun pumpResolve() {
        val next: NsdServiceInfo?
        synchronized(this) {
            if (resolving) return
            next = resolveQueue.pollFirst()
            if (next != null) resolving = true
        }
        val info = next ?: return
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val key = serviceInfo.serviceName ?: "?"
                    val tries = (resolveAttempts[key] ?: 0) + 1
                    resolveAttempts[key] = tries
                    ShareLog.w("nsd", "resolve failed $errorCode for $key try=$tries")
                    synchronized(this@NsdDiscovery) { resolving = false }
                    if (tries < 6) {
                        handler.postDelayed({ enqueueResolve(serviceInfo) }, 400L * tries)
                    }
                    pumpResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolveAttempts.remove(serviceInfo.serviceName)
                    handleResolved(serviceInfo)
                    synchronized(this@NsdDiscovery) { resolving = false }
                    pumpResolve()
                }
            },
        )
    }

    @Synchronized
    private fun handleResolved(serviceInfo: NsdServiceInfo) {
        val host = ipv4Host(serviceInfo)
        ShareLog.i(
            "nsd",
            "resolved name=${serviceInfo.serviceName} host=$host port=${serviceInfo.port} " +
                "txt=${serviceInfo.attributes.keys}",
        )
        if (host.isNullOrBlank() || serviceInfo.port <= 0) return
        val id = txt(serviceInfo, ProtocolConstants.TXT_ID) ?: serviceInfo.serviceName ?: host
        if (id == identity.deviceId) return
        val name = txt(serviceInfo, ProtocolConstants.TXT_NAME)
            ?: serviceInfo.serviceName
            ?: host
        val device = NearbyDevice(
            id = id,
            name = name,
            os = txt(serviceInfo, ProtocolConstants.TXT_OS) ?: "macos",
            host = host,
            port = serviceInfo.port,
            inviteCode = txt(serviceInfo, ProtocolConstants.TXT_INVITE)
                ?: ProtocolConstants.inviteCodeFromServiceName(serviceInfo.serviceName ?: ""),
        )
        serviceInfo.serviceName?.let { seen.remove(it) }
        seen[id] = device
        publish()
    }

    private fun ipv4Host(info: NsdServiceInfo): String? {
        val addresses = mutableListOf<java.net.InetAddress>()
        if (Build.VERSION.SDK_INT >= 34) {
            addresses += info.hostAddresses
        }
        info.host?.let { addresses += it }
        return addresses.firstOrNull { it is Inet4Address }?.hostAddress
            ?: addresses.firstOrNull()?.hostAddress
    }

    private fun txt(info: NsdServiceInfo, key: String): String? {
        val raw = info.attributes[key] ?: info.attributes["$key\u0000"] ?: return null
        return String(raw, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }.ifBlank { null }
    }

    private fun unregisterCallback(name: String) {
        val callback = serviceCallbacks.remove(name) ?: return
        if (Build.VERSION.SDK_INT >= 34 && callback is NsdManager.ServiceInfoCallback) {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    private fun unregisterAllCallbacks() {
        serviceCallbacks.keys.toList().forEach { unregisterCallback(it) }
        serviceCallbacks.clear()
    }

    private fun publish() {
        _devices.update {
            seen.values.sortedBy { device -> device.name.lowercase() }
        }
    }
}
