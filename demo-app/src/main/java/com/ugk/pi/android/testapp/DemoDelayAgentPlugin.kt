package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillMethod
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Demo-only one-shot delay; the user must approve before the timer starts. */
internal class DemoDelayAgentPlugin(
    private val controller: DemoDelayedTaskController
) : AgentCapabilityPlugin {
    override val id: String = "demo-delayed-conversation"

    override fun tools(): List<AgentTool> = listOf(ProposeDelayTool(controller))

    override fun skills(): List<AndroidSkill> = listOf(
        AndroidSkill(
            id = id,
            description = "Propose one delayed action in the current conversation after the user asks to do something later.",
            triggers = listOf(
                "以后", "之后", "分钟后", "秒后", "小时后", "稍后", "过一会", "定时", "提醒",
                "later", "minutes", "hours", "after"
            ),
            instructions = """
                When the user asks to perform an action after a relative delay, call demo_delay_propose with delaySeconds and the action to perform later.
                Do not perform the delayed action now. The app will show the exact action and delay to the user and start the timer only after explicit confirmation.
                The current Agent turn ends after a successful proposal. At the deadline the app sends the approved action as a new message in this same conversation.
                Only one delayed task can occupy the conversation. Repeating schedules and cron expressions are not supported here; explain this if requested.
            """.trimIndent(),
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "demo_delay_propose",
                    purpose = "Asks the app to confirm one delayed action in the current conversation.",
                    whenToUse = "The user requests one future action after a relative delay.",
                    resultSemantics = "A successful proposal ends this Agent turn; the app handles confirmation and later sends a fresh message."
                )
            )
        )
    )

    override fun agentInstructions(): List<String> = listOf(
        "For a one-time delayed request, use demo_delay_propose before doing the requested work. Preserve the user's intended action in instruction; do not claim it is scheduled until the app confirms it."
    )
}

private class ProposeDelayTool(
    private val controller: DemoDelayedTaskController
) : AgentTool {
    override val name: String = "demo_delay_propose"
    override val description: String =
        "Proposes one action to run later in this same conversation. The app asks the user to confirm before starting the delay."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("delaySeconds") {
                put("type", "integer")
                put("description", "Relative delay in seconds, starting after the user confirms.")
                put("minimum", 1)
                put("maximum", 86400)
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
        val durationValue = call.input["delaySeconds"] as? JsonPrimitive
        val delaySeconds = durationValue?.takeUnless { it.isString }?.longOrNull
            ?: return error(call, "delaySeconds must be an integer.")
        if (delaySeconds !in 1L..86400L) {
            return error(call, "The first version accepts delays from 1 second to 24 hours.")
        }
        val instructionValue = call.input["instruction"] as? JsonPrimitive
        val instruction = instructionValue?.takeIf { it.isString }
            ?.contentOrNull?.trim().orEmpty()
        if (instruction.isBlank() || instruction.length > 2000) {
            return error(call, "instruction must contain 1 to 2000 characters.")
        }
        val proposed = controller.propose(context.sessionId, instruction, delaySeconds)
            .getOrElse { return error(call, it.message ?: "Unable to propose a delayed task.") }
        val description = "请确认：${proposed.delaySeconds} 秒后执行「${proposed.instruction}」。"
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
