package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Nullable accessors for tool input JSON, shared by the SDK tool modules.
 * A missing key reads as null; a present scalar reads through kotlinx
 * `contentOrNull`, and a non-scalar value throws from `jsonPrimitive`
 * exactly as the per-module copies this file replaces did. Each tool turns
 * null into its own validation error, so parsing here never invents a
 * default.
 *
 * [boolean] is deliberately the strict stdlib parse, NOT kotlinx
 * `booleanOrNull`: kotlinx accepts the case variants "True"/"FALSE" that
 * the previous per-module copies rejected, and those feed overwrite-style
 * flags whose accidental acceptance would loosen write protection.
 */
fun JsonObject.string(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

fun JsonObject.boolean(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

fun JsonObject.long(key: String): Long? {
    return this[key]?.jsonPrimitive?.longOrNull
}
