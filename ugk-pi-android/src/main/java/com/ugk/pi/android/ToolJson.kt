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
 * `pi-agent-skill-runtime`) were already strict, so those tools did not change,
 * and both of them map null to `false` - the fail-closed direction, where a
 * value this accessor cannot read means "do not overwrite".
 *
 * `agent_task_list.activeOnly` used to be the third consumer and mapped null to
 * `false` too, but there the direction is the opposite: `false` means "no
 * filter", so a declared-but-unusable value silently widened the listing to
 * every row. Round 11 merged the three-state rule into the schedule module's
 * own `optionalElement` reader (`pi-schedule-skill-android`, used by
 * `agent_task_update`), and round 12 recorded the widening on the reasoning that
 * no such reader existed there yet. Both filter
 * arguments of `agent_task_list` now go through that reader and refuse an
 * unusable declaration by name, pinned by `AgentTaskListActiveOnlyArgumentTest`,
 * `AgentTaskListStatusArgumentTest` and `AgentTaskListFilterSchemaTest`. There is
 * no shared cross-module version of that reader: each module keeps its own copy
 * because promoting it would widen the published AAR API for two lines.
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
