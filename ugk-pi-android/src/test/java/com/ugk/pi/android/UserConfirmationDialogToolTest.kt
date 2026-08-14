package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConfirmationDialogToolTest {
    @Test
    fun returnsSelectedButtonFromPresenter() = runBlocking {
        val presenter = FakePresenter(selectedButtonId = "confirm")
        val tool = UserConfirmationDialogTool(
            presenter = presenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )
        val targetInput = buildJsonObject {
            put("target", "open_url")
            put("url", "https://example.com")
        }

        val result = tool.execute(
            ToolCall(
                id = "dialog-1",
                name = tool.name,
                input = JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Open camera?"),
                        "message" to JsonPrimitive("This will leave the current screen."),
                        "buttons" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("confirm"),
                                        "label" to JsonPrimitive("Continue")
                                    )
                                ),
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("cancel"),
                                        "label" to JsonPrimitive("Cancel")
                                    )
                                )
                            )
                        ),
                        "target" to buildJsonObject {
                            put("toolName", "launch_android_app_intent")
                            put("input", targetInput)
                        }
                    )
                )
            ),
            ToolExecutionContext(sessionId = SESSION)
        )

        assertEquals(
            UserConfirmationDialogRequest(
                title = "Open camera?",
                message = "This will leave the current screen.",
                buttons = listOf(
                    UserConfirmationDialogButton("confirm", "Continue"),
                    UserConfirmationDialogButton("cancel", "Cancel")
                ),
                target = UserConfirmationTarget("launch_android_app_intent", targetInput)
            ),
            presenter.requests.single()
        )
        assertTrue(result.content.contains("\"selectedButtonId\":\"confirm\""))
        val ticket = Json.parseToJsonElement(result.content).jsonObject["ticket"]?.jsonObject
        assertNotNull(ticket)
        assertEquals(1, ticket?.get("version")?.toString()?.toInt())
        assertEquals(SESSION, ticket?.get("sessionId")?.toString()?.trim('"'))
        assertEquals("launch_android_app_intent", ticket?.get("toolName")?.toString()?.trim('"'))
        assertEquals(
            UserConfirmationInputFingerprint.sha256(targetInput),
            ticket?.get("inputFingerprint")?.toString()?.trim('"')
        )
        assertEquals(NONCE, ticket?.get("nonce")?.toString()?.trim('"'))
        assertEquals(NOW + UserConfirmationTicket.DEFAULT_TTL_MILLIS, ticket?.get("expiresAtEpochMillis")?.toString()?.toLong())
    }

    @Test
    fun rejectsDialogWithoutButtons() = runBlocking {
        val tool = UserConfirmationDialogTool(FakePresenter(selectedButtonId = "confirm"))

        val result = tool.execute(
            ToolCall(
                id = "dialog-1",
                name = tool.name,
                input = JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Open camera?"),
                        "message" to JsonPrimitive("This will leave the current screen.")
                    )
                )
            ),
            ToolExecutionContext(sessionId = "s1")
        )

        assertTrue(result.isError)
    }

    @Test
    fun legacyRequestWithoutTargetDoesNotProduceExecutableTicket() = runBlocking {
        val tool = UserConfirmationDialogTool(
            presenter = FakePresenter(selectedButtonId = "confirm"),
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )

        val result = tool.execute(
            ToolCall(
                id = "dialog-legacy",
                name = tool.name,
                input = buildJsonObject {
                    put("title", "Legacy")
                    put("message", "Legacy request")
                    putJsonButtons()
                }
            ),
            ToolExecutionContext(sessionId = SESSION)
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("selectedButtonId"))
        assertFalse(result.content.contains("ticket"))
    }

    @Test
    fun rejectsPresenterButtonThatWasNotRequested() = runBlocking {
        val tool = UserConfirmationDialogTool(
            presenter = FakePresenter(selectedButtonId = "unexpected"),
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )

        val result = tool.execute(
            ToolCall(
                id = "dialog-invalid-selection",
                name = tool.name,
                input = buildJsonObject {
                    put("title", "Confirm")
                    put("message", "Confirm the operation")
                    putJsonButtons()
                    put("target", buildJsonObject {
                        put("toolName", "launch_android_app_intent")
                        put("input", buildJsonObject { put("packageName", "com.example") })
                    })
                }
            ),
            ToolExecutionContext(sessionId = SESSION)
        )

        assertTrue(result.isError)
        assertFalse(result.content.contains("ticket"))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonButtons() {
        put("buttons", JsonArray(listOf(
            JsonObject(mapOf(
                "id" to JsonPrimitive("confirm"),
                "label" to JsonPrimitive("Continue")
            ))
        )))
    }

    private companion object {
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }

    private class FakePresenter(
        private val selectedButtonId: String
    ) : UserConfirmationDialogPresenter {
        val requests = mutableListOf<UserConfirmationDialogRequest>()

        override suspend fun showConfirmationDialog(
            request: UserConfirmationDialogRequest
        ): UserConfirmationDialogResult {
            requests += request
            return UserConfirmationDialogResult(selectedButtonId)
        }
    }
}
