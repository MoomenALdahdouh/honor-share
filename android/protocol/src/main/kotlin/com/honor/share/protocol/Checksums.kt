package com.honor.share.protocol

import java.io.InputStream
import java.security.MessageDigest

object Checksums {
    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(stream: InputStream): String {
        val digest = IncrementalSha256()
        val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE)
        stream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.hex()
    }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        bytes.forEachIndexed { index, b ->
            val v = b.toInt() and 0xFF
            chars[index * 2] = hex[v ushr 4]
            chars[index * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    fun equalsHex(expected: String, actual: String): Boolean =
        expected.lowercase() == actual.lowercase()
}

class IncrementalSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        digest.update(data, offset, length)
    }

    fun hex(): String = Checksums.toHex(digest.digest())
}
