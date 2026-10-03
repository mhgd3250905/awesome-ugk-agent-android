package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
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
 * The reason an endpoint gave for an `error`, or null when it gave none.
 *
 * One rule for every place a response says "error", because the shapes differ by
 * gateway and a per-copy check drifts: an object (`Anthropic`/`OpenAI` native), a
 * plain string (proxies and legacy gateways answer `{"error":"Overloaded"}`), a
 * number, an array, or `null` meaning "no error" (how a POJO-serialized gateway
 * fills the field). Recognizing only the object shape made every other shape a
 * successful blank answer, and for the OpenAI document path it replaced the
 * endpoint's reason with a guess about a missing response field.
 *
 * [error] is read as the raw `error` element so `JsonNull` can be told apart from
 * an absent key: both mean "nothing was reported" here, which is the same
 * three-state rule the SDK applies to model-controlled optional arguments.
 *
 * [rawFallback] is what to report when the value is present but says nothing
 * readable (an object without a string `message`/`type`, an array, a blank
 * scalar). It is bounded: an endpoint must not push an arbitrarily long string
 * into an exception message that the host logs and the transcript stores. Passing
 * null leaves the caller's own reading of the body in charge - the whole-body
 * document path uses that to keep naming the parse refusal instead of the framing.
 */
internal fun apiErrorReasonOrNull(error: JsonElement?, rawFallback: String?): String? {
    if (error == null || error is JsonNull) return null
    val readable = when (error) {
        // Folded over the candidate list rather than chained: the first field that
        // carries text wins, and adding a candidate cannot silently reorder them.
        is JsonObject -> listOf("message", "type")
            .firstNotNullOfOrNull { key -> error.textOrNull(key) }
        is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }
    return readable ?: rawFallback?.trim()?.take(MAX_API_ERROR_ECHO_CHARS)?.takeIf { it.isNotEmpty() }
}

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
