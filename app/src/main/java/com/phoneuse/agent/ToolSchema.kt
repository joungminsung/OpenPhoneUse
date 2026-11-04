package com.phoneuse.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Small builders for tool parameter schemas (JSON Schema), shared by the tool modules. A tool's schema is part
 * of the model prompt, so keep the key order these produce: type, properties, required.
 */

/** A tool's parameters: always lists required, even when empty. */
internal fun schema(required: List<String> = emptyList(), props: Map<String, JsonObject> = emptyMap()) =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

internal fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

internal fun enumProp(description: String, values: List<String>) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", JsonArray(values.map { JsonPrimitive(it) }))
}

/** A nested object; unlike [schema] it leaves out required when there is none. */
internal fun obj(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

internal fun arr(items: JsonObject, description: String? = null) = buildJsonObject {
    put("type", "array")
    description?.let { put("description", it) }
    put("items", items)
}

internal fun str(description: String? = null) = buildJsonObject {
    put("type", "string")
    description?.let { put("description", it) }
}

internal fun num() = buildJsonObject { put("type", "number") }
