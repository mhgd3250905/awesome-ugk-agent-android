package com.ugk.pi.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenAiChatCompletionsProvider(
    private val apiKey: String,
    private val model: String,
    private val transport: HttpTransport? = null,
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
    private val maxStreamedBytes: Int = DEFAULT_MAX_STREAMED_BYTES,
    /**
     * The retry policy is provider-agnostic in shape (attempt budget and delay
     * curve only), so both providers share one type instead of keeping two
     * copies that drift. It previously only covered the Anthropic
     * non-streaming call; streaming and the OpenAI paths now honor it too.
     */
    private val retryPolicy: AnthropicRetryPolicy = AnthropicRetryPolicy()
) : LLMProvider {
    init {
        // maxStreamedBytes is only plumbed into the default transport; a
        // custom non-JavaNetHttpTransport must configure its own cap, and a
        // silently ignored explicit value here would hide that.
        require(transport == null || maxStreamedBytes == DEFAULT_MAX_STREAMED_BYTES) {
            "maxStreamedBytes is only applied to the default transport; " +
                "configure the cap on the supplied HttpTransport itself"
        }
    }

    /**
     * Falls back to a [JavaNetHttpTransport] that honors [maxStreamedBytes]
     * when the host does not supply its own transport.
     */
    private val effectiveTransport: HttpTransport =
        transport ?: JavaNetHttpTransport(maxStreamedBytes = maxStreamedBytes)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val httpResponse = executeWithRetry(
            HttpRequest(
                url = endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json"
                ),
                body = requestBody(request, stream = false).toString()
            )
        )

        if (httpResponse.statusCode !in 200..299) {
            throw IllegalStateException(
                "OpenAI chat completions request failed: ${httpResponse.statusCode} ${httpResponse.body}"
            )
        }

        return parseResponse(httpResponse.body)
    }

    override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
        val httpRequest = HttpRequest(
            url = endpoint,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json"
            ),
            body = requestBody(request, stream = true).toString()
        )

        // Same replay window as the Anthropic streaming path: transport-level
        // failures before the first emitted chunk are invisible to the host
        // and safe to retry; after that the failure propagates untouched.
        var attempt = 1
        var emittedAny = false
        var nextDelayMillis = retryPolicy.initialDelayMillis
        while (true) {
            try {
                parseOpenAiSseStream(effectiveTransport.postStream(httpRequest).asSseLines()).collect { chunk ->
                    emittedAny = true
                    emit(chunk)
                }
                return@flow
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                attempt += 1
                if (emittedAny || attempt > retryPolicy.maxAttempts || !error.isRetryableTransportFailure()) {
                    throw error
                }
            }
            if (nextDelayMillis > 0) {
                delay(nextDelayMillis)
            }
            nextDelayMillis = (nextDelayMillis * 2)
                .coerceAtLeast(retryPolicy.initialDelayMillis)
                .coerceAtMost(retryPolicy.maxDelayMillis)
        }
    }

    private suspend fun executeWithRetry(request: HttpRequest): HttpResponse {
        var attempt = 1
        var nextDelayMillis = retryPolicy.initialDelayMillis

        while (attempt <= retryPolicy.maxAttempts) {
            try {
                val response = effectiveTransport.post(request)
                if (!isRetryableHttpStatus(response.statusCode) || attempt == retryPolicy.maxAttempts) {
                    return response
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt == retryPolicy.maxAttempts) {
                    throw error
                }
            }

            if (nextDelayMillis > 0) {
                delay(nextDelayMillis)
            }
            nextDelayMillis = (nextDelayMillis * 2)
                .coerceAtLeast(retryPolicy.initialDelayMillis)
                .coerceAtMost(retryPolicy.maxDelayMillis)
            attempt += 1
        }

        throw IllegalStateException("OpenAI chat completions request failed before execution")
    }

    private fun parseOpenAiSseStream(rawLines: Flow<String>): Flow<ModelStreamChunk> = flow {
        val accumulatedContent = StringBuilder()
        val accumulatedReasoning = StringBuilder()
        // 每个 toolCall 在流中会根据其 index 逐步拼接 arguments
        class ToolCallDraft(
            var id: String = "",
            var name: String = "",
            val argsBuilder: StringBuilder = StringBuilder()
        )
        val toolDrafts = mutableMapOf<Int, ToolCallDraft>()
        // Index of the draft a previous tool_calls chunk was appended to.
        // Some OpenAI-compatible gateways omit the `index` field entirely;
        // their continuation chunks (arguments only, no id/name) belong to
        // this active call instead of being fabricated into a new draft.
        var lastActiveToolIndex: Int? = null
        var currentStopReason: String? = null
        var completedEmitted = false
        // Payload fragments of the event currently being read: one event may
        // carry its JSON across several `data:` lines.
        var pendingDataPayload: String? = null

        // A non-empty accumulation that no longer parses as a JSON object
        // means the stream was truncated or corrupted mid-arguments:
        // executing the tool with a fabricated empty input would run it
        // against arguments the model never completed choosing, so the call
        // is dropped. Dropping only guarantees the fabricated input is
        // never executed; whether the turn is retried depends on the
        // runtime's incomplete-response check: a stop reason of
        // max_tokens/length retries, while any other outcome (e.g.
        // tool_use with non-blank content) finishes as a partial-text
        // answer. An empty argument string stays a legitimate no-argument
        // call.
        fun buildFinalToolCalls(): List<ToolCall> = toolDrafts.values.mapNotNull { draft ->
            if (draft.id.isBlank() || draft.name.isBlank()) return@mapNotNull null
            val parsedInput = parseToolArgumentsOrNull(draft.argsBuilder.toString())
                ?: return@mapNotNull null
            ToolCall(id = draft.id, name = draft.name, input = parsedInput)
        }

        rawLines.collect { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                // Event boundary: an event that never became parsable is a
                // broken stream, not content to skip.
                pendingDataPayload?.let { throw malformedSseEvent(it) }
                return@collect
            }
            if (line.startsWith(":")) {
                return@collect
            }

            // 容错：如果后端不支持流式，直接返回了完整 JSON 响应
            if (line.startsWith("{") && line.endsWith("}")) {
                // A full error body must fail the stream: parseResponse now
                // throws for API errors, and swallowing that here would
                // degrade into the blank end-of-stream completion below.
                fullBodyApiErrorMessageOrNull(line)?.let { message ->
                    throw IllegalStateException("OpenAI stream error: $message")
                }
                val parsed = runCatching { parseResponse(line) }.getOrNull()
                if (parsed != null) {
                    if (!parsed.reasoningContent.isNullOrBlank()) {
                        emit(ModelStreamChunk.ThinkingDelta(parsed.reasoningContent))
                    }
                    if (parsed.content.isNotBlank()) {
                        emit(ModelStreamChunk.ContentDelta(parsed.content))
                    }
                    emit(ModelStreamChunk.Completed(parsed))
                    completedEmitted = true
                    return@collect
                }
            }

            if (!line.startsWith("data:")) {
                return@collect
            }

            val dataStr = line.removePrefix("data:").trim()
            if (dataStr == "[DONE]") {
                // An unfinished event must fail the stream instead of being
                // swept into the completion emitted below.
                pendingDataPayload?.let { throw malformedSseEvent(it) }
                if (!completedEmitted) {
                    val finalToolCalls = buildFinalToolCalls()
                    val response = ModelResponse(
                        content = accumulatedContent.toString(),
                        toolCalls = finalToolCalls,
                        stopReason = currentStopReason,
                        reasoningContent = accumulatedReasoning.toString().takeIf { it.isNotBlank() }
                    )
                    emit(ModelStreamChunk.Completed(response))
                    completedEmitted = true
                }
                return@collect
            }

            if (dataStr.isEmpty()) {
                // An empty payload carries no event; buffering it would leave a
                // fragment open that the stream end then reports as malformed.
                return@collect
            }

            // Try the line on its own first. Endpoints that leave out the blank
            // line between events are still readable that way, and a buffered
            // fragment must not swallow every well-formed event after it. Only
            // when the line cannot stand alone does it continue the event
            // currently being read.
            val standalone = runCatching { json.parseToJsonElement(dataStr) }.getOrNull()
            val joined = standalone ?: pendingDataPayload?.let { buffered ->
                runCatching { json.parseToJsonElement("$buffered\n$dataStr") }.getOrNull()
            }
            if (joined == null) {
                val buffered = pendingDataPayload?.let { "$it\n$dataStr" } ?: dataStr
                if (buffered.length > MAX_BUFFERED_SSE_EVENT_CHARS) {
                    throw malformedSseEvent(buffered)
                }
                pendingDataPayload = buffered
                return@collect
            }
            pendingDataPayload = null
            val dataObj = joined as? JsonObject ?: return@collect

            // OpenAI-compatible gateways push mid-stream failures (rate
            // limits, content filters, upstream disconnects) as a data event
            // carrying an `error` object. Dropping it here would finish a
            // truncated answer as a normal completion, so surface the error.
            // `"error":null` is how a POJO-serialized gateway spells "no
            // error", and JsonNull is a value rather than an absent key.
            val errorElement = dataObj["error"]
            if (errorElement != null && errorElement !is JsonNull) {
                val message = (errorElement as? JsonObject)
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: dataStr
                throw IllegalStateException("OpenAI stream error: $message")
            }

            // The remaining fields are optional in the protocol and arrive as
            // JSON null from real gateways; `JsonNull.jsonObject` throws, so
            // read them with safe casts and treat null as absent.
            val choices = (dataObj["choices"] as? JsonArray) ?: return@collect
            val firstChoice = (choices.firstOrNull() as? JsonObject) ?: return@collect
            val finishReason = firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (!finishReason.isNullOrBlank()) {
                currentStopReason = finishReason
            }

            val delta = firstChoice["delta"] as? JsonObject ?: return@collect

            // 思考链增量（Reasoning / CoT，OpenAI 协议常用 reasoning_content）。
            // contentText 容错处理部分网关用 content-parts 数组回传的形态。
            val reasoning = contentText(delta["reasoning_content"])
            if (!reasoning.isNullOrEmpty()) {
                accumulatedReasoning.append(reasoning)
                emit(ModelStreamChunk.ThinkingDelta(reasoning))
            }

            // 正文内容增量（兼容纯字符串与 content-parts 数组两种网关形态）
            val content = contentText(delta["content"])
            if (content.isNotEmpty()) {
                accumulatedContent.append(content)
                emit(ModelStreamChunk.ContentDelta(content))
            }

            // 工具调用分片
            val toolCallsArray = delta["tool_calls"] as? JsonArray
            toolCallsArray?.forEach { toolElement ->
                val toolObj = toolElement as? JsonObject ?: return@forEach
                val explicitIndex = toolObj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val id = toolObj["id"]?.jsonPrimitive?.contentOrNull
                // The spec requires `index` on streamed tool_calls, but some
                // gateways omit it. Without it: a chunk carrying an id joins
                // the draft with that id (or starts a new call); an arguments
                // continuation chunk extends the most recently active draft.
                // Falling back to toolDrafts.size for continuations would
                // fabricate a phantom draft whose blank id gets the real
                // arguments dropped while the first call runs with `{}`.
                val index = when {
                    explicitIndex != null -> explicitIndex
                    !id.isNullOrBlank() ->
                        toolDrafts.entries.firstOrNull { it.value.id == id }?.key
                            ?: toolDrafts.size
                    else -> lastActiveToolIndex ?: toolDrafts.size
                }
                val draft = toolDrafts.getOrPut(index) { ToolCallDraft() }
                lastActiveToolIndex = index

                if (!id.isNullOrBlank()) {
                    draft.id = id
                }

                val functionObj = toolObj["function"] as? JsonObject
                if (functionObj != null) {
                    val name = functionObj["name"]?.jsonPrimitive?.contentOrNull
                    if (!name.isNullOrBlank()) {
                        draft.name = name
                    }
                    val args = functionObj["arguments"]?.jsonPrimitive?.contentOrNull
                    if (!args.isNullOrEmpty()) {
                        draft.argsBuilder.append(args)
                    }
                }
            }
        }

        // A stream that stops in the middle of an event delivered a payload that
        // never became parsable: that must fail the stream instead of falling
        // back to an answer built from the truncated prefix.
        pendingDataPayload?.let { throw malformedSseEvent(it) }

        // 流结束兜底
        if (!completedEmitted) {
            val finalToolCalls = buildFinalToolCalls()
            val response = ModelResponse(
                content = accumulatedContent.toString(),
                toolCalls = finalToolCalls,
                stopReason = currentStopReason,
                reasoningContent = accumulatedReasoning.toString().takeIf { it.isNotBlank() }
            )
            emit(ModelStreamChunk.Completed(response))
        }
    }

    /**
     * Parses accumulated tool-call arguments. An empty accumulation is a
     * legitimate no-argument call and maps to an empty object; anything else
     * that fails to parse as a JSON object is truncated or corrupted input
     * and maps to null so the caller drops the call instead of executing it
     * with fabricated input.
     */
    private fun parseToolArgumentsOrNull(accumulated: String): JsonObject? {
        if (accumulated.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(accumulated) }.getOrNull() as? JsonObject
    }

    /**
     * Extracts assistant text from a `content` field. OpenAI-compatible
     * gateways may serialize it as a plain string or as an array of typed
     * content parts: a primitive yields its string, an array yields its
     * `type == "text"` parts concatenated in order (other part types are
     * ignored), and any other shape (object, null) degrades to an empty
     * string instead of failing the whole request.
     */
    private fun contentText(content: JsonElement?): String {
        return when (content) {
            null -> ""
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> content.joinToString(separator = "") { it.textContentOrNull() }
            else -> ""
        }
    }

    private fun JsonElement.textContentOrNull(): String {
        val part = this as? JsonObject ?: return ""
        if ((part["type"] as? JsonPrimitive)?.contentOrNull != "text") return ""
        return (part["text"] as? JsonPrimitive)?.contentOrNull ?: ""
    }

    private fun requestBody(request: ModelRequest, stream: Boolean = false): JsonObject {
        return buildJsonObject {
            put("model", model)
            if (stream) {
                put("stream", true)
            }
            put("messages", JsonArray(request.messages.map { it.toOpenAiMessage() }))
            if (request.responseFormat == ModelResponseFormat.JSON_OBJECT) {
                putJsonObject("response_format") {
                    put("type", "json_object")
                }
            }
            if (request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map { it.toOpenAiTool() }))
                put("tool_choice", "auto")
            }
        }
    }

    private fun AgentMessage.toOpenAiMessage(): JsonObject {
        return when (this) {
            is AgentMessage.System -> buildJsonObject {
                put("role", "system")
                put("content", content)
            }

            is AgentMessage.User -> buildJsonObject {
                put("role", "user")
                if (images.isEmpty()) {
                    put("content", content)
                } else {
                    putJsonArray("content") {
                        images.forEach { img ->
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:${img.mimeType};base64,${img.base64Data}")
                                    }
                                }
                            )
                        }
                        if (content.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", content)
                                }
                            )
                        }
                    }
                }
            }

            is AgentMessage.Assistant -> buildJsonObject {
                put("role", "assistant")
                put("content", content)
                reasoningContent
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("reasoning_content", it) }
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JsonArray(toolCalls.map { it.toOpenAiToolCall() }))
                }
            }

            is AgentMessage.Tool -> buildJsonObject {
                put("role", "tool")
                put("tool_call_id", result.toolCallId)
                put("content", result.content)
            }
        }
    }

    private fun AgentToolDefinition.toOpenAiTool(): JsonObject {
        return buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                put("parameters", inputSchema)
            }
        }
    }

    private fun ToolCall.toOpenAiToolCall(): JsonObject {
        return buildJsonObject {
            put("id", id)
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("arguments", input.toString())
            }
        }
    }

    private fun fullBodyApiErrorMessageOrNull(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val errorObj = root["error"] as? JsonObject ?: return null
        return errorObj["message"]?.jsonPrimitive?.contentOrNull
            ?: errorObj["type"]?.jsonPrimitive?.contentOrNull
    }

    private fun parseResponse(body: String): ModelResponse {
        val root = json.parseToJsonElement(body).jsonObject
        // A 200 body can still be an API error object (quota exhaustion,
        // gateway overload). Parsing it as a message would yield a blank
        // "successful" response and mask the real failure.
        (root["error"] as? JsonObject)?.let { errorObj ->
            val message = errorObj["message"]?.jsonPrimitive?.contentOrNull
                ?: errorObj["type"]?.jsonPrimitive?.contentOrNull
                ?: body.take(200)
            throw IllegalStateException("OpenAI API error: $message")
        }
        val choices = (root["choices"] as? JsonArray)
            ?: error("OpenAI response missing choices[0]")
        val choice = (choices.firstOrNull() as? JsonObject)
            ?: error("OpenAI response missing choices[0]")
        val message = (choice["message"] as? JsonObject)
            ?: error("OpenAI response missing choices[0].message")

        val content = contentText(message["content"])
        val toolCalls = (message["tool_calls"] as? JsonArray)
            ?.mapNotNull { it.toToolCallOrNull() }
            ?: emptyList()

        return ModelResponse(
            content = content,
            toolCalls = toolCalls,
            stopReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            reasoningContent = contentText(message["reasoning_content"]).takeIf { it.isNotEmpty() }
        )
    }

    private fun JsonElement.toToolCallOrNull(): ToolCall? {
        val objectValue = this as? JsonObject ?: return null
        val function = objectValue["function"] as? JsonObject ?: return null
        // Same policy as the streaming path (parseToolArgumentsOrNull):
        // missing or blank arguments are a legitimate no-argument call;
        // anything that does not parse as a JSON object is dropped instead
        // of being fabricated into {"value": ...} — the model never chose
        // that shape.
        val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull
        val input = parseToolArgumentsOrNull(arguments ?: "") ?: return null

        return ToolCall(
            // Same policy as the streaming path's buildFinalToolCalls: a blank
            // id or name reaches the transcript and then invalidates every
            // later request.
            id = objectValue["id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return null,
            name = function["name"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return null,
            input = input
        )
    }
}
