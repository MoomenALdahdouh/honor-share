package com.honor.share.protocol

object ProtocolConstants {
    const val VERSION: Int = 1
    const val SERVICE_TYPE: String = "_honor-share._tcp"
    const val SERVICE_TYPE_ANDROID: String = "_honor-share._tcp."
    const val TXT_VERSION: String = "v"
    const val TXT_ID: String = "id"
    const val TXT_NAME: String = "name"
    const val TXT_OS: String = "os"
    const val TXT_INVITE: String = "inv"
    const val TXT_HOST: String = "h"
    const val TXT_PORT: String = "p"
    const val INVITATION_TTL_SEC: Long = 600L
    const val INVITE_MAX_ATTEMPTS: Int = 8
    const val INVITE_WINDOW_MS: Long = 5 * 60 * 1000L
    const val INVITE_LOCKOUT_MS: Long = 60_000L

    fun inviteServiceName(numericCode: String): String = "HS-$numericCode"

    fun inviteCodeFromServiceName(serviceName: String): String? {
        val match = Regex("""HS-(\d{6})""").find(serviceName) ?: return null
        return match.groupValues[1]
    }
    const val MAX_DISPLAY_NAME_BYTES: Int = 40
    const val MAX_FRAME_LENGTH: Int = 1_048_576
    const val CHUNK_SIZE: Int = 256 * 1024
    const val KIND_CONTROL: Int = 0
    const val KIND_BINARY: Int = 1
    const val DEVICE_STALE_MS: Long = 30_000L
    const val MAX_FILES_PER_TRANSFER: Int = 10_000
    const val PARTIAL_SUFFIX: String = ".honor-share-partial"
    const val DEFAULT_RECEIVE_FOLDER: String = "HONOR Share"

    fun receiveSubfolder(peerName: String, nowMs: Long = System.currentTimeMillis(), fileCount: Int = 1): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(nowMs))
        val cleaned = peerName.replace("/", "-").replace(":", "-").trim()
        val folder = cleaned.ifBlank { "Device" }.take(40)
        val base = "$day/$folder"
        if (fileCount <= 1) return base
        return "$base/${batchFolder(nowMs)}"
    }

    fun batchFolder(nowMs: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date(nowMs))
}
