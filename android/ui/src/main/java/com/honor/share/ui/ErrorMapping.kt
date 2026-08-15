package com.honor.share.ui

import com.honor.share.protocol.ErrorCode

fun ErrorCode.userStringRes(): Int = when (this) {
    ErrorCode.NO_DEVICE -> R.string.error_no_device
    ErrorCode.PERMISSION_DENIED -> R.string.error_permission
    ErrorCode.RADIO_OFF -> R.string.error_radio
    ErrorCode.AUTH_FAILED -> R.string.error_auth
    ErrorCode.TIMEOUT -> R.string.error_timeout
    ErrorCode.CONNECTION_LOST, ErrorCode.DEVICE_DISCONNECTED -> R.string.error_connection
    ErrorCode.FILE_UNAVAILABLE, ErrorCode.FILE_PERMISSION_LOST -> R.string.error_file
    ErrorCode.DISK_FULL -> R.string.error_disk
    ErrorCode.CHECKSUM_MISMATCH -> R.string.verification_failed_body
    ErrorCode.UNSUPPORTED_VERSION -> R.string.error_version
    ErrorCode.CANCELLED, ErrorCode.USER_REJECTED -> R.string.transfer_cancelled
    ErrorCode.INVITATION_EXPIRED -> R.string.error_invitation_expired
    ErrorCode.INVALID_INVITATION -> R.string.error_invalid_invitation
    ErrorCode.RATE_LIMITED -> R.string.error_rate_limited
    else -> R.string.error_generic
}
