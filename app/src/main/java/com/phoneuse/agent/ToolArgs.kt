package com.phoneuse.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Reading a tool call's arguments, shared by ToolExecutor and the tool dispatchers. A missing key or JSON null
 * reads as null; a value of the wrong type throws [ArgException], which each dispatcher reports as an
 * "invalid arguments" result.
 */

/** A tool argument of the wrong type or missing. An [IllegalArgumentException], as the dispatchers expect. */
internal class ArgException(message: String) : IllegalArgumentException(message)

internal fun JsonObject.prim(key: String): JsonPrimitive? = when (val v = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> v
    else -> throw ArgException("$key must be a scalar")
}

internal fun JsonObject.str(key: String): String? = prim(key)?.content

/** An integer; a whole number written as 3.0 counts too. */
internal fun JsonObject.int(key: String): Int? = prim(key)?.let {
    it.intOrNull ?: it.doubleOrNull?.takeIf { d -> d % 1.0 == 0.0 }?.toInt() ?: throw ArgException("$key must be an integer")
}

internal fun JsonObject.bool(key: String): Boolean? =
    prim(key)?.let { it.booleanOrNull ?: throw ArgException("$key must be a boolean") }

internal fun JsonObject.num(key: String): Double? =
    prim(key)?.let { it.doubleOrNull ?: throw ArgException("$key must be a number") }

internal fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ArgException("$key must be strings") }
    else -> throw ArgException("$key must be an array")
}

internal fun JsonObject.requireStr(key: String): String = str(key) ?: throw ArgException("$key required")

internal fun JsonObject.requireInt(key: String): Int = int(key) ?: throw ArgException("$key required")

internal fun JsonObject.requireNum(key: String): Double = num(key) ?: throw ArgException("$key required")
