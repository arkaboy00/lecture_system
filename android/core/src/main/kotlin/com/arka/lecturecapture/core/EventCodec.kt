package com.arka.lecturecapture.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class EventFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encodes a [Record] as one flat JSON object: `e`, `seq`, `ts`, `mono`, `boot`, then body fields.
 * Unknown keys are ignored on decode so newer files stay readable by older code.
 */
object EventCodec {
    internal val json = Json {
        classDiscriminator = "e"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val ENVELOPE = setOf("seq", "ts", "mono", "boot")

    fun encode(record: Record): String {
        val body = json.encodeToJsonElement(EventBody.serializer(), record.body).jsonObject
        val out = LinkedHashMap<String, JsonElement>()
        out["e"] = body.getValue("e")
        out["seq"] = JsonPrimitive(record.seq)
        out["ts"] = JsonPrimitive(record.stamp.ts)
        out["mono"] = JsonPrimitive(record.stamp.mono)
        record.stamp.boot?.let { out["boot"] = JsonPrimitive(it) }
        for ((key, value) in body) if (key != "e") out[key] = value
        return JsonObject(out).toString()
    }

    fun decode(line: String): Record {
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            throw EventFormatException("not a JSON object", e)
        }
        try {
            val seq = obj.getValue("seq").jsonPrimitive.long
            val stamp = Stamp(
                ts = obj.getValue("ts").jsonPrimitive.content,
                mono = obj.getValue("mono").jsonPrimitive.long,
                boot = obj["boot"]?.jsonPrimitive?.int,
            )
            val body = json.decodeFromJsonElement(
                EventBody.serializer(),
                JsonObject(obj.filterKeys { it !in ENVELOPE }),
            )
            return Record(seq, stamp, body)
        } catch (e: NoSuchElementException) {
            throw EventFormatException("missing envelope field", e)
        } catch (e: SerializationException) {
            throw EventFormatException("bad event body: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw EventFormatException("bad field value: ${e.message}", e)
        }
    }
}
