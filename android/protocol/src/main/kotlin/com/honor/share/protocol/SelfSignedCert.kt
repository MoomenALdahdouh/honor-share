package com.honor.share.protocol

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

data class GeneratedIdentity(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val fingerprintSha256: String,
)

object SelfSignedCert {
    private val sha256Rsa = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    private val commonName = intArrayOf(2, 5, 4, 3)

    fun generate(commonNameValue: String = "HONOR Share"): GeneratedIdentity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger(64, SecureRandom())
        val tbs = tbsCertificate(commonNameValue, keyPair, serial, notBefore, notAfter)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(tbs)
        val signed = signature.sign()
        val der = Der.sequence(
            tbs,
            Der.sequence(Der.oid(sha256Rsa), Der.nullValue()),
            Der.bitString(signed),
        )
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(keyPair.public)
        return GeneratedIdentity(
            keyPair = keyPair,
            certificate = cert,
            fingerprintSha256 = Checksums.sha256Hex(cert.encoded),
        )
    }

    private fun tbsCertificate(
        cn: String,
        keyPair: KeyPair,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
    ): ByteArray {
        val name = Der.sequence(
            Der.set(
                Der.sequence(
                    Der.oid(commonName),
                    Der.utf8(cn),
                ),
            ),
        )
        val validity = Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter))
        val spki = keyPair.public.encoded
        return Der.sequence(
            Der.context(0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(serial),
            Der.sequence(Der.oid(sha256Rsa), Der.nullValue()),
            name,
            validity,
            name,
            spki,
        )
    }

}

internal object Der {
    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(*parts))

    fun integer(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        return tlv(0x02, bytes)
    }

    fun oid(parts: IntArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add((40 * parts[0] + parts[1]).toByte())
        for (i in 2 until parts.size) {
            val encoded = base128(parts[i])
            encoded.forEach { out.add(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun utf8(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    fun bitString(value: ByteArray): ByteArray {
        val content = ByteArray(value.size + 1)
        content[0] = 0
        value.copyInto(content, 1)
        return tlv(0x03, content)
    }

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun utcTime(date: Date): ByteArray {
        val formatted = java.text.SimpleDateFormat("yyMMddHHmmss'Z'").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(date)
        return tlv(0x17, formatted.toByteArray(Charsets.US_ASCII))
    }

    fun context(number: Int, content: ByteArray): ByteArray = tlv(0xA0 or number, content)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val length = lengthBytes(content.size)
        val out = ByteArray(1 + length.size + content.size)
        out[0] = tag.toByte()
        length.copyInto(out, 1)
        content.copyInto(out, 1 + length.size)
        return out
    }

    private fun lengthBytes(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        val bytes = ArrayList<Byte>()
        var value = length
        while (value > 0) {
            bytes.add(0, (value and 0xFF).toByte())
            value = value ushr 8
        }
        val out = ByteArray(1 + bytes.size)
        out[0] = (0x80 or bytes.size).toByte()
        bytes.forEachIndexed { i, b -> out[i + 1] = b }
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var o = 0
        for (part in parts) {
            part.copyInto(out, o)
            o += part.size
        }
        return out
    }

    private fun base128(value: Int): ByteArray {
        if (value < 128) return byteArrayOf(value.toByte())
        val stack = ArrayList<Int>()
        var v = value
        stack.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            stack.add(0, (v and 0x7F) or 0x80)
            v = v ushr 7
        }
        return ByteArray(stack.size) { stack[it].toByte() }
    }
}
