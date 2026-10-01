package com.ugk.pi.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * True when a response `Content-Type` declares an SSE event stream.
 *
 * This is the transport's primary signal for "line framing is the payload, and
 * each line may be delivered as it arrives". Everything else is treated as one
 * document: an endpoint that ignores `stream` answers a whole JSON body, and
 * handing that over line by line takes it apart before a provider can recognise
 * the shape.
 *
 * Parameters are ignored (`text/event-stream; charset=utf-8` still is an event
 * stream), and a missing or malformed value falls through to
 * [looksLikeEventStreamLine]. A stream whose very first line is blank *and* whose
 * media type is also missing falls through to the document branch: the answer is
 * still read correctly (the reader re-splits the buffered body) but it arrives in
 * one emission, and a body over `maxResponseBytes` then fails loudly instead of
 * streaming. That boundary is pinned by a test rather than fixed, because buffering
 * is the only way to hand a document over intact.
 */
internal fun isEventStreamContentType(contentType: String?): Boolean =
    contentType?.substringBefore(';')?.trim()?.equals(EVENT_STREAM_MEDIA_TYPE, ignoreCase = true) == true

/**
 * The secondary signal: a first content line that only an SSE body produces.
 * Without it, an endpoint that streams real events but forgets the media type
 * would be buffered into one emission, losing incremental delivery for the whole
 * answer and putting a stream under the document size cap. The line is trimmed the
 * way the provider readers trim it, so the two cannot disagree about an indented
 * stream. Only the prefixes the SSE grammar reserves are recognised, so a JSON
 * document - pretty-printed or not - can never be mistaken for one.
 */
internal fun looksLikeEventStreamLine(firstLine: String): Boolean {
    val line = firstLine.trimStart()
    return line.startsWith("data:") || line.startsWith("event:") ||
        line.startsWith("id:") || line.startsWith("retry:") || line.startsWith(":")
}

private const val EVENT_STREAM_MEDIA_TYPE = "text/event-stream"

data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

data class HttpResponse(
    val statusCode: Int,
    val body: String
)

interface HttpTransport {
    suspend fun post(request: HttpRequest): HttpResponse

    /**
     * 以流式长连接发起 POST 请求，逐行发射响应数据（如 SSE 协议行）。
     * 默认回退实现：调用普通 post，并把响应体拆成完整的行后逐行发射。
     *
     * Emitting the body as one emission would break every line-oriented parser:
     * an SSE event spanning several lines cannot be parsed as a single line, so
     * the caller would silently see an empty answer.
     *
     * The contract has a second half, and both implementations owe it: a response
     * that is not an SSE event stream is one document and must be handed over
     * unsplit. [JavaNetHttpTransport] decides that from the response media type
     * plus the first line, because the socket already delivers line boundaries
     * that the provider cannot reassemble once they have been emitted separately.
     * A host transport that implements only [post] inherits the same guarantee
     * from the default below, which hands the body to [asSseLines] whole - that
     * fallback recognises a document, it does not bound the body any further than
     * [post] already does.
     */
    fun postStream(request: HttpRequest): Flow<String> = flow {
        val response = post(request)
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("HTTP request failed: ${response.statusCode} ${response.body}")
        }
        // asSseLines keeps a single JSON document intact and otherwise emits one
        // line per emission, which is what the provider parsers read.
        emitAll(flowOf(response.body).asSseLines())
    }
}

class JavaNetHttpTransport(
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 180_000,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val maxStreamedBytes: Int = DEFAULT_MAX_STREAMED_BYTES
) : HttpTransport {
    init {
        require(connectTimeoutMillis >= 0) { "connectTimeoutMillis must be greater than or equal to 0" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must be greater than or equal to 0" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be greater than 0" }
        require(maxStreamedBytes > 0) { "maxStreamedBytes must be greater than 0" }
    }

    override fun postStream(request: HttpRequest): Flow<String> = channelFlow {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        // This body must never block: coroutine cancellation only resumes
        // suspended code, so a blocking read here would pin the socket and
        // its IO thread until readTimeout even after the collector is gone.
        // All blocking work runs in a child coroutine while this body stays
        // suspended in awaitClose, where collector cancellation immediately
        // disconnects the connection — and disconnect() unblocks the child.
        launch {
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.doOutput = true
                request.headers.forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }
                connection.outputStream.use { output ->
                    output.write(request.body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = connection.errorStream?.use { it.readUtf8(maxResponseBytes) } ?: ""
                    throw IllegalStateException("HTTP request failed: $responseCode $errorBody")
                }

                BufferedInputStream(connection.inputStream, 8 * 1024).use { input ->
                    val firstLine = input.readUtf8Line(maxResponseBytes)
                    if (firstLine == null) {
                        // An empty body is neither an event stream nor a
                        // document; the providers report that as a failure
                        // rather than completing with nothing.
                        return@use
                    }
                    // A response labelled `text/event-stream` is framed by line.
                    // The label is not enough on its own: an endpoint that drops
                    // or missets it still has to stream, or the answer arrives in
                    // one burst at EOF (or fails the document cap). The first line
                    // carrying SSE framing is that case, and it costs nothing -
                    // line framing was already the behaviour for both.
                    val eventStream = isEventStreamContentType(connection.contentType) ||
                        looksLikeEventStreamLine(firstLine.text)
                    if (eventStream) {
                        var totalStreamedBytes = firstLine.byteCount.toLong()
                        send(firstLine.text)
                        while (true) {
                            val line = input.readUtf8Line(maxResponseBytes) ?: break
                            // The per-line cap above cannot bound a stream of many
                            // small SSE events: only the SUM of all line bytes
                            // does. A hostile or broken endpoint that pushes past
                            // maxStreamedBytes fails here instead of accumulating
                            // unbounded data in the host.
                            totalStreamedBytes += line.byteCount
                            if (totalStreamedBytes > maxStreamedBytes) {
                                throw IOException("HTTP stream exceeds maxStreamedBytes=$maxStreamedBytes")
                            }
                            send(line.text)
                        }
                    } else {
                        // The endpoint did not answer an event stream, so this body
                        // is one document. Handing it over line by line would take
                        // it apart before a provider can recognise the shape, so it
                        // is read whole under the same byte cap the non-streaming
                        // post() applies - and it is reported, not buffered past it.
                        val document = StringBuilder().apply { append(firstLine.text) }
                        var totalDocumentBytes = firstLine.byteCount.toLong()
                        while (true) {
                            val line = input.readUtf8Line(maxResponseBytes) ?: break
                            totalDocumentBytes += line.byteCount
                            if (totalDocumentBytes > maxResponseBytes) {
                                throw IOException("HTTP response exceeds maxResponseBytes=$maxResponseBytes")
                            }
                            document.append('\n').append(line.text)
                        }
                        send(document.toString())
                    }
                }
                close()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                close(error)
            }
        }
        try {
            awaitClose { connection.disconnect() }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun post(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            request.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            connection.outputStream.use { output ->
                output.write(request.body.toByteArray(Charsets.UTF_8))
            }

            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val body = stream?.use { it.readUtf8(maxResponseBytes) } ?: ""
            HttpResponse(connection.responseCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readUtf8(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count > maxBytes - totalBytes) {
                throw IOException("HTTP response exceeds maxResponseBytes=$maxBytes")
            }
            output.write(buffer, 0, count)
            totalBytes += count
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads a single line with a hard byte cap. Bytes are counted before
     * UTF-8 decoding so the bound holds for multi-byte characters too, and a
     * line that would exceed it fails instead of buffering unbounded data.
     * The stream must support mark/reset for the CRLF lookahead
     * (see [BufferedInputStream]). Returns the decoded text together with
     * the wire byte count of the line content so the caller can also bound
     * the total across lines.
     */
    private fun InputStream.readUtf8Line(maxBytes: Int): StreamedLine? {
        val line = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        while (true) {
            val byte = read()
            if (byte < 0) {
                return if (line.size() == 0) {
                    null
                } else {
                    StreamedLine(line.toString(Charsets.UTF_8.name()), line.size())
                }
            }
            if (byte == '\n'.code || byte == '\r'.code) {
                if (byte == '\r'.code) {
                    // Mirror BufferedReader.readLine(): CR, LF, and CRLF all
                    // terminate a line; swallow the LF of a CRLF pair.
                    mark(1)
                    if (read() != '\n'.code) reset()
                }
                return StreamedLine(line.toString(Charsets.UTF_8.name()), line.size())
            }
            if (line.size() >= maxBytes) {
                throw IOException("HTTP response line exceeds maxResponseBytes=$maxBytes")
            }
            line.write(byte)
        }
    }

    /** One streamed line with the wire byte count of its content. */
    private class StreamedLine(val text: String, val byteCount: Int)
}

/**
 * Default total byte cap for one streamed (SSE) response: the sum of all
 * line content bytes across the whole stream. Generous enough for large
 * legitimate answers (the SSE/JSON envelope roughly doubles to triples the
 * plain content size) while bounding what a hostile or broken endpoint can
 * push into the host. Hosts can override it via
 * [JavaNetHttpTransport.maxStreamedBytes] or the provider constructors.
 */
internal const val DEFAULT_MAX_STREAMED_BYTES = 8 * 1024 * 1024
