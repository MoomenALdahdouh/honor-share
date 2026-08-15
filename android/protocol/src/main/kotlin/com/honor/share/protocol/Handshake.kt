package com.honor.share.protocol

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ProtocolSession(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val writeLock = Any()

    fun send(envelope: Envelope) {
        val bytes = ProtocolJson.encode(envelope).toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            FrameCodec.write(output, FrameCodec.encodeControl(bytes))
        }
    }

    fun sendBinary(fileId: UUID, offset: Long, data: ByteArray) {
        synchronized(writeLock) {
            FrameCodec.write(output, FrameCodec.encodeBinary(fileId, offset, data))
        }
    }

    fun receive(): Frame = FrameCodec.read(input)

    fun receiveControl(): Envelope {
        return when (val frame = receive()) {
            is Frame.Control -> ProtocolJson.parse(frame.json)
            is Frame.Binary -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected control frame")
        }
    }

    fun typed(type: MessageType, payload: Any, msgId: String = java.util.UUID.randomUUID().toString(), ts: Long = System.currentTimeMillis()): Envelope {
        val obj = when (payload) {
            is HelloPayload -> ProtocolJson.payloadObject(payload)
            is AuthChallengePayload -> ProtocolJson.payloadObject(payload)
            is AuthResponsePayload -> ProtocolJson.payloadObject(payload)
            is TransferRequestPayload -> ProtocolJson.payloadObject(payload)
            is TransferIdPayload -> ProtocolJson.payloadObject(payload)
            is TransferAcceptedPayload -> ProtocolJson.payloadObject(payload)
            is TransferRejectedPayload -> ProtocolJson.payloadObject(payload)
            is FileStartPayload -> ProtocolJson.payloadObject(payload)
            is FileProgressPayload -> ProtocolJson.payloadObject(payload)
            is FileCompletePayload -> ProtocolJson.payloadObject(payload)
            is FileResumePayload -> ProtocolJson.payloadObject(payload)
            is TransferCancelledPayload -> ProtocolJson.payloadObject(payload)
            is ErrorPayload -> ProtocolJson.payloadObject(payload)
            else -> error("unsupported payload")
        }
        return ProtocolJson.message(type, obj, msgId, ts)
    }
}

data class LocalProfile(
    val deviceId: String,
    val name: String,
    val os: String,
    val identity: GeneratedIdentity,
)

data class RemotePeer(
    val deviceId: String,
    val name: String,
    val os: String,
    val fingerprint: String,
    val newlyPaired: Boolean,
)

class Handshake(
    private val session: ProtocolSession,
    private val local: LocalProfile,
    private val capturedFingerprint: String,
    private val knownPin: (String) -> String?,
    private val confirmSas: (sas: String, peerName: String) -> Boolean,
) {
    fun runAsClient(): RemotePeer {
        val nonce = Checksums.toHex(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        session.send(
            session.typed(
                MessageType.HELLO,
                HelloPayload(
                    deviceId = local.deviceId,
                    name = local.name,
                    os = local.os,
                    certFingerprint = local.identity.fingerprintSha256,
                    authNonce = nonce,
                ),
            ),
        )
        val ack = session.receiveControl()
        return finish(ack, nonce, initiator = true)
    }

    fun runAsServer(): RemotePeer {
        val hello = session.receiveControl()
        if (hello.messageType() == MessageType.ERROR) {
            throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "peer sent error")
        }
        if (hello.messageType() != MessageType.HELLO) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected HELLO")
        }
        val payload = ProtocolJson.payload<HelloPayload>(hello)
        if (payload.protocolVersion != ProtocolConstants.VERSION) {
            session.send(
                session.typed(
                    MessageType.ERROR,
                    ErrorPayload(ErrorCode.UNSUPPORTED_VERSION.name),
                ),
            )
            throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "peer version ${payload.protocolVersion}")
        }
        session.send(
            session.typed(
                MessageType.HELLO_ACK,
                HelloPayload(
                    deviceId = local.deviceId,
                    name = local.name,
                    os = local.os,
                    certFingerprint = local.identity.fingerprintSha256,
                    authNonce = payload.authNonce,
                ),
            ),
        )
        return authenticate(payload, payload.authNonce, initiator = false)
    }

    private fun finish(ack: Envelope, nonce: String, initiator: Boolean): RemotePeer {
        if (ack.messageType() == MessageType.ERROR) {
            val error = ProtocolJson.payload<ErrorPayload>(ack)
            throw ProtocolException(ErrorCode.fromWire(error.code), error.code)
        }
        if (ack.messageType() != MessageType.HELLO_ACK) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected HELLO_ACK")
        }
        val payload = ProtocolJson.payload<HelloPayload>(ack)
        if (payload.protocolVersion != ProtocolConstants.VERSION) {
            session.send(session.typed(MessageType.ERROR, ErrorPayload(ErrorCode.UNSUPPORTED_VERSION.name)))
            throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "peer version ${payload.protocolVersion}")
        }
        return authenticate(payload, nonce, initiator)
    }

    private fun authenticate(peerHello: HelloPayload, nonce: String, initiator: Boolean): RemotePeer {
        if (!Checksums.equalsHex(peerHello.certFingerprint, capturedFingerprint)) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "hello fingerprint does not match TLS certificate")
        }
        val pin = knownPin(peerHello.deviceId)
        val weTrust = pin != null && Checksums.equalsHex(pin, capturedFingerprint)
        if (weTrust) {
            session.send(session.typed(MessageType.AUTH_RESPONSE, AuthResponsePayload(confirmed = true, trusted = true)))
            val reply = session.receiveControl()
            when (reply.messageType()) {
                MessageType.AUTH_RESPONSE -> {
                    val payload = ProtocolJson.payload<AuthResponsePayload>(reply)
                    if (!payload.confirmed) {
                        throw ProtocolException(ErrorCode.AUTH_FAILED, "peer rejected pairing")
                    }
                    return RemotePeer(peerHello.deviceId, peerHello.name, peerHello.os, capturedFingerprint, newlyPaired = false)
                }
                MessageType.AUTH_CHALLENGE -> {
                    return completeSas(peerHello, nonce, initiator = initiator, skipChallengeExchange = true)
                }
                else -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected AUTH_RESPONSE got ${reply.type}")
            }
        }
        return completeSas(peerHello, nonce, initiator = initiator, skipChallengeExchange = false)
    }

    private fun completeSas(
        peerHello: HelloPayload,
        nonce: String,
        initiator: Boolean,
        skipChallengeExchange: Boolean,
    ): RemotePeer {
        val sas = Sas.compute(local.identity.fingerprintSha256, capturedFingerprint, nonce)
        if (!skipChallengeExchange) {
            if (initiator) {
                session.send(session.typed(MessageType.AUTH_CHALLENGE, AuthChallengePayload()))
            } else {
                val challenge = session.receiveControl()
                when (challenge.messageType()) {
                    MessageType.AUTH_CHALLENGE -> Unit
                    MessageType.AUTH_RESPONSE -> {
                        session.send(session.typed(MessageType.AUTH_CHALLENGE, AuthChallengePayload()))
                    }
                    else -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected AUTH_CHALLENGE")
                }
            }
        }
        if (!confirmSas(sas, peerHello.name)) {
            session.send(session.typed(MessageType.AUTH_RESPONSE, AuthResponsePayload(confirmed = false)))
            throw ProtocolException(ErrorCode.AUTH_FAILED, "user rejected pairing")
        }
        val sendAuthResponse = !skipChallengeExchange || initiator
        if (sendAuthResponse) {
            session.send(session.typed(MessageType.AUTH_RESPONSE, AuthResponsePayload(confirmed = true, trusted = false)))
        }
        waitPeerAuth(expectTrusted = false)
        return RemotePeer(peerHello.deviceId, peerHello.name, peerHello.os, capturedFingerprint, newlyPaired = true)
    }

    private fun waitPeerAuth(expectTrusted: Boolean) {
        val response = session.receiveControl()
        if (response.messageType() != MessageType.AUTH_RESPONSE) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "expected AUTH_RESPONSE")
        }
        val payload = ProtocolJson.payload<AuthResponsePayload>(response)
        if (!payload.confirmed) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "peer rejected pairing")
        }
        if (expectTrusted && !payload.trusted) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "peer is not trusted")
        }
    }
}
