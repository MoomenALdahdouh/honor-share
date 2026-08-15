package com.honor.share.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

sealed class Frame {
    data class Control(val json: ByteArray) : Frame() {
        override fun equals(other: Any?): Boolean =
            other is Control && json.contentEquals(other.json)

        override fun hashCode(): Int = json.contentHashCode()
    }

    data class Binary(
        val fileId: UUID,
        val offset: Long,
        val data: ByteArray,
    ) : Frame() {
        override fun equals(other: Any?): Boolean =
            other is Binary && fileId == other.fileId && offset == other.offset && data.contentEquals(other.data)

        override fun hashCode(): Int {
            var result = fileId.hashCode()
            result = 31 * result + offset.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}

object FrameCodec {
    fun encodeControl(json: ByteArray): ByteArray {
        val length = 1 + json.size
        require(length <= ProtocolConstants.MAX_FRAME_LENGTH) { "control frame too large" }
        val out = ByteArray(4 + length)
        putIntBe(out, 0, length)
        out[4] = ProtocolConstants.KIND_CONTROL.toByte()
        json.copyInto(out, 5)
        return out
    }

    fun encodeBinary(fileId: UUID, offset: Long, data: ByteArray): ByteArray {
        val payload = 1 + 16 + 8 + data.size
        require(payload <= ProtocolConstants.MAX_FRAME_LENGTH) { "binary frame too large" }
        val out = ByteArray(4 + payload)
        putIntBe(out, 0, payload)
        out[4] = ProtocolConstants.KIND_BINARY.toByte()
        uuidToBytes(fileId).copyInto(out, 5)
        putLongBe(out, 21, offset)
        data.copyInto(out, 29)
        return out
    }

    fun write(output: OutputStream, frame: ByteArray) {
        output.write(frame)
        output.flush()
    }

    fun read(input: InputStream): Frame {
        val header = readFully(input, 4)
        val length = getIntBe(header, 0)
        if (length < 1 || length > ProtocolConstants.MAX_FRAME_LENGTH) {
            throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "invalid frame length")
        }
        val body = readFully(input, length)
        return when (body[0].toInt() and 0xFF) {
            ProtocolConstants.KIND_CONTROL -> Frame.Control(body.copyOfRange(1, body.size))
            ProtocolConstants.KIND_BINARY -> {
                if (body.size < 1 + 16 + 8) {
                    throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "short binary frame")
                }
                val fileId = bytesToUuid(body, 1)
                val offset = getLongBe(body, 17)
                val data = body.copyOfRange(25, body.size)
                Frame.Binary(fileId, offset, data)
            }
            else -> throw ProtocolException(ErrorCode.PROTOCOL_VIOLATION, "unknown frame kind")
        }
    }

    fun uuidToBytes(id: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(id.mostSignificantBits)
        buffer.putLong(id.leastSignificantBits)
        return buffer.array()
    }

    fun bytesToUuid(bytes: ByteArray, offset: Int = 0): UUID {
        val buffer = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.BIG_ENDIAN)
        return UUID(buffer.long, buffer.long)
    }

    private fun putIntBe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putLongBe(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = (value ushr ((7 - i) * 8)).toByte()
        }
    }

    private fun getIntBe(source: ByteArray, offset: Int): Int {
        return ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
    }

    private fun getLongBe(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw EOFException("unexpected end of stream")
            read += n
        }
        return buffer
    }
}

class ProtocolException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
