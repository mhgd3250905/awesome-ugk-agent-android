package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ModelResponseFormat
import com.ugk.pi.android.ModelStreamChunk
import com.ugk.pi.android.ToolCall
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Lets the model decide whether the current user turn needs the in-conversation timer. */
internal class DemoModelIntentRouter(
    private val delegate: LLMProvider,
    profile: ProviderProfile
) : LLMProvider {
    private val nativeJsonMode = profile.resolvedProtocol == ProviderProtocol.OPENAI_CHAT_COMPLETIONS &&
        runCatching { URI(profile.endpoint).host?.lowercase() }.getOrNull().let { host ->
            host == "api.z.ai" || host == "open.bigmodel.cn"
        }

    override suspend fun generate(request: ModelRequest): ModelResponse =
        when (val route = routeIfNeeded(request)) {
            is Route.Timed -> route.asModelResponse()
            Route.Continue -> delegate.generate(request)
        }

    override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
        when (val route = routeIfNeeded(request)) {
            is Route.Timed -> emit(ModelStreamChunk.Completed(route.asModelResponse()))
            Route.Continue -> emitAll(delegate.generateStream(request))
        }
    }

    private suspend fun routeIfNeeded(request: ModelRequest): Route {
        if (request.runSource != AgentRunSource.USER || !request.isFirstModelRequest ||
            request.tools.none { it.name == DELAY_TOOL_NAME }
        ) return Route.Continue

        val latestUserIndex = request.messages.indexOfLast { it is AgentMessage.User }
        val latestUser = request.messages.getOrNull(latestUserIndex) as? AgentMessage.User
            ?: throw IllegalStateException("没有可供模型判断的用户消息，未创建定时任务。")
        val recentContext = request.messages.take(latestUserIndex)
            .mapNotNull { message ->
                when (message) {
                    is AgentMessage.User -> "User: ${message.content.take(1_200)}"
                    is AgentMessage.Assistant -> "Assistant: ${message.content.take(1_200)}"
                    else -> null
                }
            }
            .takeLast(12)
            .joinToString("\n")
        val routingInput = AgentMessage.User(
            content = buildString {
                if (recentContext.isNotBlank()) {
                    appendLine("Earlier conversation, for resolving references:")
                    appendLine(recentContext)
                    appendLine()
                }
                appendLine("Latest user message to classify:")
                append(latestUser.content)
            },
            images = latestUser.images
        )
        repeat(MAX_ROUTE_ATTEMPTS) { attempt ->
            val routeRequest = request.copy(
                messages = listOf(
                    AgentMessage.System(
                        ROUTE_INSTRUCTIONS + if (attempt == 0) "" else
                            "\nYour previous routing output was invalid. Return one complete JSON object with the exact required fields."
                    )
                ) + routingInput,
                tools = emptyList(),
                responseFormat = if (nativeJsonMode) ModelResponseFormat.JSON_OBJECT else ModelResponseFormat.TEXT,
                isFirstModelRequest = false
            )
            val response = delegate.generate(routeRequest)
            parseRoute(response)?.let { return it }
        }
        throw IllegalStateException("模型未能给出有效的任务判断；未创建定时任务，请重试。")
    }

    private fun parseRoute(response: ModelResponse): Route? {
        if (response.toolCalls.isNotEmpty() || response.stopReason == "length" ||
            response.stopReason == "max_tokens"
        ) return null
        val value = runCatching { Json.parseToJsonElement(response.content.trim()) }.getOrNull()
            as? JsonObject ?: return null
        val kind = (value["route"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return when (kind) {
            "continue" -> Route.Continue
            "delay", "repeat" -> {
                val repeating = kind == "repeat"
                val secondsField = if (repeating) "intervalSeconds" else "delaySeconds"
                val seconds = (value[secondsField] as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.longOrNull
                    ?.takeIf { it in 1L..86_400L } ?: return null
                val instruction = (value["instruction"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 2_000 } ?: return null
                Route.Timed(seconds, instruction, repeating)
            }
            else -> null
        }
    }

    private sealed interface Route {
        data object Continue : Route
        data class Timed(
            val seconds: Long,
            val instruction: String,
            val repeating: Boolean
        ) : Route {
            fun asModelResponse(): ModelResponse = ModelResponse(
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "route_${UUID.randomUUID()}",
                        name = DELAY_TOOL_NAME,
                        input = kotlinx.serialization.json.buildJsonObject {
                            put("delaySeconds", kotlinx.serialization.json.JsonPrimitive(seconds))
                            put("instruction", kotlinx.serialization.json.JsonPrimitive(instruction))
                            put("repeating", kotlinx.serialization.json.JsonPrimitive(repeating))
                        }
                    )
                ),
                stopReason = if (repeating) "model_routed_repeat" else "model_routed_delay"
            )
        }
    }

    private companion object {
        const val DELAY_TOOL_NAME = "demo_delay_propose"
        const val MAX_ROUTE_ATTEMPTS = 2
        val ROUTE_INSTRUCTIONS = """
            You are the intent router for a phone Agent. Read the latest actual user message in its conversation context and decide whether it asks for one action after a relative delay or an indefinite action at a fixed interval.
            Return exactly one JSON object, with no prose or Markdown.
            For a clear, one-time delayed action within 1 second to 24 hours, return {"route":"delay","delaySeconds":60,"instruction":"the action to perform when the timer expires"} with the requested duration converted to integer seconds. Preserve the user's intended action; do not perform it now.
            For a clear indefinite recurring action at a fixed interval from 1 second to 24 hours, return {"route":"repeat","intervalSeconds":300,"instruction":"the action to perform on every occurrence"}. The first run happens one interval after the user confirms; later runs continue at the same cadence until the user manually stops. Phrases such as "from now on, every 5 minutes" or "从现在开始，每5分钟帮我做这件事" mean repeat with the first run after 5 minutes, not an immediate run.
            For every other request, including ambiguous timing, missing action, cron/calendar schedules, a finite repeat count, an automatic end condition, and ordinary immediate work, return {"route":"continue"}. The Agent will handle that request normally.
            Classify the user's meaning, not a fixed list of words. The app will ask for confirmation only after a valid delay result. Do not claim that any task has already been scheduled.
        """.trimIndent()
    }
}
