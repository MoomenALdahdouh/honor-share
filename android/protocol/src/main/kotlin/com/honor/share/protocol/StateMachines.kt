package com.honor.share.protocol

enum class ConnectionState {
    IDLE,
    DISCOVERING,
    DEVICE_FOUND,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

object ConnectionMachine {
    private val allowed: Map<ConnectionState, Set<ConnectionState>> = mapOf(
        ConnectionState.IDLE to setOf(ConnectionState.DISCOVERING, ConnectionState.CONNECTING, ConnectionState.FAILED),
        ConnectionState.DISCOVERING to setOf(
            ConnectionState.DEVICE_FOUND,
            ConnectionState.IDLE,
            ConnectionState.FAILED,
            ConnectionState.DISCOVERING,
        ),
        ConnectionState.DEVICE_FOUND to setOf(
            ConnectionState.CONNECTING,
            ConnectionState.DISCOVERING,
            ConnectionState.IDLE,
            ConnectionState.FAILED,
        ),
        ConnectionState.CONNECTING to setOf(
            ConnectionState.AUTHENTICATING,
            ConnectionState.CONNECTED,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        ),
        ConnectionState.AUTHENTICATING to setOf(
            ConnectionState.CONNECTED,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        ),
        ConnectionState.CONNECTED to setOf(ConnectionState.DISCONNECTED, ConnectionState.FAILED),
        ConnectionState.DISCONNECTED to setOf(
            ConnectionState.IDLE,
            ConnectionState.DISCOVERING,
            ConnectionState.CONNECTING,
        ),
        ConnectionState.FAILED to setOf(ConnectionState.IDLE, ConnectionState.DISCOVERING, ConnectionState.CONNECTING),
    )

    fun canTransition(from: ConnectionState, to: ConnectionState): Boolean =
        from == to || allowed[from]?.contains(to) == true

    fun transition(from: ConnectionState, to: ConnectionState): ConnectionState {
        if (!canTransition(from, to)) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "illegal connection transition $from -> $to")
        }
        return to
    }
}

enum class TransferState {
    IDLE,
    PREPARING,
    WAITING_FOR_ACCEPTANCE,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

enum class FileStatus {
    QUEUED,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object TransferMachine {
    private val allowed: Map<TransferState, Set<TransferState>> = mapOf(
        TransferState.IDLE to setOf(TransferState.PREPARING, TransferState.FAILED),
        TransferState.PREPARING to setOf(
            TransferState.WAITING_FOR_ACCEPTANCE,
            TransferState.CANCELLED,
            TransferState.FAILED,
        ),
        TransferState.WAITING_FOR_ACCEPTANCE to setOf(
            TransferState.TRANSFERRING,
            TransferState.CANCELLED,
            TransferState.FAILED,
        ),
        TransferState.TRANSFERRING to setOf(
            TransferState.VERIFYING,
            TransferState.COMPLETED,
            TransferState.CANCELLED,
            TransferState.FAILED,
        ),
        TransferState.VERIFYING to setOf(
            TransferState.TRANSFERRING,
            TransferState.COMPLETED,
            TransferState.FAILED,
            TransferState.CANCELLED,
        ),
        TransferState.COMPLETED to emptySet(),
        TransferState.CANCELLED to emptySet(),
        TransferState.FAILED to setOf(TransferState.PREPARING, TransferState.IDLE),
    )

    fun canTransition(from: TransferState, to: TransferState): Boolean =
        from == to || allowed[from]?.contains(to) == true

    fun transition(from: TransferState, to: TransferState): TransferState {
        if (!canTransition(from, to)) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "illegal transfer transition $from -> $to")
        }
        return to
    }
}
