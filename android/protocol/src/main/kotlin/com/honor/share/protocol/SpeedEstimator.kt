package com.honor.share.protocol

class SpeedEstimator(
    private val minElapsedMs: Long = 2_000L,
    private val smoothing: Double = 0.25,
) {
    private var lastBytes: Long = 0
    private var lastTime: Long = 0
    private var ema: Double = 0.0
    private var startedAt: Long = -1
    private var samples: Int = 0

    fun reset() {
        lastBytes = 0
        lastTime = 0
        ema = 0.0
        startedAt = -1
        samples = 0
    }

    fun onProgress(bytesTransferred: Long, nowMs: Long): Double {
        if (startedAt < 0) {
            startedAt = nowMs
            lastBytes = bytesTransferred
            lastTime = nowMs
            return 0.0
        }
        val dt = nowMs - lastTime
        if (dt < 80) return ema
        val instant = (bytesTransferred - lastBytes).toDouble() * 1000.0 / dt.toDouble()
        ema = if (samples == 0) instant else (smoothing * instant + (1.0 - smoothing) * ema)
        lastBytes = bytesTransferred
        lastTime = nowMs
        samples++
        if (ema < 0) ema = 0.0
        return ema
    }

    fun bytesPerSecond(): Double = ema

    fun etaSeconds(remainingBytes: Long, nowMs: Long = lastTime): Long? {
        if (remainingBytes <= 0) return 0
        if (samples < 3) return null
        if (nowMs - startedAt < minElapsedMs) return null
        if (ema < 50_000) return null
        val eta = remainingBytes / ema
        if (eta.isNaN() || eta.isInfinite() || eta > 24 * 3600) return null
        return eta.toLong().coerceAtLeast(1)
    }
}

object ByteFormat {
    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        val pattern = if (value >= 10) "%.0f %s" else "%.1f %s"
        return pattern.format(value, units[unit])
    }

    fun humanSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond < 1) return "0 B/s"
        return humanSize(bytesPerSecond.toLong()) + "/s"
    }
}
