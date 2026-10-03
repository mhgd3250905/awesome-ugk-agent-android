package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The text of an object field an endpoint may fill with anything.
 *
 * `element?.jsonPrimitive` throws `IllegalStateException` for a non-primitive, so
 * reading an error description that way replaces the API's own reason - the one
 * string a caller needs to be able to read - with a serialization-library message
 * about `JsonObject is not a JsonPrimitive`. A value that is not a string is
 * reported as absent, which lets the caller fall back to what it already holds
 * (the raw payload, or the body snippet). A blank string counts as absent too:
 * `{"error":{"message":"","type":"overloaded_error"}}` must report the type, not
 * "Anthropic API error: " with nothing after it.
 */
internal fun JsonObject.textOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * The reason a response document reports for its `error`, or null when it reports none.
 *
 * Reading only the object shape (`{"error":{...}}`) made every other spelling a
 * successful blank answer, and for the OpenAI document path it replaced the
 * endpoint's reason with a guess about a missing response field: proxies and
 * legacy gateways answer `{"error":"Overloaded"}`, and an endpoint may carry the
 * protocol's own `{"type":"error"}` marker with nothing readable inside it.
 *
 * Two different presence rules are on purpose, and they are the whole design:
 *
 * - a standalone document is read here, where an `error` field that reports
 *   nothing (`""`, `false`, `0`, `[]`, `{}`, `null`) means "no error", because a
 *   gateway that serializes a whole struct fills every field - failing a body that
 *   also contains a complete answer is worse than the silence this rule exists to
 *   end.
 * - a mid-stream event is read by [streamErrorReasonOrNull], where any present
 *   non-`null` `error` fails the stream: there is no answer in that envelope to
 *   preserve, so "empty" cannot mean "fine".
 *
 * Both caps exist because the text comes from the endpoint and lands in an
 * exception message the host logs and the transcript stores. The reason the
 * endpoint chose to tell us gets the larger bound; a payload we could not read
 * gets the smaller one.
 */
internal fun apiErrorReasonOrNull(root: JsonObject, rawFallback: String?): String? {
    val declared = root["error"]
    val markerReportsError = (root["type"] as? JsonPrimitive)?.contentOrNull == "error"
    if (!markerReportsError && (declared == null || !declared.reportsAnything())) return null
    return boundedApiErrorText(apiErrorReasonText(declared), rawFallback)
}

/**
 * The reason a streamed error event reports, or null when it carries no `error`.
 *
 * Presence is the test here: a chunk that arrives with an `error` key is a failure
 * even when the field is empty, because the alternative is to keep streaming and
 * finish a truncated answer as a normal completion. `"error":null` stays absence -
 * that is how a POJO-serialized gateway spells "no error" inside every chunk.
 */
internal fun streamErrorReasonOrNull(error: JsonElement?, rawPayload: String): String? {
    if (error == null || error is JsonNull) return null
    return boundedApiErrorText(apiErrorReasonText(error), rawPayload)
}

private fun apiErrorReasonText(error: JsonElement?): String? = when (error) {
    // Folded over the candidate list rather than chained: the first field that
    // carries text wins, and adding a candidate cannot silently reorder them.
    is JsonObject -> listOf("message", "type").firstNotNullOfOrNull { key -> error.textOrNull(key) }
    is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }
    else -> null
}

/**
 * The endpoint's own reason keeps the larger bound; a payload we could not read
 * keeps the smaller one. The distinction matters because the first is what the
 * caller needs to see and the second is only evidence of the shape.
 */
private fun boundedApiErrorText(reason: String?, rawFallback: String?): String? {
    val text = reason?.take(MAX_API_ERROR_REASON_CHARS)
        ?: rawFallback?.trim()?.take(MAX_API_ERROR_ECHO_CHARS)
    return text?.takeIf { it.isNotEmpty() }
}

/**
 * Whether a serialized value says anything at all.
 *
 * `JsonNull` is a value rather than an absent key, and a gateway that marshals a
 * whole struct leaves `""`, `false`, `0`, `[]` and `{}` behind for every field it
 * did not set. None of those report a problem; a non-zero number, a `true` flag,
 * a non-blank string or a collection with something in it does.
 */
private fun JsonElement.reportsAnything(): Boolean = when (this) {
    JsonNull -> false
    is JsonObject -> size > 0
    is JsonArray -> isNotEmpty()
    is JsonPrimitive -> when {
        isString -> content.isNotBlank()
        else -> content.toBooleanStrictOrNull()?.let { it }
            ?: content.toDoubleOrNull()?.let { it != 0.0 }
            ?: content.isNotBlank()
    }
}

internal const val MAX_API_ERROR_REASON_CHARS = 1_000

internal const val MAX_API_ERROR_ECHO_CHARS = 200

/**
 * Splits any emission that carries several lines into one emission per line.
 *
 * [HttpTransport.postStream] is documented to emit one response line per
 * emission, but nothing enforces it: the interface's own default implementation
 * inherited a body-wide single emission, and a host transport that batches what
 * the socket delivered naturally emits several events at once. A line-oriented
 * parser then fails to parse the payload, and a parser that ignores unparseable
 * payloads reports a shortened answer as a successful completion — the runtime
 * stores that truncated text as the model's final response.
 *
 * Emissions that contain no line terminator are passed through unchanged, which
 * keeps this operator idempotent for a transport that already frames by line.
 * Splitting an emission that ends in the middle of a line cannot be repaired
 * here without guessing: reassembly would have to invent the separator that the
 * SSE framing rules reserve for a real line break. Such a transport violates
 * the documented contract, and the providers report its payload as malformed
 * instead of completing with half of it.
 *
 * `CRLF`, `LF` and a lone `CR` all terminate a line, mirroring
 * `BufferedReader.readLine()`.
 */
internal fun Flow<String>.asSseLines(): Flow<String> = flow {
    collect { emission ->
        // A non-streaming endpoint may answer one whole JSON document (possibly
        // pretty-printed over several lines). Providers recognize that shape as a
        // single emission, so splitting it would take it apart before the parser
        // can see it.
        if (isStandaloneJsonDocument(emission)) {
            emit(emission)
            return@collect
        }
        if (emission.indexOf('\n') < 0 && emission.indexOf('\r') < 0) {
            emit(emission)
            return@collect
        }
        // `CRLF` first: when two delimiters match at the same index, String.split
        // takes the one listed first, so "a\r\nb" yields ["a","b"] instead of
        // ["a","","b"] with a stray event boundary.
        emission.split("\r\n", "\n", "\r").forEach { line -> emit(line) }
    }
}

/**
 * Failure for an SSE event whose `data:` payload never became parsable, either
 * because the stream ended inside the event or because a blank line closed it.
 *
 * Echoing the payload is bounded so a broken or hostile endpoint cannot push an
 * unbounded string into the host's logs and transcripts.
 */
internal fun malformedSseEvent(payload: String): IllegalStateException =
    IllegalStateException(
        "SSE stream ended an event with an unparsable data payload: " +
            payload.take(MAX_MALFORMED_SSE_ECHO_CHARS)
    )

internal const val MAX_MALFORMED_SSE_ECHO_CHARS = 200

/**
 * Upper bound for the payload fragments buffered for one unfinished SSE event.
 *
 * Buffering exists to join the `data:` lines of a single event, not to hold a
 * broken endpoint's output: without a bound, a stream of unparsable lines is
 * re-joined on every line, which is quadratic in the number of lines and can
 * push gigabytes of copies through one model turn.
 */
internal const val MAX_BUFFERED_SSE_EVENT_CHARS = 1_000_000

/**
 * True when [body] is a single JSON document rather than an event stream.
 *
 * Providers keep a tolerance branch for endpoints that answer a streaming
 * request with one whole JSON document. Splitting such a document into lines
 * would take it apart before the parser ever sees it, so the fallback transport
 * hands it over intact.
 */
internal fun isStandaloneJsonDocument(body: String): Boolean {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
    return runCatching { Json.parseToJsonElement(trimmed) is JsonObject }.getOrDefault(false)
}
