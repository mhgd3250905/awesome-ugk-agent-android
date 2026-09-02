package com.ugk.pi.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenAiChatCompletionsProvider(
    private val apiKey: String,
    private val model: String,
    private val transport: HttpTransport? = null,
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
    private val maxStreamedBytes: Int = DEFAULT_MAX_STREAMED_BYTES
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
        val httpResponse = effectiveTransport.post(
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

        val rawLinesFlow = effectiveTransport.postStream(httpRequest)
        emitAll(parseOpenAiSseStream(rawLinesFlow))
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
        var currentStopReason: String? = null
        var completedEmitted = false

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
            if (line.isEmpty() || line.startsWith(":")) {
                return@collect
            }

            // 容错：如果后端不支持流式，直接返回了完整 JSON 响应
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
            if (dataStr == "[DONE]") {
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

            val dataElement = runCatching { json.parseToJsonElement(dataStr) }.getOrNull() ?: return@collect
            val dataObj = dataElement as? JsonObject ?: return@collect

            val choices = dataObj["choices"]?.jsonArray ?: return@collect
            val firstChoice = choices.firstOrNull()?.jsonObject ?: return@collect
            val finishReason = firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (!finishReason.isNullOrBlank()) {
                currentStopReason = finishReason
            }

            val delta = firstChoice["delta"]?.jsonObject ?: return@collect

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
            val toolCallsArray = delta["tool_calls"]?.jsonArray
            toolCallsArray?.forEach { toolElement ->
                val toolObj = toolElement.jsonObject
                val index = toolObj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: toolDrafts.size
                val draft = toolDrafts.getOrPut(index) { ToolCallDraft() }

                val id = toolObj["id"]?.jsonPrimitive?.contentOrNull
                if (!id.isNullOrBlank()) {
                    draft.id = id
                }

                val functionObj = toolObj["function"]?.jsonObject
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

    private fun parseResponse(body: String): ModelResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: error("OpenAI response missing choices[0]")
        val message = choice["message"]
            ?.jsonObject
            ?: error("OpenAI response missing choices[0].message")

        val content = contentText(message["content"])
        val toolCalls = message["tool_calls"]
            ?.jsonArray
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
        val objectValue = jsonObject
        val function = objectValue["function"]?.jsonObject ?: return null
        // Same policy as the streaming path (parseToolArgumentsOrNull):
        // missing or blank arguments are a legitimate no-argument call;
        // anything that does not parse as a JSON object is dropped instead
        // of being fabricated into {"value": ...} — the model never chose
        // that shape.
        val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull
        val input = parseToolArgumentsOrNull(arguments ?: "") ?: return null

        return ToolCall(
            id = objectValue["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            name = function["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            input = input
        )
    }
}
