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

data class UserConfirmationDialogResult(
    val selectedButtonId: String
)

interface UserConfirmationDialogPresenter {
    suspend fun showConfirmationDialog(
        request: UserConfirmationDialogRequest
    ): UserConfirmationDialogResult
}

class UserConfirmationDialogTool(
    private val presenter: UserConfirmationDialogPresenter,
    override val name: String = "show_user_confirmation_dialog",
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
        val request = call.toDialogRequest() ?: return ToolResult(
            toolCallId = call.id,
            name = name,
            content = "Dialog requires title, message, and at least one button with id and label.",
            isError = true
        )
        val result = presenter.showConfirmationDialog(request)
        if (request.buttons.none { it.id == result.selectedButtonId }) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Dialog returned a button id that was not present in the request.",
                isError = true
            )
        }
        val ticket = request.target?.let { target ->
            runCatching {
                UserConfirmationTicket.issue(
                    sessionId = context.sessionId,
                    target = target,
                    issuedAtEpochMillis = nowEpochMillis(),
                    nonce = nonceGenerator()
                )
            }.getOrElse {
                return ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "Unable to issue a confirmation ticket: " +
                        (it.message ?: "invalid target or nonce") + ".",
                    isError = true
                )
            }
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("selectedButtonId", result.selectedButtonId)
                ticket?.let { put("ticket", it.toJsonObject()) }
            }.toString()
        )
    }

    private fun ToolCall.toDialogRequest(): UserConfirmationDialogRequest? {
        val title = input.stringField("title")
            ?: return null
        val message = input.stringField("message")
            ?: return null
        val buttons = input["buttons"]
            ?.let { it as? JsonArray }
            ?.mapNotNull { it.toDialogButtonOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val target = when (val targetElement = input["target"]) {
            null, JsonNull -> null
            else -> targetElement.toDialogTargetOrNull() ?: return null
        }
        return UserConfirmationDialogRequest(title, message, buttons, target)
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
}
