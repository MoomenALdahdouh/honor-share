package com.honor.share.protocol

enum class ErrorCode {
    UNSUPPORTED_VERSION,
    UNKNOWN_MESSAGE,
    AUTH_FAILED,
    TIMEOUT,
    CONNECTION_LOST,
    FILE_UNAVAILABLE,
    DISK_FULL,
    CHECKSUM_MISMATCH,
    CANCELLED,
    PROTOCOL_VIOLATION,
    PERMISSION_DENIED,
    RADIO_OFF,
    NO_DEVICE,
    DESTINATION_UNAVAILABLE,
    FILE_PERMISSION_LOST,
    DEVICE_DISCONNECTED,
    INVITATION_EXPIRED,
    INVALID_INVITATION,
    RATE_LIMITED,
    USER_REJECTED,
    ;

    companion object {
        fun fromWire(value: String): ErrorCode =
            entries.firstOrNull { it.name == value } ?: PROTOCOL_VIOLATION
    }
}
