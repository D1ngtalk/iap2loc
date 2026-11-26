package io.github.d1ngtalk.iap2loc.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Small helpers over kotlinx JsonElement: no generated serializers, so nothing for R8 to strip. */
internal val JsonCodec = Json { prettyPrint = false }
internal val JsonPretty = Json { prettyPrint = true }

/** Builds a JSON object from pairs; Kotlin nulls become JSON null, like System.Text.Json writes them. */
internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to json(it.second) })

internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Number -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map(::json))
    is Array<*> -> JsonArray(value.map(::json))
    else -> throw IllegalArgumentException("cannot encode ${value::class}")
}

/** Case-insensitive lookup, matching PropertyNameCaseInsensitive on the desktop. */
internal operator fun JsonObject.invoke(key: String): JsonElement? =
    this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

internal fun JsonObject.double(key: String): Double? = (this(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { !it.isNaN() }

internal fun JsonObject.int(key: String): Int? = (this(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

internal fun JsonObject.bool(key: String): Boolean? = (this(key) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.text(key: String): String? = (this(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.child(key: String): JsonObject? = this(key) as? JsonObject

internal fun JsonObject.list(key: String): JsonArray? = this(key) as? JsonArray

internal fun parseObject(body: String): JsonObject? = runCatching { JsonCodec.parseToJsonElement(body) as? JsonObject }.getOrNull()
