package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Demo-only single-conversation timer; the user must approve before it starts. */
internal class DemoDelayAgentPlugin(
    private val controller: DemoDelayedTaskController
) : AgentCapabilityPlugin {
    override val id: String = "demo-delayed-conversation"

    override fun tools(): List<AgentTool> = listOf(ProposeDelayTool(controller))

    override fun skills(): List<AndroidSkill> = emptyList()

    override fun agentInstructions(): List<String> = listOf(
        """
            For a one-time delayed request or an indefinite fixed-interval request, call demo_delay_propose before doing the requested work. Set repeating=true only for an explicit recurring request.
            Preserve the user's intended action in instruction. Do not perform it now or claim it is scheduled until the app confirms it.
            A successful proposal ends this Agent turn. The app sends the approved action as a new message in this same conversation after the first interval. For a repeating task, each later full interval starts after the previous Agent turn finishes and its result is saved, until the user stops it. Runs never overlap or catch up in a burst.
            Only one timed task can occupy the conversation. Do not propose a timer for cron expressions, finite repeat counts or automatic end conditions; explain the limitation instead.
        """.trimIndent()
    )
}

private class ProposeDelayTool(
    private val controller: DemoDelayedTaskController
) : AgentTool {
    override val name: String = "demo_delay_propose"
    override val description: String =
        "Proposes a one-time or repeating action in this same conversation. The app asks the user to confirm before starting the timer."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("delaySeconds") {
                put("type", "integer")
                put("description", "Seconds until the first run after confirmation, then the full interval after each repeating run finishes and its result is saved.")
                put("minimum", 1)
                put("maximum", 86400)
            }
            putJsonObject("repeating") {
                put("type", "boolean")
                put("description", "True only when the user explicitly wants this action repeated until manually stopped; defaults to false.")
            }
            putJsonObject("instruction") {
                put("type", "string")
                put("description", "The exact actionable request to send as a new message when the delay expires.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("delaySeconds"))
            add(JsonPrimitive("instruction"))
        }
        put("additionalProperties", false)
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (context.runSource != AgentRunSource.USER) {
            return error(call, "Only a direct user message can propose a delayed task.")
        }
        if (call.input.keys.any { it !in setOf("delaySeconds", "instruction", "repeating") }) {
            return error(call, "Unknown timer proposal field.")
        }
        val durationValue = call.input["delaySeconds"] as? JsonPrimitive
        val delaySeconds = durationValue?.takeUnless { it.isString }?.longOrNull
            ?: return error(call, "delaySeconds must be an integer.")
        if (delaySeconds !in 1L..86400L) {
            return error(call, "The timer accepts intervals from 1 second to 24 hours.")
        }
        val repeating = when (val value = call.input["repeating"]) {
            null -> false
            is JsonPrimitive -> value.takeUnless { it.isString }?.booleanOrNull
                ?: return error(call, "repeating must be a boolean.")
            else -> return error(call, "repeating must be a boolean.")
        }
        val instructionValue = call.input["instruction"] as? JsonPrimitive
        val instruction = instructionValue?.takeIf { it.isString }
            ?.contentOrNull?.trim().orEmpty()
        if (instruction.isBlank() || instruction.length > 2000) {
            return error(call, "instruction must contain 1 to 2000 characters.")
        }
        val proposed = controller.propose(context.sessionId, instruction, delaySeconds, repeating)
            .getOrElse { return error(call, it.message ?: "Unable to propose a delayed task.") }
        val description = if (proposed.repeating) {
            "请确认：每 ${proposed.delaySeconds} 秒执行「${proposed.instruction}」，直到手动停止；首次在确认后一个间隔执行。"
        } else {
            "请确认：${proposed.delaySeconds} 秒后执行「${proposed.instruction}」。"
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = description,
            metadata = buildJsonObject {
                put("terminalForTurn", true)
                put("assistantMessage", description)
                put("proposalId", proposed.id)
            }
        )
    }

    private fun error(call: ToolCall, message: String) = ToolResult(
        toolCallId = call.id,
        name = name,
        content = message,
        isError = true
    )
}
