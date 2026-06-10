package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConfirmationDialogToolTest {
    @Test
    fun returnsSelectedButtonFromPresenter() = runBlocking {
        val presenter = FakePresenter(selectedButtonId = "confirm")
        val tool = UserConfirmationDialogTool(presenter)

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
                        )
                    )
                )
            ),
            ToolExecutionContext(sessionId = "s1")
        )

        assertEquals(
            UserConfirmationDialogRequest(
                title = "Open camera?",
                message = "This will leave the current screen.",
                buttons = listOf(
                    UserConfirmationDialogButton("confirm", "Continue"),
                    UserConfirmationDialogButton("cancel", "Cancel")
                )
            ),
            presenter.requests.single()
        )
        assertTrue(result.content.contains("\"selectedButtonId\":\"confirm\""))
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
