package com.honor.share.protocol

import java.security.MessageDigest

object Sas {
    fun compute(fingerprintA: String, fingerprintB: String, authNonce: String): String {
        val a = fingerprintA.lowercase()
        val b = fingerprintB.lowercase()
        val (first, second) = if (a <= b) a to b else b to a
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$first|$second|$authNonce".toByteArray(Charsets.UTF_8))
        var n = 0L
        for (i in 0 until 4) {
            n = (n shl 8) or (digest[i].toLong() and 0xFF)
        }
        return (n % 1_000_000L).toString().padStart(6, '0')
    }

    fun display(code: String): String {
        val padded = code.padStart(6, '0')
        return padded.substring(0, 3) + " " + padded.substring(3)
    }
}
