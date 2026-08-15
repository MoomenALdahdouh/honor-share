package com.honor.share.protocol

enum class MessageType {
    HELLO,
    HELLO_ACK,
    AUTH_CHALLENGE,
    AUTH_RESPONSE,
    TRANSFER_REQUEST,
    TRANSFER_ACCEPTED,
    TRANSFER_REJECTED,
    FILE_START,
    FILE_PROGRESS,
    FILE_COMPLETE,
    FILE_RESUME,
    TRANSFER_PAUSE,
    TRANSFER_COMPLETE,
    TRANSFER_CANCELLED,
    ERROR,
    ;

    companion object {
        fun fromWire(value: String): MessageType? =
            entries.firstOrNull { it.name == value }
    }
}
