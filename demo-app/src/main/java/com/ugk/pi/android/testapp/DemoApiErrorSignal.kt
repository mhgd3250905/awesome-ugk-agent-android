package com.ugk.pi.android.testapp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether a JSON body reports an error, independent of how the gateway spelled it.
 *
 * Two readers in this module decided that by asking whether `error` is an object,
 * which is the shape the SDK's own providers no longer trust: a proxy that answers
 * `{"error":"Overloaded"}` sailed through both. The rule here matches the one the
 * providers use, and for the same reason the empty serialized fields of a struct
 * (`""`, `false`, `0`, `[]`, `{}`, `null`) mean "nothing was reported" - in the
 * workflow plan reader that difference is the whole bug: the model returns a
 * complete plan with `"error": null`, and an "is the key present" check refused the
 * plan with a message about missing evidence.
 *
 * Kept as a separate object rather than reusing the core helper because `internal`
 * members of the published AAR are not visible here; the two sides are pinned by
 * the same shape table in `DemoApiErrorSignalTest` and
 * `StreamedResponseTransportContractTest`.
 */
internal object DemoApiErrorSignal {

    /** A response document: the protocol's own `type == "error"` marker counts even with no `error` field. */
    fun reportedBy(root: JsonObject): Boolean {
        val marker = (root["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull == "error"
        return marker || reportsAnything(root["error"])
    }

    fun reportsAnything(error: JsonElement?): Boolean = when (error) {
        null, JsonNull -> false
        is JsonObject -> error.size > 0
        is JsonArray -> error.isNotEmpty()
        is JsonPrimitive -> when {
            error.isString -> error.content.isNotBlank()
            else -> when (val flag = error.content.toBooleanStrictOrNull()) {
                null -> error.content.toDoubleOrNull()?.let { it != 0.0 } ?: error.content.isNotBlank()
                else -> flag
            }
        }
    }
}
