package com.honor.share.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

object ProtocolJson {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
        prettyPrint = false
    }

    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    fun decode(text: String): Envelope = json.decodeFromString(Envelope.serializer(), text)

    fun parse(bytes: ByteArray): Envelope = decode(bytes.toString(Charsets.UTF_8))

    inline fun <reified T> payload(envelope: Envelope): T =
        json.decodeFromJsonElement(envelope.payload)

    inline fun <reified T> payloadObject(value: T): JsonObject =
        json.encodeToJsonElement(value).jsonObject

    fun message(
        type: MessageType,
        payload: JsonObject,
        msgId: String = UUID.randomUUID().toString(),
        ts: Long = System.currentTimeMillis(),
    ): Envelope = Envelope(
        v = ProtocolConstants.VERSION,
        type = type.name,
        msgId = msgId,
        ts = ts,
        payload = payload,
    )
}
