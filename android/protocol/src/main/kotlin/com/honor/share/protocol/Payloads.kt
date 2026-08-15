package com.honor.share.protocol

import kotlinx.serialization.Serializable

@Serializable
data class HelloPayload(
    val deviceId: String,
    val name: String,
    val os: String,
    val protocolVersion: Int = ProtocolConstants.VERSION,
    val certFingerprint: String,
    val authNonce: String,
)

@Serializable
data class AuthChallengePayload(
    val method: String = "sas-v1",
)

@Serializable
data class AuthResponsePayload(
    val confirmed: Boolean,
    val trusted: Boolean = false,
)

@Serializable
data class FileMeta(
    val fileId: String,
    val name: String,
    val size: Long,
    val mimeType: String = "application/octet-stream",
    val relativePath: String = name,
    val sha256: String? = null,
    val modifiedAt: Long? = null,
)

@Serializable
data class TransferRequestPayload(
    val transferId: String,
    val files: List<FileMeta>,
    val totalBytes: Long,
    val packageId: String? = null,
)

@Serializable
data class TransferIdPayload(
    val transferId: String,
)

@Serializable
data class TransferAcceptedPayload(
    val transferId: String,
    val skipFileIds: List<String> = emptyList(),
)

@Serializable
data class TransferRejectedPayload(
    val transferId: String,
    val reason: String,
)

@Serializable
data class FileStartPayload(
    val transferId: String,
    val fileId: String,
    val name: String,
    val size: Long,
    val mimeType: String = "application/octet-stream",
    val offset: Long = 0,
)

@Serializable
data class FileProgressPayload(
    val transferId: String,
    val fileId: String,
    val bytesTransferred: Long,
)

@Serializable
data class FileCompletePayload(
    val transferId: String,
    val fileId: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
data class FileResumePayload(
    val transferId: String,
    val fileId: String,
    val bytesReceived: Long,
)

@Serializable
data class TransferCancelledPayload(
    val transferId: String,
    val reason: String = "USER_CANCELLED",
)

@Serializable
data class ErrorPayload(
    val code: String,
    val transferId: String? = null,
    val fileId: String? = null,
)
