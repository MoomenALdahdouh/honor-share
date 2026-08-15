package com.honor.share.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.honor.share.protocol.GeneratedIdentity
import com.honor.share.protocol.SelfSignedCert
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val displayName: String,
    val generated: GeneratedIdentity,
)

class DeviceIdentityStore(private val context: Context) {
    fun loadOrCreate(): DeviceIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
        val file = File(context.filesDir, P12_NAME)
        val generated = if (file.exists()) {
            loadPkcs12(file)
        } else {
            val created = SelfSignedCert.generate()
            savePkcs12(file, created)
            created
        }
        return DeviceIdentity(
            deviceId = deviceId,
            displayName = deviceName(),
            generated = generated,
        )
    }

    private fun deviceName(): String {
        val global = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        if (!global.isNullOrBlank()) return global.take(40)
        return Build.MODEL.take(40)
    }

    private fun savePkcs12(file: File, identity: GeneratedIdentity) {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, PASS)
        store.setKeyEntry(ALIAS, identity.generatedPrivate(), PASS, arrayOf(identity.certificate))
        file.outputStream().use { store.store(it, PASS) }
    }

    private fun loadPkcs12(file: File): GeneratedIdentity {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, PASS) }
        val key = store.getKey(ALIAS, PASS) as java.security.PrivateKey
        val cert = store.getCertificate(ALIAS) as java.security.cert.X509Certificate
        val public = cert.publicKey
        return GeneratedIdentity(
            keyPair = java.security.KeyPair(public, key),
            certificate = cert,
            fingerprintSha256 = com.honor.share.protocol.Checksums.sha256Hex(cert.encoded),
        )
    }

    private fun GeneratedIdentity.generatedPrivate(): java.security.PrivateKey = keyPair.private

    companion object {
        private const val PREFS = "honor_share_identity"
        private const val KEY_ID = "device_id"
        private const val P12_NAME = "identity.p12"
        private const val ALIAS = "honor-share"
        private val PASS = "honor-share-local".toCharArray()
    }
}

class TrustedPeerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("honor_share_trusted", Context.MODE_PRIVATE)

    fun fingerprintFor(deviceId: String): String? = prefs.getString(deviceId, null)

    fun trust(deviceId: String, fingerprint: String, name: String) {
        prefs.edit()
            .putString(deviceId, fingerprint)
            .putString("name:$deviceId", name)
            .apply()
    }

    fun nameFor(deviceId: String): String? = prefs.getString("name:$deviceId", null)

    fun isTrusted(deviceId: String, fingerprint: String): Boolean =
        fingerprintFor(deviceId) == fingerprint
}

class RadioMonitor(private val context: Context) {
    fun wifiEnabled(): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifi.isWifiEnabled
    }

    fun hasLocalNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return true
            }
        }
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    (Build.VERSION.SDK_INT >= 26 && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                )
        ) {
            return true
        }
        return wifiEnabled()
    }
}
