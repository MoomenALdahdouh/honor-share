package com.honor.share.protocol

object RetryPolicy {
    const val MAX_ATTEMPTS: Int = 3
    const val BASE_DELAY_MS: Long = 400

    fun shouldRetry(code: ErrorCode, attempt: Int): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        return when (code) {
            ErrorCode.CONNECTION_LOST, ErrorCode.TIMEOUT, ErrorCode.DEVICE_DISCONNECTED -> true
            else -> false
        }
    }

    fun delayMs(attempt: Int): Long = BASE_DELAY_MS * (1L shl attempt.coerceAtLeast(0))
}
