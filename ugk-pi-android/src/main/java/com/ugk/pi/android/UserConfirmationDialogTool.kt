package com.ugk.pi.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class UserConfirmationDialogRequest(
    val title: String,
    val message: String,
    val buttons: List<UserConfirmationDialogButton>
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
    override val name: String = "show_user_confirmation_dialog"
) : AgentTool {
    override val description: String =
        "Shows a user confirmation dialog and returns the selected button id."

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
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("selectedButtonId", result.selectedButtonId)
            }.toString()
        )
    }

    private fun ToolCall.toDialogRequest(): UserConfirmationDialogRequest? {
        val title = input["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val message = input["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val buttons = input["buttons"]
            ?.jsonArray
            ?.mapNotNull { it.toDialogButtonOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return UserConfirmationDialogRequest(title, message, buttons)
    }

    private fun kotlinx.serialization.json.JsonElement.toDialogButtonOrNull(): UserConfirmationDialogButton? {
        val button = this as? JsonObject ?: return null
        val id = button["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val label = button["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        return UserConfirmationDialogButton(id, label)
    }
}
