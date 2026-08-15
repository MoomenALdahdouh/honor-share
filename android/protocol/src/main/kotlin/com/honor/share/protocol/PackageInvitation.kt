package com.honor.share.protocol

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PackageInvitation(
    val protocolVersion: Int = ProtocolConstants.VERSION,
    val host: String,
    val port: Int,
    val deviceId: String,
    val os: String,
    val packageId: String,
    val inviteId: String,
    val expiresAtEpochSec: Long,
    val numericCode: String,
) {
    fun encode(): String = listOf(
        PREFIX,
        protocolVersion.toString(),
        host,
        port.toString(),
        deviceId,
        os,
        packageId,
        inviteId,
        expiresAtEpochSec.toString(),
        numericCode,
    ).joinToString("|")

    fun isExpired(nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSec >= expiresAtEpochSec

    fun remainingSeconds(nowEpochSec: Long = System.currentTimeMillis() / 1000): Long =
        (expiresAtEpochSec - nowEpochSec).coerceAtLeast(0)

    fun displayCode(): String = numericCode.chunked(3).joinToString(" ")

    companion object {
        const val PREFIX: String = "HS2"

        fun create(
            host: String,
            port: Int,
            deviceId: String,
            os: String,
            packageId: String,
            ttlSec: Long = ProtocolConstants.INVITATION_TTL_SEC,
            nowEpochSec: Long = System.currentTimeMillis() / 1000,
            numericCode: String = randomNumericCode(),
        ): PackageInvitation = PackageInvitation(
            host = host,
            port = port,
            deviceId = deviceId,
            os = os,
            packageId = packageId,
            inviteId = UUID.randomUUID().toString(),
            expiresAtEpochSec = nowEpochSec + ttlSec,
            numericCode = numericCode,
        )

        fun randomNumericCode(): String {
            val value = SecureRandom().nextInt(1_000_000)
            return String.format("%06d", value)
        }

        fun parse(raw: String): PackageInvitation? {
            val parts = raw.trim().split("|")
            if (parts.size != 10 || parts[0] != PREFIX) return null
            val version = parts[1].toIntOrNull() ?: return null
            if (version != ProtocolConstants.VERSION) return null
            val host = parts[2]
            if (host.isBlank() || host.any { it.isWhitespace() }) return null
            val port = parts[3].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val code = parts[9]
            if (code.length != 6 || code.any { !it.isDigit() }) return null
            val exp = parts[8].toLongOrNull() ?: return null
            if (parts[4].isBlank() || parts[6].isBlank() || parts[7].isBlank()) return null
            return PackageInvitation(
                protocolVersion = version,
                host = host,
                port = port,
                deviceId = parts[4],
                os = parts[5].ifBlank { "unknown" },
                packageId = parts[6],
                inviteId = parts[7],
                expiresAtEpochSec = exp,
                numericCode = code,
            )
        }
    }
}

class InviteRateLimiter(
    private val maxAttempts: Int = ProtocolConstants.INVITE_MAX_ATTEMPTS,
    private val windowMs: Long = ProtocolConstants.INVITE_WINDOW_MS,
    private val lockoutMs: Long = ProtocolConstants.INVITE_LOCKOUT_MS,
) {
    private data class Bucket(var failures: Int, var windowStart: Long, var lockedUntil: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets[key] ?: return true
        if (now < bucket.lockedUntil) return false
        if (now - bucket.windowStart > windowMs) return true
        return bucket.failures < maxAttempts
    }

    fun recordFailure(key: String, now: Long = System.currentTimeMillis()) {
        val bucket = buckets.getOrPut(key) { Bucket(0, now, 0) }
        if (now - bucket.windowStart > windowMs) {
            bucket.failures = 0
            bucket.windowStart = now
            bucket.lockedUntil = 0
        }
        bucket.failures += 1
        if (bucket.failures >= maxAttempts) {
            bucket.lockedUntil = now + lockoutMs
        }
    }

    fun recordSuccess(key: String) {
        buckets.remove(key)
    }
}
