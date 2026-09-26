package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
