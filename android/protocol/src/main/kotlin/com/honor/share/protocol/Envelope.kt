package com.honor.share.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class Envelope(
    val v: Int = ProtocolConstants.VERSION,
    val type: String,
    val msgId: String,
    val ts: Long,
    val payload: JsonObject = buildJsonObject { },
) {
    fun messageType(): MessageType? = MessageType.fromWire(type)
}
