package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogTool
import com.ugk.pi.android.UserConfirmationRequiredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducers for the round-7 review: `HeadlessConfirmationDialogPresenter`
 * promises to deny protected operations when no UI exists, but when the
 * model-authored button set carries no recognised cancellation id it returned
 * the **last offered button** instead - and the model names that button
 * `confirm`, which is inside the SDK's accepted set. The unattended scheduled
 * task run (`DemoScheduledTaskPromptExecutor`) is exactly the path that uses
 * this presenter, so a background Agent turn could execute protected Tools
 * with nobody asked.
 */
class HeadlessConfirmationAutoApprovalTest {
    @Test
    fun headlessPresenterReportsThatNoUserDecided() = runBlocking {
        // Two button sets that both lack a cancellation button. The old fallback
        // answered the second one with its last button (`review_later`, which
        // reads as neither yes nor no) and the first with `confirm`, an accepted
        // id; neither said honestly that nobody was asked.
        listOf(
            listOf(UserConfirmationDialogButton("confirm", "确认")),
            listOf(
                UserConfirmationDialogButton("confirm", "确认"),
                UserConfirmationDialogButton("review_later", "稍后")
            )
        ).forEach { buttons ->
            val result = HeadlessConfirmationDialogPresenter.showConfirmationDialog(
                UserConfirmationDialogRequest(title = "确认", message = "执行操作", buttons = buttons)
            )

            assertTrue(
                "no UI means nobody decided, whatever id the model offered: $result",
                result.withoutUserDecision
            )
            assertFalse(
                "an offered non-accepting button must be preferred over an accepting one",
                buttons.last().id in ACCEPTED && result.selectedButtonId == buttons.last().id &&
                    buttons.any { it.id !in ACCEPTED }
            )
        }
    }

    @Test
    fun singleConfirmButtonDialogDoesNotAuthorizeTheProtectedTool() = runBlocking {
        val delegate = RecordingTool()
        val protectedInput = buildJsonObject { put("command", "erase_everything") }
        val dialogInput = buildJsonObject {
            put("title", "确认")
            put("message", "是否执行该操作？")
            put(
                "buttons",
                kotlinx.serialization.json.JsonArray(
                    listOf(buildJsonObject {
                        put("id", "confirm")
                        put("label", "确认")
                    })
                )
            )
            putJsonObject("target") {
                put("toolName", delegate.name)
                put("input", protectedInput)
            }
        }

        // Step 1: the Agent does exactly what the SDK instructs - ask the user
        // through the dialog Tool, binding the target input.
        val dialog = UserConfirmationDialogTool(
            presenter = HeadlessConfirmationDialogPresenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )
        val dialogResult = dialog.execute(
            ToolCall("dialog-1", dialog.name, dialogInput),
            ToolExecutionContext(sessionId = SESSION)
        )
        assertFalse("dialog tool itself must not error", dialogResult.isError)

        // Step 2: the protected Tool runs immediately after that result.
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val outcome = tool.execute(
            ToolCall("protected-1", delegate.name, protectedInput),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(AgentMessage.Tool(dialogResult))
            )
        )

        assertTrue(
            "an unattended run must not execute the protected Tool, it returned: ${outcome.content}",
            outcome.isError
        )
        assertFalse(
            "the protected Tool executed with no user in the loop",
            delegate.executed
        )
    }

    @Test
    fun headlessPresenterStillDeniesWhenACancellationButtonExists() = runBlocking {
        val result = HeadlessConfirmationDialogPresenter.showConfirmationDialog(
            UserConfirmationDialogRequest(
                title = "确认",
                message = "执行操作",
                buttons = listOf(
                    UserConfirmationDialogButton("confirm", "确认"),
                    UserConfirmationDialogButton("cancel", "取消")
                )
            )
        )

        // Unchanged on purpose: with a real cancellation button available the
        // answer stays a plain refusal rather than `withoutUserDecision`, so the
        // model keeps the round-6 "do not ask again" wording instead of being
        // told to open the dialog once more.
        assertEquals("cancel", result.selectedButtonId)
        assertFalse(result.withoutUserDecision)
    }

    private class RecordingTool : AgentTool {
        var executed = false
        override val name: String = "dangerous_tool"
        override val description: String = "Test protected tool."
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            executed = true
            return ToolResult(call.id, name, "executed")
        }
    }

    private companion object {
        val ACCEPTED = setOf("confirm", "continue", "ok", "yes", "allow")
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
