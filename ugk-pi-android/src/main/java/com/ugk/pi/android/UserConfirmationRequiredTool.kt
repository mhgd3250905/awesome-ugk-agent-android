package com.ugk.pi.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class UserConfirmationRequiredTool(
    private val delegate: AgentTool,
    private val acceptedButtonIds: Set<String> = setOf("confirm", "continue", "ok", "yes", "allow"),
    private val declinedButtonIds: Set<String> = setOf("cancel", "deny", "no", "reject", "decline", "stop"),
    private val shouldBypassConfirmation: () -> Boolean = { false },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AgentTool {
    override val name: String = delegate.name
    override val description: String
        get() = if (shouldBypassConfirmation()) {
            "${delegate.description} The host has enabled full authorization for this session; do not call ${USER_CONFIRMATION_DIALOG_TOOL_NAME} before this Tool."
        } else {
            "${delegate.description} Requires a prior ${USER_CONFIRMATION_DIALOG_TOOL_NAME} confirmation."
        }
    override val inputSchema: JsonObject = delegate.inputSchema

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        if (shouldBypassConfirmation()) {
            return delegate.execute(call, context)
        }
        if (!context.hasImmediateUserConfirmation(call)) {
            // A refusal must not be answered with the same wording as a missing
            // confirmation: telling the model to "call show_user_confirmation_dialog
            // and then retry" turns the user's "no" into another modal prompt on the
            // same action, and the user can only escape by answering a dialog that
            // was already answered.
            val refusal = context.declinedConfirmationFor(call)
            val unanswered = context.unresolvedConfirmationFor(call)
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = when {
                    refusal -> {
                        "The user declined this exact operation ($name) at the confirmation " +
                            "dialog. Do not call ${USER_CONFIRMATION_DIALOG_TOOL_NAME} again for it " +
                            "and do not retry it in this run; continue without it or ask the " +
                            "user a new question."
                    }
                    // Nothing decided this, and asking again cannot reach a user
                    // that was unreachable the first time: the missing-confirmation
                    // wording below would send the model straight back to the same
                    // dialog that just closed without an answer.
                    unanswered -> {
                        "The confirmation dialog for this operation ($name) closed without " +
                            "a user decision. Do not call ${USER_CONFIRMATION_DIALOG_TOOL_NAME} " +
                            "again for it in this run; continue without it or report that " +
                            "the user could not be reached."
                    }
                    else -> {
                        "User confirmation required for this exact Tool input. Call " +
                            "${USER_CONFIRMATION_DIALOG_TOOL_NAME} with target.toolName and the exact " +
                            "target.input first, then retry only with an unexpired ticket and " +
                            "an accepted selectedButtonId from ${acceptedButtonIds.sorted()}."
                    }
                },
                isError = true
            )
        }

        return delegate.execute(call, context)
    }

    /**
     * The dialog result that still owns the current confirmation context for
     * [call], or null when nothing confirms or refuses it.
     *
     * Both the accepted and the refused decision read this single view, so neither
     * one can be judged against a dialog that the conversation already moved past.
     */
    private fun ToolExecutionContext.immediateDialogResult(call: ToolCall): JsonObject? {
        val lastToolIndex = priorMessages.indexOfLast { it is AgentMessage.Tool }
        if (lastToolIndex < 0) return null

        val result = (priorMessages[lastToolIndex] as? AgentMessage.Tool)?.result
            ?: return null
        if (result.name != USER_CONFIRMATION_DIALOG_TOOL_NAME || result.isError) return null

        // AgentRuntime appends the model's Assistant(toolCalls) envelope before
        // executing that response's ToolCall. It is transport context, not a
        // new action. Allow exactly that envelope when it contains this exact
        // call; any user/system message or additional ToolResult invalidates
        // the confirmation.
        val messagesAfterConfirmation = priorMessages.drop(lastToolIndex + 1)
        if (messagesAfterConfirmation.size > 1) return null
        val assistantEnvelope = messagesAfterConfirmation.singleOrNull()
            as? AgentMessage.Assistant
        if (messagesAfterConfirmation.isNotEmpty() &&
            assistantEnvelope?.toolCalls?.any {
                it.name == call.name && it.input == call.input
            } != true
        ) {
            return null
        }

        return runCatching {
            Json.parseToJsonElement(result.content).jsonObject
        }.getOrNull()
    }

    /**
     * True only when the user pressed a decline button for this exact protected
     * call.
     *
     * A selection outside the accepted set is deliberately not enough: an
     * unrecognized id (for example `approve`) says nothing about what the user
     * meant, and telling the model "the user declined" would then block an action
     * the user actually authorized. Only an id that means refusal, resolved while
     * the dialog was still the current context and bound to this Tool by its
     * ticket, is reported as a refusal.
     *
     * The ticket's input fingerprint must match this call's input as well: the
     * user declined the exact input the dialog showed. Without this, declining
     * input A would also report "the user declined this exact operation" for a
     * different input B, and the model would never seek the confirmation that
     * B actually needs.
     */
    private fun ToolExecutionContext.declinedConfirmationFor(call: ToolCall): Boolean {
        val confirmation = immediateDialogResult(call) ?: return false
        val selectedButtonId = confirmation.stringField("selectedButtonId")
            ?: return false
        if (selectedButtonId !in declinedButtonIds) return false
        if (confirmation.booleanField("withoutUserDecision")) return false
        val ticket = (confirmation["ticket"] as? JsonObject)?.toTicketOrNull()
            ?: return false
        if (ticket.sessionId != sessionId || ticket.toolName != call.name) return false
        val inputFingerprint = runCatching {
            UserConfirmationInputFingerprint.sha256(call.input)
        }.getOrNull() ?: return false
        return ticket.inputFingerprint == inputFingerprint
    }

    /**
     * True when a dialog did appear for this Tool and closed with the host
     * declaring that no user decided anything (`withoutUserDecision`).
     *
     * Neither an authorization nor a refusal, but it does answer the question
     * "should the model ask again?", which the plain missing-confirmation
     * wording cannot. Like the refusal path, this is bound to the exact input
     * the dialog showed: an unanswered dialog for input A must not suppress
     * the confirmation prompt for a different input B.
     */
    private fun ToolExecutionContext.unresolvedConfirmationFor(call: ToolCall): Boolean {
        val confirmation = immediateDialogResult(call) ?: return false
        if (!confirmation.booleanField("withoutUserDecision")) return false
        val ticket = (confirmation["ticket"] as? JsonObject)?.toTicketOrNull()
            ?: return false
        if (ticket.sessionId != sessionId || ticket.toolName != call.name) return false
        val inputFingerprint = runCatching {
            UserConfirmationInputFingerprint.sha256(call.input)
        }.getOrNull() ?: return false
        return ticket.inputFingerprint == inputFingerprint
    }

    private fun ToolExecutionContext.hasImmediateUserConfirmation(call: ToolCall): Boolean {
        val confirmation = immediateDialogResult(call) ?: return false
        val selectedButtonId = confirmation.stringField("selectedButtonId")
            ?: return false
        if (selectedButtonId !in acceptedButtonIds) return false
        // An accepted id is only authorization if somebody actually chose it. The
        // host can resolve a dialog on its own (destroyed window, no UI present),
        // and the ticket it issues is well-formed for exactly that input, so the
        // remaining checks cannot tell a fabricated decision from a real one.
        if (confirmation.booleanField("withoutUserDecision")) return false

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

    private fun JsonObject.booleanField(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

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
