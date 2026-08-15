package com.honor.share.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ShareLink(
    val host: String,
    val port: Int,
    val id: String,
    val name: String,
    val os: String,
) {
    fun encode(): String {
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
        return listOf(PREFIX, host, port.toString(), id, os, encodedName).joinToString("|")
    }

    companion object {
        const val PREFIX: String = "HS1"

        fun parse(raw: String): ShareLink? {
            val text = raw.trim()
            val parts = text.split("|")
            if (parts.size < 6 || parts[0] != PREFIX) return null
            val port = parts[2].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val host = parts[1]
            if (host.isBlank() || host.any { it.isWhitespace() }) return null
            val name = try {
                URLDecoder.decode(parts.drop(5).joinToString("|"), StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                return null
            }
            return ShareLink(
                host = host,
                port = port,
                id = parts[3].ifBlank { host },
                name = name.ifBlank { host },
                os = parts[4].ifBlank { "unknown" },
            )
        }
    }
}
