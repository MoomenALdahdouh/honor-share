package com.honor.share.protocol

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class PackageState {
    DRAFT,
    PREPARING,
    READY,
    WAITING_FOR_RECEIVER,
    CONNECTING,
    AUTHENTICATING,
    COMPARING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    CANCELLED,
    EXPIRED,
    FAILED,
}

enum class PackageFileStatus {
    PENDING,
    UNAVAILABLE,
    SKIPPED,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
}

data class PackageFile(
    val fileId: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mimeType: String,
    val modifiedAt: Long?,
    val hash: String?,
    val status: PackageFileStatus = PackageFileStatus.PENDING,
) {
    fun toMeta(): FileMeta = FileMeta(
        fileId = fileId,
        name = name,
        size = size,
        mimeType = mimeType.ifBlank { "application/octet-stream" },
        relativePath = relativePath.ifBlank { name },
        sha256 = hash,
        modifiedAt = modifiedAt,
    )
}

data class TransferPackage(
    val packageId: String,
    val protocolVersion: Int = ProtocolConstants.VERSION,
    val createdAt: Long,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourceOs: String,
    val files: List<PackageFile>,
    val state: PackageState = PackageState.DRAFT,
    val invitation: PackageInvitation? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val availableCount: Int get() = files.count { it.status != PackageFileStatus.UNAVAILABLE }
    val unavailableCount: Int get() = files.count { it.status == PackageFileStatus.UNAVAILABLE }

    fun displayName(now: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(createdAt)).let { time ->
            val day = dayLabel(createdAt, now)
            if (day == null) time else "$day — $time"
        }
    }

    companion object {
        fun create(
            sourceDeviceId: String,
            sourceDeviceName: String,
            sourceOs: String,
            files: List<PackageFile>,
            now: Long = System.currentTimeMillis(),
        ): TransferPackage = TransferPackage(
            packageId = UUID.randomUUID().toString(),
            createdAt = now,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = sourceDeviceName,
            sourceOs = sourceOs,
            files = files,
            state = if (files.isEmpty()) PackageState.DRAFT else PackageState.PREPARING,
        )
    }
}

object PackageMachine {
    private val allowed: Map<PackageState, Set<PackageState>> = mapOf(
        PackageState.DRAFT to setOf(PackageState.PREPARING, PackageState.CANCELLED, PackageState.FAILED),
        PackageState.PREPARING to setOf(
            PackageState.READY,
            PackageState.DRAFT,
            PackageState.CANCELLED,
            PackageState.FAILED,
        ),
        PackageState.READY to setOf(
            PackageState.WAITING_FOR_RECEIVER,
            PackageState.PREPARING,
            PackageState.EXPIRED,
            PackageState.CANCELLED,
            PackageState.FAILED,
        ),
        PackageState.WAITING_FOR_RECEIVER to setOf(
            PackageState.CONNECTING,
            PackageState.READY,
            PackageState.EXPIRED,
            PackageState.CANCELLED,
            PackageState.FAILED,
        ),
        PackageState.CONNECTING to setOf(
            PackageState.AUTHENTICATING,
            PackageState.WAITING_FOR_RECEIVER,
            PackageState.FAILED,
            PackageState.CANCELLED,
        ),
        PackageState.AUTHENTICATING to setOf(
            PackageState.COMPARING,
            PackageState.TRANSFERRING,
            PackageState.FAILED,
            PackageState.CANCELLED,
        ),
        PackageState.COMPARING to setOf(
            PackageState.TRANSFERRING,
            PackageState.CANCELLED,
            PackageState.FAILED,
        ),
        PackageState.TRANSFERRING to setOf(
            PackageState.VERIFYING,
            PackageState.COMPLETED,
            PackageState.PARTIALLY_COMPLETED,
            PackageState.WAITING_FOR_RECEIVER,
            PackageState.CANCELLED,
            PackageState.FAILED,
        ),
        PackageState.VERIFYING to setOf(
            PackageState.TRANSFERRING,
            PackageState.COMPLETED,
            PackageState.PARTIALLY_COMPLETED,
            PackageState.FAILED,
            PackageState.CANCELLED,
        ),
        PackageState.COMPLETED to emptySet(),
        PackageState.PARTIALLY_COMPLETED to setOf(
            PackageState.TRANSFERRING,
            PackageState.WAITING_FOR_RECEIVER,
            PackageState.CANCELLED,
            PackageState.FAILED,
        ),
        PackageState.CANCELLED to setOf(PackageState.READY, PackageState.DRAFT),
        PackageState.EXPIRED to setOf(PackageState.READY, PackageState.WAITING_FOR_RECEIVER, PackageState.CANCELLED),
        PackageState.FAILED to setOf(
            PackageState.READY,
            PackageState.WAITING_FOR_RECEIVER,
            PackageState.TRANSFERRING,
            PackageState.CANCELLED,
        ),
    )

    fun canTransition(from: PackageState, to: PackageState): Boolean =
        from == to || allowed[from]?.contains(to) == true

    fun transition(from: PackageState, to: PackageState): PackageState {
        if (!canTransition(from, to)) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "illegal package transition $from -> $to")
        }
        return to
    }
}

internal fun dayLabel(createdAt: Long, now: Long): String? {
    val createdCal = Calendar.getInstance().apply { timeInMillis = createdAt }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    fun dayKey(calendar: Calendar) = calendar.get(Calendar.YEAR) * 1_000 + calendar.get(Calendar.DAY_OF_YEAR)
    return when (dayKey(nowCal) - dayKey(createdCal)) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(createdAt))
    }
}
