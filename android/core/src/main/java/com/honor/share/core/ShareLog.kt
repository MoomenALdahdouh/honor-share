package com.honor.share.core

import android.util.Log
import com.honor.share.protocol.ErrorCode

object ShareLog {
    @Volatile
    var debugEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (debugEnabled) Log.d(prefix(tag), sanitize(message))
    }

    fun i(tag: String, message: String) {
        Log.i(prefix(tag), sanitize(message))
    }

    fun w(tag: String, message: String) {
        Log.w(prefix(tag), sanitize(message))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e(prefix(tag), sanitize(message), error)
        } else {
            Log.e(prefix(tag), sanitize(message))
        }
    }

    private fun prefix(tag: String): String = "HonorShare/$tag"

    private fun sanitize(message: String): String {
        return message
            .replace(Regex("(?i)(token|secret|password|private[_ ]?key|authNonce)\\s*[=:]\\s*\\S+"), "$1=*")
            .replace(Regex("/Users/[^\\s]+"), "[path]")
            .replace(Regex("/home/[^\\s]+"), "[path]")
    }
}

data class ShareError(
    val code: ErrorCode,
    val debugMessage: String,
) {
    companion object {
        fun from(code: ErrorCode, debugMessage: String, cause: Throwable? = null): ShareError {
            if (cause != null) {
                ShareLog.e("error", debugMessage, cause)
            } else {
                ShareLog.w("error", "${code.name}: $debugMessage")
            }
            return ShareError(code, debugMessage)
        }
    }
}
