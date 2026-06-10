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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenAiChatCompletionsProvider(
    private val apiKey: String,
    private val model: String,
    private val transport: HttpTransport = JavaNetHttpTransport(),
    private val endpoint: String = "https://api.openai.com/v1/chat/completions"
) : LLMProvider {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val httpResponse = transport.post(
            HttpRequest(
                url = endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json"
                ),
                body = requestBody(request).toString()
            )
        )

        if (httpResponse.statusCode !in 200..299) {
            throw IllegalStateException(
                "OpenAI chat completions request failed: ${httpResponse.statusCode} ${httpResponse.body}"
            )
        }

        return parseResponse(httpResponse.body)
    }

    private fun requestBody(request: ModelRequest): JsonObject {
        return buildJsonObject {
            put("model", model)
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
                put("content", content)
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

        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val toolCalls = message["tool_calls"]
            ?.jsonArray
            ?.mapNotNull { it.toToolCallOrNull() }
            ?: emptyList()

        return ModelResponse(
            content = content,
            toolCalls = toolCalls,
            stopReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            reasoningContent = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun JsonElement.toToolCallOrNull(): ToolCall? {
        val objectValue = jsonObject
        val function = objectValue["function"]?.jsonObject ?: return null
        val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val input = when (val parsed = json.parseToJsonElement(arguments)) {
            is JsonObject -> parsed
            JsonNull -> JsonObject(emptyMap())
            else -> JsonObject(mapOf("value" to parsed))
        }

        return ToolCall(
            id = objectValue["id"]?.jsonPrimitive?.contentOrNull ?: return null,
            name = function["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            input = input
        )
    }
}
