package com.ugk.pi.system.skill

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The shape a gateway emits for an optional argument nobody filled in.
 *
 * OpenAI-compatible Java/Pojo endpoints serialize an unset optional as JSON `null`
 * rather than leaving the key out, and `JsonNull` is **both** a `JsonPrimitive` and
 * a value - so `this[key]`, `!= null` and `key in obj` all read "the model asked for
 * this" where the gateway meant "nothing was decided". `?.jsonArray` and
 * `?.jsonObject` are worse than a wrong decision here: they throw, and the runtime
 * turns that throw into a tool result naming a serialization class instead of the
 * argument the caller has to fix.
 *
 * A value that *is* declared with an unusable shape is still refused - the rule is
 * about absence, not permission to accept anything.
 *
 * This module repeats the reader that round 11 landed in the attention, agent-skill
 * runtime, schedule and demo modules instead of publishing one from
 * `ugk-pi-android`: two lines do not justify widening the published AAR's API, and
 * the cost is stated in `docs/terminal-runtime-validation.md` §37.
 */
internal fun JsonObject.optionalElement(key: String): JsonElement? =
    this[key]?.takeUnless { it is JsonNull }

/** What an argument says about one list-of-strings field. */
internal sealed interface DeclaredStringList {
    /** Nothing was filled in: the key is absent, or the endpoint emitted `null`. */
    object Undeclared : DeclaredStringList

    /** A value was declared that is not a list - the caller must refuse it. */
    object Unusable : DeclaredStringList

    /** The declared entries. Non-string items are dropped, as this reader always has. */
    class Values(val values: List<String>) : DeclaredStringList
}

internal fun JsonObject.declaredStringList(key: String): DeclaredStringList {
    val declared = optionalElement(key) ?: return DeclaredStringList.Undeclared
    val items = declared as? JsonArray ?: return DeclaredStringList.Unusable
    return DeclaredStringList.Values(items.mapNotNull { it.contentOrNull })
}
