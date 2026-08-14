package com.ugk.pi.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class UserConfirmationRequiredTool(
    private val delegate: AgentTool,
    private val acceptedButtonIds: Set<String> = setOf("confirm", "continue", "ok", "yes", "allow"),
    private val shouldBypassConfirmation: () -> Boolean = { false },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AgentTool {
    override val name: String = delegate.name
    override val description: String =
        "${delegate.description} Requires a prior show_user_confirmation_dialog confirmation."
    override val inputSchema: JsonObject = delegate.inputSchema

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        if (shouldBypassConfirmation()) {
            return delegate.execute(call, context)
        }
        if (!context.hasImmediateUserConfirmation(call)) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "User confirmation required for this exact Tool input. Call show_user_confirmation_dialog with target.toolName and the exact target.input first, then retry only with an unexpired ticket and an accepted selectedButtonId from ${acceptedButtonIds.sorted()}.",
                isError = true
            )
        }

        return delegate.execute(call, context)
    }

    private fun ToolExecutionContext.hasImmediateUserConfirmation(call: ToolCall): Boolean {
        val result = (priorMessages.lastOrNull() as? AgentMessage.Tool)?.result
            ?: return false
        if (result.name != "show_user_confirmation_dialog" || result.isError) return false

        val confirmation = runCatching {
            Json.parseToJsonElement(result.content).jsonObject
        }.getOrNull() ?: return false
        val selectedButtonId = confirmation.stringField("selectedButtonId")
            ?: return false
        if (selectedButtonId !in acceptedButtonIds) return false

        val ticket = (confirmation["ticket"] as? JsonObject)
            ?.toTicketOrNull()
            ?: return false
        if (!ticket.isStructurallyValid(nowEpochMillis())) return false
        if (ticket.sessionId != sessionId || ticket.toolName != call.name) return false

        val inputFingerprint = runCatching {
            UserConfirmationInputFingerprint.sha256(call.input)
        }.getOrNull() ?: return false
        return ticket.inputFingerprint == inputFingerprint
    }

    private fun JsonObject.stringField(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return value.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.toTicketOrNull(): UserConfirmationTicket? {
        val version = numericField("version")?.toIntOrNull() ?: return null
        val sessionId = stringField("sessionId") ?: return null
        val toolName = stringField("toolName") ?: return null
        val inputFingerprint = stringField("inputFingerprint") ?: return null
        val nonce = stringField("nonce") ?: return null
        val issuedAtEpochMillis = numericField("issuedAtEpochMillis")?.toLongOrNull() ?: return null
        val expiresAtEpochMillis = numericField("expiresAtEpochMillis")?.toLongOrNull() ?: return null
        return UserConfirmationTicket(
            version = version,
            sessionId = sessionId,
            toolName = toolName,
            inputFingerprint = inputFingerprint,
            nonce = nonce,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis
        )
    }

    private fun JsonObject.numericField(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        if (value.isString) return null
        return value.contentOrNull
    }
}
