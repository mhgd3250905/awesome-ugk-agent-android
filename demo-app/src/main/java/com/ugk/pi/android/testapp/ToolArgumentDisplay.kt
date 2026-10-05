package com.ugk.pi.android.testapp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a Tool argument says to a display or trace surface.
 *
 * The three demo surfaces that render or log a Tool call (the run-state label, the
 * floating-window log line, the trace record) each read arguments with
 * `element.jsonPrimitive`, which measured raises IllegalArgumentException for an
 * object or array (build/review-evidence/r14-shape-oracle.txt). Two of them run
 * inside `DemoAgentRunCoordinator.dispatch` - the state fold is called outside any
 * `runCatching`, and the event listener is called unguarded - so one argument the
 * model sent in the wrong shape ended the whole conversation turn with
 * "Element class kotlinx.serialization.json.JsonObject is not a JsonPrimitive".
 *
 * A display surface has no authority to abort a run, and it must not invent a value
 * either: an argument that WAS sent but cannot be read is reported as unreadable,
 * which is a different fact from "nobody sent it".
 */
internal sealed interface DisplayedArgument {
    /** No usable value was sent: the key is absent, or the endpoint emitted JSON null. */
    object NotSent : DisplayedArgument

    /** A value was sent that this client cannot render: an object, an array, or a primitive it cannot read. */
    object Unreadable : DisplayedArgument

    /** The text to show. May be empty when the caller sent an empty string. */
    class Sent(val text: String) : DisplayedArgument
}

internal fun JsonObject.displayedArgument(key: String): DisplayedArgument {
    val element: JsonElement? = this[key]
    return when {
        element == null || element is JsonNull -> DisplayedArgument.NotSent
        element is JsonPrimitive -> DisplayedArgument.Sent(element.contentOrNull.orEmpty())
        else -> DisplayedArgument.Unreadable
    }
}

/** The Chinese run-state wording for an argument the caller sent in an unreadable shape. */
internal const val UNREADABLE_ARGUMENT_LABEL = "无法解析"

/** The machine-facing trace and log word for the same fact, kept distinct from "missing". */
internal const val UNREADABLE_ARGUMENT_STATE = "unparseable"

/**
 * A compact, bounded fragment for a log line: user-entered text is never quoted in
 * full, and an unreadable value must not look like an absent one.
 */
internal fun JsonObject.displayedText(key: String, maxLength: Int = 32): String =
    when (val argument = displayedArgument(key)) {
        DisplayedArgument.NotSent -> ""
        DisplayedArgument.Unreadable -> UNREADABLE_ARGUMENT_STATE
        is DisplayedArgument.Sent -> argument.text.take(maxLength)
    }

/** Present / missing / unparseable, for the diagnostic suffix the floating window logs. */
internal fun JsonObject.displayedPresence(key: String): String =
    when (val argument = displayedArgument(key)) {
        DisplayedArgument.NotSent -> "missing"
        DisplayedArgument.Unreadable -> UNREADABLE_ARGUMENT_STATE
        // A blank string was sent but carries nothing, which is what "missing" has
        // always meant on this surface; only an unreadable shape is reported differently.
        is DisplayedArgument.Sent -> if (argument.text.isBlank()) "missing" else "present"
    }
