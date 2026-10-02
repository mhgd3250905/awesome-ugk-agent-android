package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Nullable accessors for tool input JSON, shared by the SDK tool modules.
 * A missing key or a non-scalar value reads as null; each tool turns that
 * into its own validation error, so parsing here never throws and never
 * invents a default.
 */
fun JsonObject.string(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

fun JsonObject.boolean(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.booleanOrNull
}

fun JsonObject.long(key: String): Long? {
    return this[key]?.jsonPrimitive?.longOrNull
}
