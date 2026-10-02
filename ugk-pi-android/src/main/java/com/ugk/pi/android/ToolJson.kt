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
 * `booleanOrNull`, which also accepts the case variants "True"/"FALSE". The two
 * copies that feed the overwrite-style write flags (`pi-file-skill`,
 * `pi-agent-skill-runtime`) were already strict, so those tools did not change.
 * The third copy, in `pi-schedule-skill`, read `booleanOrNull`, so
 * `agent_task_list.activeOnly` did accept a case variant and now reads it as
 * absent - which widens the listing back to every row instead of narrowing it.
 * That difference is pinned case by case in
 * `AgentTaskListActiveOnlyArgumentTest`; the three-state fix (a declared value
 * of the wrong type is refused by name) belongs to the shared optional-argument
 * reader, not to this file.
 *
 * Quoted scalars are read by their content on purpose: `{"overwrite": "true"}`
 * is accepted here, as it was by every replaced copy.
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
