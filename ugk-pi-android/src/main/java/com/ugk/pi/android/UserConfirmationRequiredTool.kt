package com.ugk.pi.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UserConfirmationRequiredTool(
    private val delegate: AgentTool,
    private val acceptedButtonIds: Set<String> = setOf("confirm", "continue", "ok", "yes", "allow")
) : AgentTool {
    override val name: String = delegate.name
    override val description: String =
        "${delegate.description} Requires a prior show_user_confirmation_dialog confirmation."
    override val inputSchema: JsonObject = delegate.inputSchema

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        if (!context.hasImmediateUserConfirmation()) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "User confirmation required. Call show_user_confirmation_dialog first, then retry only if selectedButtonId is one of ${acceptedButtonIds.sorted()}.",
                isError = true
            )
        }

        return delegate.execute(call, context)
    }

    private fun ToolExecutionContext.hasImmediateUserConfirmation(): Boolean {
        val result = priorMessages
            .filterIsInstance<AgentMessage.Tool>()
            .lastOrNull()
            ?.result
            ?: return false
        if (result.name != "show_user_confirmation_dialog" || result.isError) return false

        val selectedButtonId = runCatching {
            Json.parseToJsonElement(result.content)
                .jsonObject["selectedButtonId"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

        return selectedButtonId in acceptedButtonIds
    }
}
