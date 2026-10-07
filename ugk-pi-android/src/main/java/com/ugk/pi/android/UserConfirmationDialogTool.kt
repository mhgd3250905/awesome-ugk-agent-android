package com.ugk.pi.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Model-visible name of the default confirmation dialog Tool. A host may rename it
 * via the [UserConfirmationDialogTool] constructor, but every wrapping, policy and
 * instruction that references the dialog by name must then agree on the same value —
 * reference this constant instead of repeating the literal.
 */
const val USER_CONFIRMATION_DIALOG_TOOL_NAME: String = "show_user_confirmation_dialog"

data class UserConfirmationDialogRequest(
    val title: String,
    val message: String,
    val buttons: List<UserConfirmationDialogButton>,
    val target: UserConfirmationTarget? = null
)

data class UserConfirmationDialogButton(
    val id: String,
    val label: String
)

/**
 * @param selectedButtonId the id of the button the host resolved with.
 * @param withoutUserDecision true when the host resolved the dialog **without the
 * user deciding anything** — it was dismissed, its window was destroyed, or its
 * owner finished first. Hosts that cannot tell leave it false; a protected Tool
 * then reports the selection as a user refusal only when it really was one.
 */
data class UserConfirmationDialogResult(
    val selectedButtonId: String,
    val withoutUserDecision: Boolean = false
)

interface UserConfirmationDialogPresenter {
    suspend fun showConfirmationDialog(
        request: UserConfirmationDialogRequest
    ): UserConfirmationDialogResult
}

class UserConfirmationDialogTool(
    private val presenter: UserConfirmationDialogPresenter,
    override val name: String = USER_CONFIRMATION_DIALOG_TOOL_NAME,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nonceGenerator: () -> String = { UserConfirmationTicket.randomNonce() }
) : AgentTool {
    override val description: String =
        "Shows a user confirmation dialog for an exact protected Tool input and returns the selected button id plus a short-lived bound ticket."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
            }
            putJsonObject("message") {
                put("type", "string")
            }
            putJsonObject("buttons") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                        }
                        putJsonObject("label") {
                            put("type", "string")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("label"))
                    }
                }
            }
            putJsonObject("target") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("toolName") {
                        put("type", "string")
                    }
                    putJsonObject("input") {
                        put("type", "object")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("toolName"))
                    add(JsonPrimitive("input"))
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("title"))
            add(JsonPrimitive("message"))
            add(JsonPrimitive("buttons"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val parsed = call.toDialogRequest()
        if (parsed is DialogRequestParse.Invalid) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = parsed.reason,
                isError = true
            )
        }
        val request = (parsed as DialogRequestParse.Valid).request

        // Nothing that can be decided without the user may wait for them: an
        // answer already given cannot be re-collected except by raising the modal
        // again on a question the user already left. The nonce is generated here so
        // it is drawn exactly once, and the clock is read again when the ticket is
        // written so the TTL still starts at the answer rather than at the prompt.
        val pending = request.target?.let { target ->
            val nonce = nonceGenerator()
            runCatching {
                UserConfirmationTicket.requireIssuable(
                    sessionId = context.sessionId,
                    target = target,
                    issuedAtEpochMillis = nowEpochMillis(),
                    nonce = nonce
                )
            }.getOrElse {
                return ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "Cannot ask the user about this target: " +
                        (it.message?.take(BOUND_DIAGNOSTIC_CHARS) ?: "invalid target or nonce") + ".",
                    isError = true
                )
            }
            PendingTicket(target = target, nonce = nonce)
        }

        val result = presenter.showConfirmationDialog(request)
        val ticket = pending?.let {
            UserConfirmationTicket.issue(
                sessionId = context.sessionId,
                target = it.target,
                issuedAtEpochMillis = nowEpochMillis(),
                nonce = it.nonce
            )
        }
        if (request.buttons.none { it.id == result.selectedButtonId }) {
            // The dialog is spent and no offered button can be attributed to the
            // user. With a ticket the honest, bound answer is "nobody decided",
            // which a protected Tool reports without sending the model back to the
            // dialog. Without one nothing ties that claim to this exact input, so
            // stay a loud error instead of asserting what the user did not say.
            if (ticket != null) {
                return ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = buildJsonObject {
                        put("withoutUserDecision", true)
                        put("ticket", ticket.toJsonObject())
                    }.toString()
                )
            }
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Dialog returned a button id that was not present in the request, " +
                    "and this dialog carries no confirmation ticket to bind a 'no user decision' " +
                    "answer to one exact Tool input.",
                isError = true
            )
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("selectedButtonId", result.selectedButtonId)
                if (result.withoutUserDecision) {
                    put("withoutUserDecision", true)
                }
                ticket?.let { put("ticket", it.toJsonObject()) }
            }.toString()
        )
    }

    private class PendingTicket(
        val target: UserConfirmationTarget,
        val nonce: String
    )

    private sealed interface DialogRequestParse {
        class Valid(val request: UserConfirmationDialogRequest) : DialogRequestParse
        class Invalid(val reason: String) : DialogRequestParse
    }

    private fun ToolCall.toDialogRequest(): DialogRequestParse {
        val title = input.stringField("title")
            ?: return DialogRequestParse.Invalid(MALFORMED_DIALOG_REQUEST)
        val message = input.stringField("message")
            ?: return DialogRequestParse.Invalid(MALFORMED_DIALOG_REQUEST)
        val buttonsElement = input["buttons"]
        val buttons = buttonsElement
            ?.let { it as? JsonArray }
            ?.mapNotNull { it.toDialogButtonOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return DialogRequestParse.Invalid(
                if (buttonsElement != null && buttonsElement !is JsonArray) {
                    "Dialog buttons must be an array of objects each with a non-blank id and label; " +
                        "the request sent something else in `buttons`."
                } else {
                    MALFORMED_DIALOG_REQUEST
                }
            )

        val target = when (val targetElement = input["target"]) {
            null, JsonNull -> null
            else -> targetElement.toDialogTargetOrNull()
                ?: return DialogRequestParse.Invalid(
                    "Dialog `target` is present but unusable: it must be an object with a non-blank " +
                        "`toolName` and an `input` object. `title`, `message` and `buttons` were valid."
                )
        }
        return DialogRequestParse.Valid(
            UserConfirmationDialogRequest(title, message, buttons, target)
        )
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonElement.toDialogTargetOrNull(): UserConfirmationTarget? {
        val target = this as? JsonObject ?: return null
        val toolName = target.stringField("toolName")
            ?: return null
        val input = target["input"] as? JsonObject ?: return null
        return UserConfirmationTarget(toolName, input)
    }

    private fun kotlinx.serialization.json.JsonElement.toDialogButtonOrNull(): UserConfirmationDialogButton? {
        val button = this as? JsonObject ?: return null
        val id = button.stringField("id")
            ?: return null
        val label = button.stringField("label")
            ?: return null
        return UserConfirmationDialogButton(id, label)
    }

    private companion object {
        const val MALFORMED_DIALOG_REQUEST =
            "Dialog requires title, message, and at least one button with id and label."

        /**
         * An echo of an internal failure message lands in a tool result, a log and
         * the transcript, so it is capped even though every producer here is an
         * SDK-authored literal. Round 13 recorded an unbounded echo on the other arm
         * of this same method.
         */
        const val BOUND_DIAGNOSTIC_CHARS = 200
    }
}
