package com.ugk.pi.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

data class AnthropicRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 2_000
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must be greater than or equal to 0" }
        require(maxDelayMillis >= 0) { "maxDelayMillis must be greater than or equal to 0" }
    }
}

class AnthropicMessagesProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val transport: HttpTransport? = null,
    private val maxTokens: Int = 32_768,
    private val anthropicVersion: String = "2023-06-01",
    private val retryPolicy: AnthropicRetryPolicy = AnthropicRetryPolicy(),
    private val maxStreamedBytes: Int = DEFAULT_MAX_STREAMED_BYTES
) : LLMProvider {
    init {
        // maxStreamedBytes is only plumbed into the default transport; a
        // custom non-JavaNetHttpTransport must configure its own cap, and a
        // silently ignored explicit value here would hide that.
        require(
            transport == null ||
                transport is JavaNetHttpTransport ||
                maxStreamedBytes == DEFAULT_MAX_STREAMED_BYTES
        ) {
            "maxStreamedBytes is not applied to a custom HttpTransport; configure the cap on the transport itself"
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
        val httpRequest = HttpRequest(
            url = "${baseUrl.trimEnd('/')}/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to anthropicVersion,
                "content-type" to "application/json"
            ),
            body = requestBody(request, stream = false).toString()
        )
        val httpResponse = executeWithRetry(httpRequest)

        if (httpResponse.statusCode !in 200..299) {
            throw IllegalStateException(
                "Anthropic messages request failed: ${httpResponse.statusCode} ${httpResponse.body}"
            )
        }

        return parseResponse(httpResponse.body)
    }

    override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
        val httpRequest = HttpRequest(
            url = "${baseUrl.trimEnd('/')}/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to anthropicVersion,
                "content-type" to "application/json"
            ),
            body = requestBody(request, stream = true).toString()
        )

        val rawLinesFlow = effectiveTransport.postStream(httpRequest)
        emitAll(parseSseStream(rawLinesFlow))
    }

    private fun parseSseStream(rawLines: Flow<String>): Flow<ModelStreamChunk> = flow {
        val accumulatedContent = StringBuilder()
        val accumulatedThinking = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var currentStopReason: String? = null

        var currentToolId: String? = null
        var currentToolName: String? = null
        val currentToolInputJson = StringBuilder()
        var completedEmitted = false

        rawLines.collect { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(":")) {
                // SSE 注释或心跳行
                return@collect
            }

            // 容错：如果后端不支持 SSE，直接返回了完整 JSON 字符串
            if (line.startsWith("{") && line.endsWith("}")) {
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
            if (dataStr == "[DONE]" || dataStr.isEmpty()) {
                return@collect
            }

            val dataElement = runCatching { json.parseToJsonElement(dataStr) }.getOrNull() ?: return@collect
            val dataObj = dataElement as? JsonObject ?: return@collect

            when (dataObj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val block = dataObj["content_block"] as? JsonObject
                    val blockType = block?.get("type")?.jsonPrimitive?.contentOrNull
                    if (blockType == "tool_use") {
                        currentToolId = block["id"]?.jsonPrimitive?.contentOrNull
                        currentToolName = block["name"]?.jsonPrimitive?.contentOrNull
                        currentToolInputJson.clear()
                    }
                }

                "content_block_delta" -> {
                    val delta = dataObj["delta"] as? JsonObject ?: return@collect
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "thinking_delta" -> {
                            val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull
                            if (!thinking.isNullOrEmpty()) {
                                accumulatedThinking.append(thinking)
                                emit(ModelStreamChunk.ThinkingDelta(thinking))
                            }
                        }

                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                            if (!text.isNullOrEmpty()) {
                                accumulatedContent.append(text)
                                emit(ModelStreamChunk.ContentDelta(text))
                            }
                        }

                        "input_json_delta" -> {
                            val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull
                            if (!partial.isNullOrEmpty()) {
                                currentToolInputJson.append(partial)
                            }
                        }
                    }
                }

                "content_block_stop" -> {
                    if (currentToolId != null && currentToolName != null) {
                        // A non-empty accumulation that no longer parses as a
                        // JSON object means the stream was truncated or
                        // corrupted mid-arguments. Executing the tool with a
                        // fabricated input would run it against arguments the
                        // model never completed choosing, so the call is
                        // dropped. Dropping only guarantees the fabricated
                        // input is never executed; whether the turn is retried
                        // depends on the runtime's incomplete-response check:
                        // a stop reason of max_tokens/length retries, while
                        // any other outcome (e.g. tool_use with non-blank
                        // content) finishes as a partial-text answer.
                        val input = parseToolInputOrNull(currentToolInputJson.toString())
                        if (input != null) {
                            toolCalls.add(
                                ToolCall(
                                    id = currentToolId!!,
                                    name = currentToolName!!,
                                    input = input
                                )
                            )
                        }
                        currentToolId = null
                        currentToolName = null
                        currentToolInputJson.clear()
                    }
                }

                "message_delta" -> {
                    val delta = dataObj["delta"] as? JsonObject
                    val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                    if (!stopReason.isNullOrBlank()) {
                        currentStopReason = stopReason
                    }
                }

                "message_stop" -> {
                    if (!completedEmitted) {
                        val response = ModelResponse(
                            content = accumulatedContent.toString(),
                            toolCalls = toolCalls.toList(),
                            stopReason = currentStopReason,
                            reasoningContent = accumulatedThinking.toString().takeIf { it.isNotBlank() }
                        )
                        emit(ModelStreamChunk.Completed(response))
                        completedEmitted = true
                    }
                }

                "error" -> {
                    val errorObj = dataObj["error"] as? JsonObject
                    val message = errorObj?.get("message")?.jsonPrimitive?.contentOrNull ?: dataStr
                    throw IllegalStateException("Anthropic SSE stream error: $message")
                }
            }
        }

        // 流正常完结兜底：如果服务端未正常发送 message_stop 便关闭了数据流
        if (!completedEmitted) {
            val response = ModelResponse(
                content = accumulatedContent.toString(),
                toolCalls = toolCalls.toList(),
                stopReason = currentStopReason,
                reasoningContent = accumulatedThinking.toString().takeIf { it.isNotBlank() }
            )
            emit(ModelStreamChunk.Completed(response))
        }
    }

    private suspend fun executeWithRetry(request: HttpRequest): HttpResponse {
        var attempt = 1
        var nextDelayMillis = retryPolicy.initialDelayMillis
        var lastError: Throwable? = null

        while (attempt <= retryPolicy.maxAttempts) {
            try {
                val response = effectiveTransport.post(request)
                if (!response.statusCode.isRetryableStatusCode() || attempt == retryPolicy.maxAttempts) {
                    return response
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
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

        throw lastError ?: IllegalStateException("Anthropic messages request failed before execution")
    }

    private fun Int.isRetryableStatusCode(): Boolean {
        return this == 408 || this == 429 || this in 500..599
    }

    private fun requestBody(request: ModelRequest, stream: Boolean = false): JsonObject {
        return buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (stream) {
                put("stream", true)
            }

            val systemText = request.messages
                .filterIsInstance<AgentMessage.System>()
                .joinToString(separator = "\n\n") { it.content }
            if (systemText.isNotBlank()) {
                put("system", systemText)
            }

            put(
                "messages",
                JsonArray(request.messages
                    .filterNot { it is AgentMessage.System }
                    .toAnthropicMessages())
            )

            if (request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map { it.toAnthropicTool() }))
            }
        }
    }

    private fun List<AgentMessage>.toAnthropicMessages(): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val runToolResults = mutableListOf<AgentMessage.Tool>()
        val runUsers = mutableListOf<AgentMessage.User>()

        // The Messages API enforces strict user/assistant alternation, so one
        // run of consecutive Tool and User messages must be serialized as a
        // single user message whose content blocks are concatenated in order.
        fun flushRun() {
            when {
                runToolResults.isEmpty() && runUsers.isEmpty() -> return
                runToolResults.isEmpty() && runUsers.size == 1 -> {
                    result += runUsers.single().toAnthropicMessage()
                }

                else -> {
                    result += buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            runToolResults.forEach { message ->
                                add(message.result.toAnthropicToolResult())
                            }
                            runUsers.forEach { message ->
                                message.appendUserContentBlocksTo(this)
                            }
                        }
                    }
                }
            }
            runToolResults.clear()
            runUsers.clear()
        }

        forEach { message ->
            when (message) {
                is AgentMessage.Tool -> runToolResults += message
                is AgentMessage.User -> runUsers += message
                else -> {
                    flushRun()
                    result += message.toAnthropicMessage()
                }
            }
        }
        flushRun()
        return result
    }

    private fun AgentMessage.toAnthropicMessage(): JsonObject {
        return when (this) {
            is AgentMessage.System -> error("System messages are serialized as the top-level system field")
            is AgentMessage.User -> buildJsonObject {
                put("role", "user")
                if (images.isEmpty()) {
                    put("content", content)
                } else {
                    putJsonArray("content") {
                        appendUserContentBlocksTo(this)
                    }
                }
            }

            is AgentMessage.Assistant -> buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    // Thinking is never replayed: the Messages API requires a
                    // signature on returned thinking blocks and rejects them
                    // when the request does not enable thinking, while
                    // AgentMessage.Assistant does not carry signatures.
                    if (content.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", content)
                            }
                        )
                    } else if (toolCalls.isEmpty()) {
                        // Blank tool-less assistant messages can still sit in
                        // a transcript (legacy data or host-appended entries).
                        // Serialized as-is they would produce an empty
                        // content array, which the Messages API rejects for
                        // every later request of the session, so repair them
                        // at this serialization boundary.
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", BLANK_ASSISTANT_PLACEHOLDER)
                            }
                        )
                    }
                    toolCalls.forEach { call ->
                        add(call.toAnthropicToolUse())
                    }
                }
            }

            is AgentMessage.Tool -> error("Tool messages are serialized in grouped user messages")
        }
    }

    /** Appends this user message's image and text blocks to [blocks], in order. */
    private fun AgentMessage.User.appendUserContentBlocksTo(blocks: JsonArrayBuilder) {
        images.forEach { img ->
            blocks.add(
                buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", img.mimeType)
                        put("data", img.base64Data)
                    }
                }
            )
        }
        if (content.isNotBlank()) {
            blocks.add(
                buildJsonObject {
                    put("type", "text")
                    put("text", this@appendUserContentBlocksTo.content)
                }
            )
        }
    }

    private fun ToolResult.toAnthropicToolResult(): JsonObject {
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", toolCallId)
            put("content", content)
            if (isError) {
                put("is_error", true)
            }
        }
    }

    private fun AgentToolDefinition.toAnthropicTool(): JsonObject {
        return buildJsonObject {
            put("name", name)
            put("description", description)
            put("input_schema", inputSchema)
        }
    }

    private fun ToolCall.toAnthropicToolUse(): JsonObject {
        return buildJsonObject {
            put("type", "tool_use")
            put("id", id)
            put("name", name)
            put("input", input)
        }
    }

    /**
     * Parses accumulated tool-input JSON. An empty accumulation is a
     * legitimate no-argument call and maps to an empty object; anything else
     * that fails to parse as a JSON object is truncated or corrupted input
     * and maps to null so the caller drops the call instead of executing it
     * with fabricated input.
     */
    private fun parseToolInputOrNull(accumulated: String): JsonObject? {
        if (accumulated.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(accumulated) }.getOrNull() as? JsonObject
    }

    private fun parseResponse(body: String): ModelResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val contentBlocks = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val text = contentBlocks
            .mapNotNull { block ->
                val objectValue = block.jsonObject
                if (objectValue["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    objectValue["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
            .joinToString(separator = "\n")
        val reasoningContent = contentBlocks
            .mapNotNull { block ->
                val objectValue = block.jsonObject
                if (objectValue["type"]?.jsonPrimitive?.contentOrNull == "thinking") {
                    objectValue["thinking"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
            .joinToString(separator = "\n")
            .takeIf { it.isNotBlank() }

        val toolCalls = contentBlocks.mapNotNull { it.toToolCallOrNull() }
        return ModelResponse(
            content = text,
            toolCalls = toolCalls,
            stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull,
            reasoningContent = reasoningContent
        )
    }

    private fun JsonElement.toToolCallOrNull(): ToolCall? {
        val objectValue = jsonObject
        if (objectValue["type"]?.jsonPrimitive?.contentOrNull != "tool_use") {
            return null
        }

        return ToolCall(
            id = objectValue["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            name = objectValue["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            input = objectValue["input"] as? JsonObject ?: JsonObject(emptyMap())
        )
    }
}

/** Serialized stand-in for a blank tool-less assistant message. */
private const val BLANK_ASSISTANT_PLACEHOLDER = "(no content)"
