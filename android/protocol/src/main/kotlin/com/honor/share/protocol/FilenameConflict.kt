package com.honor.share.protocol

object FilenameConflict {
    fun uniqueName(desired: String, exists: (String) -> Boolean): String {
        val safe = desired.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        if (safe == "." || safe == ".." || safe.contains("..")) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "illegal file name")
        }
        if (!exists(safe)) return safe
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (index <= 10_000) {
            val candidate = "$base ($index)$ext"
            if (!exists(candidate)) return candidate
            index++
        }
        throw ProtocolException(ErrorCode.FILE_UNAVAILABLE, "too many name conflicts")
    }

    fun sanitizeRelativePath(path: String): String {
        val trimmed = path.replace('\\', '/').trim().trimStart('/')
        val parts = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "illegal relative path")
        }
        return parts.joinToString("/")
    }
}
