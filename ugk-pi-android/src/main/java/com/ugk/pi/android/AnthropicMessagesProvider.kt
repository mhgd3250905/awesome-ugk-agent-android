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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.delay

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
    private val transport: HttpTransport = JavaNetHttpTransport(),
    private val maxTokens: Int = 32_768,
    private val anthropicVersion: String = "2023-06-01",
    private val retryPolicy: AnthropicRetryPolicy = AnthropicRetryPolicy()
) : LLMProvider {
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
            body = requestBody(request).toString()
        )
        val httpResponse = executeWithRetry(httpRequest)

        if (httpResponse.statusCode !in 200..299) {
            throw IllegalStateException(
                "Anthropic messages request failed: ${httpResponse.statusCode} ${httpResponse.body}"
            )
        }

        return parseResponse(httpResponse.body)
    }

    private suspend fun executeWithRetry(request: HttpRequest): HttpResponse {
        var attempt = 1
        var nextDelayMillis = retryPolicy.initialDelayMillis
        var lastError: Throwable? = null

        while (attempt <= retryPolicy.maxAttempts) {
            try {
                val response = transport.post(request)
                if (!response.statusCode.isRetryableStatusCode() || attempt == retryPolicy.maxAttempts) {
                    return response
                }
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

    private fun requestBody(request: ModelRequest): JsonObject {
        return buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)

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
        val pendingToolResults = mutableListOf<AgentMessage.Tool>()

        fun flushToolResults() {
            if (pendingToolResults.isEmpty()) return
            result += pendingToolResults.toAnthropicToolResultMessage()
            pendingToolResults.clear()
        }

        forEach { message ->
            when (message) {
                is AgentMessage.Tool -> pendingToolResults += message
                else -> {
                    flushToolResults()
                    result += message.toAnthropicMessage()
                }
            }
        }
        flushToolResults()
        return result
    }

    private fun AgentMessage.toAnthropicMessage(): JsonObject {
        return when (this) {
            is AgentMessage.System -> error("System messages are serialized as the top-level system field")
            is AgentMessage.User -> buildJsonObject {
                put("role", "user")
                put("content", content)
            }

            is AgentMessage.Assistant -> buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    reasoningContent
                        ?.takeIf { it.isNotBlank() }
                        ?.let { reasoning ->
                            add(
                                buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", reasoning)
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
                    toolCalls.forEach { call ->
                        add(call.toAnthropicToolUse())
                    }
                }
            }

            is AgentMessage.Tool -> error("Tool messages are serialized in grouped user messages")
        }
    }

    private fun List<AgentMessage.Tool>.toAnthropicToolResultMessage(): JsonObject {
        return buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                this@toAnthropicToolResultMessage.forEach { message ->
                    add(message.result.toAnthropicToolResult())
                }
            }
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
