package com.honor.share.protocol

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PeerCertCapture {
    @Volatile
    var certificate: X509Certificate? = null

    fun fingerprint(): String? = certificate?.encoded?.let { Checksums.sha256Hex(it) }
}

object TlsSockets {
    fun context(identity: GeneratedIdentity, capture: PeerCertCapture, expectedFingerprint: String? = null): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, PASS)
        store.setKeyEntry("honor-share", identity.keyPair.private, PASS, arrayOf(identity.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, PASS)
        val trust = arrayOf<TrustManager>(PinningTrustManager(capture, expectedFingerprint))
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, trust, SecureRandom())
        return ssl
    }

    fun wrapClient(plain: java.net.Socket, ssl: SSLContext, host: String, port: Int): SSLSocket {
        val socket = ssl.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket.useClientMode = true
        preferTls13(socket)
        socket.startHandshake()
        return socket
    }

    fun wrapServer(plain: java.net.Socket, ssl: SSLContext): SSLSocket {
        val address = plain.remoteSocketAddress as? java.net.InetSocketAddress
        val host = address?.hostString ?: "peer"
        val port = address?.port ?: plain.port
        val socket = ssl.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket.useClientMode = false
        socket.needClientAuth = true
        preferTls13(socket)
        socket.startHandshake()
        return socket
    }

    private fun preferTls13(socket: SSLSocket) {
        val supported = socket.supportedProtocols.toSet()
        val wanted = listOf("TLSv1.3", "TLSv1.2").filter { supported.contains(it) }
        if (wanted.isNotEmpty()) socket.enabledProtocols = wanted.toTypedArray()
    }

    private val PASS = "honor-share-local".toCharArray()
}

private class PinningTrustManager(
    private val capture: PeerCertCapture,
    private val expectedFingerprint: String?,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        capture.certificate = chain[0]
        val actual = Checksums.sha256Hex(chain[0].encoded)
        if (expectedFingerprint != null && !Checksums.equalsHex(expectedFingerprint, actual)) {
            throw CertificateException("certificate pin mismatch")
        }
    }
}
