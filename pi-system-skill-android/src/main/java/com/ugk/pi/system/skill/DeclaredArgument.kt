package com.ugk.pi.system.skill

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * What an argument says about one optional scalar field.
 *
 * The same three states as [DeclaredStringList], with one deliberate difference in what
 * counts as a value: `declaredStringList` accepts only JSON strings as entries, while
 * these readers take any primitive's content - `{"maxChars": "500"}` and
 * `{"text_exact": 20}` are read as the caller's number or text rather than refused.
 * Quoted scalars were already accepted by every helper this replaced, and a gateway that
 * stringifies numbers is the normal case, not an attack.
 *
 * Measured on this build by `ArgumentFamilyCoverageTest`: `intOrNull` is null for `20.5`,
 * `true` and `"2e3"`, so a bound the model did state used to be read as "no bound
 * requested" and the documented default took over - for `clipboard_read_text` that
 * default is 8000 characters of clipboard handed to the next model request. And the
 * throwing `element.jsonPrimitive` raised IllegalArgumentException for an object or
 * array, which `AgentRuntime.executeTool` turned into a tool result quoting
 * `kotlinx.serialization.json.JsonObject`, and which the demo-side display readers
 * turned into an aborted run.
 *
 * No arm of these readers throws.
 */
internal sealed interface DeclaredArgument<out T> {
    /** Nobody filled it in: the key is absent, or the endpoint emitted JSON null. */
    object Undeclared : DeclaredArgument<Nothing>

    /** A value was declared that cannot be read as this type - the caller must refuse it. */
    object Unusable : DeclaredArgument<Nothing>

    /** The value as declared. A string may be blank; the caller decides what blank means. */
    class Of<T>(val value: T) : DeclaredArgument<T>
}

internal fun JsonObject.declaredString(key: String): DeclaredArgument<String> {
    val declared = optionalElement(key) ?: return DeclaredArgument.Undeclared
    val primitive = declared as? JsonPrimitive ?: return DeclaredArgument.Unusable
    val content = primitive.contentOrNull ?: return DeclaredArgument.Unusable
    return DeclaredArgument.Of(content)
}

internal fun JsonObject.declaredInt(key: String): DeclaredArgument<Int> {
    val declared = optionalElement(key) ?: return DeclaredArgument.Undeclared
    val primitive = declared as? JsonPrimitive ?: return DeclaredArgument.Unusable
    primitive.intOrNull?.let { return DeclaredArgument.Of(it) }
    // An endpoint that rounds an integer through a double sends 2e3 or 2000.0; that is
    // the same request. 20.5 of anything countable is not a value a caller can mean.
    val asDouble = primitive.doubleOrNull ?: return DeclaredArgument.Unusable
    // Measured, not recalled: Kotlin's Double.roundToInt() throws IllegalArgumentException
    // for NaN and for +/-Infinity, and `doubleOrNull` does accept the quoted forms "NaN"
    // and "Infinity". Without this check the "never throws" rule this reader exists for
    // was broken by its own integral-double branch.
    if (!asDouble.isFinite()) return DeclaredArgument.Unusable
    if (asDouble != asDouble.roundToInt().toDouble()) return DeclaredArgument.Unusable
    return DeclaredArgument.Of(asDouble.roundToInt())
}

internal fun JsonObject.declaredDouble(key: String): DeclaredArgument<Double> {
    val declared = optionalElement(key) ?: return DeclaredArgument.Undeclared
    val primitive = declared as? JsonPrimitive ?: return DeclaredArgument.Unusable
    return primitive.doubleOrNull?.let { DeclaredArgument.Of(it) } ?: DeclaredArgument.Unusable
}

internal fun JsonObject.declaredBoolean(key: String): DeclaredArgument<Boolean> {
    val declared = optionalElement(key) ?: return DeclaredArgument.Undeclared
    val primitive = declared as? JsonPrimitive ?: return DeclaredArgument.Unusable
    return primitive.booleanOrNull?.let { DeclaredArgument.Of(it) } ?: DeclaredArgument.Unusable
}

/**
 * The same three states for an argument where whitespace-only is the same nothing a
 * missing key is. It is NOT for a field where an empty value is a real request -
 * `set_text` with `""` clears a field on purpose, and [declaredString] keeps that.
 */
internal fun JsonObject.declaredNonBlank(key: String): DeclaredArgument<String> {
    val declared = declaredString(key)
    return if (declared is DeclaredArgument.Of && declared.value.isBlank()) {
        DeclaredArgument.Undeclared
    } else {
        declared
    }
}

/**
 * The same three states for a value whose surrounding whitespace is not part of it.
 *
 * `launch_android_app` matches an anchored package-name pattern and `find_android_app`
 * compares labels directly, so an untrimmed `" com.example.app "` is refused, and a
 * `" gmail "` query scores zero and the Tool answers ok with no candidates. The round
 * that added this reader dropped that trim; it lives here now, so the two callers share
 * one rule and the rule itself is host-testable even though those Tools need a Context.
 */
internal fun JsonObject.declaredTrimmed(key: String): DeclaredArgument<String> =
    when (val declared = declaredNonBlank(key)) {
        is DeclaredArgument.Of -> DeclaredArgument.Of(declared.value.trim())
        else -> declared
    }

/**
 * The first argument slot holding a structured value, or null.
 *
 * Four Tools run this before checking whether anything was supplied at all:
 * `screen_perform_action`, `screen_gesture`, `clipboard_write_text`, `find_android_app`.
 * Without it, a call like `{"label": {"a":1}}` to `clipboard_write_text` answered
 * "text is required" - a true statement about a different argument, and one that sends
 * the caller to fix the wrong thing while the unreadable one stays. An object or array
 * is unreadable for every reader in this family, so it needs no type knowledge to spot.
 *
 * Not every multi-argument Tool uses it: `screen_read_ui_tree` and
 * `screen_find_ui_element` refuse each slot inside their own reading loop,
 * `screen_visual_gesture` scans its three scalar slots before the `target` object, and
 * `terminal_bash_execute` / `launch_android_app_intent` check the one argument that can
 * diverge from a confirmation binding. Where an absent argument already produces a
 * refusal, adding this pre-pass would change nothing except the order of two error
 * messages.
 *
 * The `Unusable` arms of the string readers below are unreachable while this pre-pass
 * covers the same slots; the numeric and boolean readers keep live `Unusable` arms,
 * because for those a primitive that does not parse (`20.5`, `true`) is also unusable
 * and this scan cannot see it.
 */
internal fun JsonObject.firstStructuredArgument(keys: List<String>): String? =
    keys.firstOrNull { element ->
        val value = this[element]
        value != null && value !is JsonNull && value !is JsonPrimitive
    }

/**
 * A refusal names the argument and stops there. The declared value may be a whole
 * document, and echoing it in full put megabytes of gateway text into tool results,
 * logs and the transcript (round 13, F7). Structured values are described by shape
 * rather than by contents.
 */
internal const val MAX_REFUSAL_ECHO_CHARS = 120

internal fun JsonElement.describeForRefusal(): String = when (this) {
    is JsonNull -> "null"
    is JsonObject -> "an object with " + size + (if (size == 1) " key" else " keys")
    is JsonArray -> "an array of " + size + " items"
    is JsonPrimitive -> content.take(MAX_REFUSAL_ECHO_CHARS)
}

/** The sentence every refusal in this family ends with, so the wording cannot drift. */
internal fun unusableArgumentMessage(key: String, declared: JsonElement?): String =
    "The argument '" + key + "' was sent as " + (declared?.describeForRefusal() ?: "an unreadable value") +
        "; it must be a value this Tool can read. Re-send '" + key + "' alone, in the type its schema declares."
