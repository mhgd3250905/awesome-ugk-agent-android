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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmation gate asks a human to decide, and that decision cannot be
 * re-collected cheaply: the user has to read a modal again.
 *
 * Every check in [UserConfirmationDialogTool.execute] that does not depend on
 * the answer must therefore run *before* the presenter is called. Two of them
 * did not, so a blank session id or a target number too large to canonicalize
 * burned the user's answer and left the protected Tool answering with the
 * "call the dialog first, then retry" wording - a second modal for a question
 * that was already answered.
 *
 * The cases are driven through the real [UserConfirmationRequiredTool] as well,
 * because "the answer was not turned into an authorization" is the half a
 * wording change can silently break.
 */
class UserConfirmationAnswerConsumedBeforeValidationTest {

    @Test
    fun anUnissuableTargetIsRefusedBeforeTheUserIsAsked() = runBlocking {
        // `AgentSession` accepts a blank id, and `UserConfirmationTicket.issue`
        // requires a non-blank one: the require used to fire after the modal.
        val presenter = RecordingPresenter(selectedButtonId = "confirm")
        val tool = UserConfirmationDialogTool(
            presenter = presenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )

        val result = tool.execute(dialogCall(targetInput = simpleTarget()), ToolExecutionContext(sessionId = ""))

        assertTrue("an unissuable target must still be an error, got: ${result.content}", result.isError)
        assertEquals(
            "the user must not be shown a dialog whose answer can never be honored",
            0,
            presenter.requests.size
        )
    }

    @Test
    fun aTargetNumberTooLargeToCanonicalizeIsRefusedBeforeTheUserIsAsked() = runBlocking {
        // Reachable from a model-authored tool input, not only from a host: this
        // is valid JSON the parser accepts, and canonical-json-v1 refuses it.
        val oversized = Json.parseToJsonElement("""{"count":1e100000}""") as JsonObject
        // Pin the trigger itself, so the assertion below cannot be red for an
        // unrelated reason: canonical-json-v1 must be what refuses this value.
        assertTrue(
            "canonical-json-v1 must refuse this number, or the case proves nothing",
            runCatching { UserConfirmationInputFingerprint.sha256(oversized) }.exceptionOrNull() != null
        )
        val presenter = RecordingPresenter(selectedButtonId = "confirm")
        val tool = UserConfirmationDialogTool(
            presenter = presenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )

        val result = tool.execute(dialogCall(targetInput = oversized), ToolExecutionContext(sessionId = SESSION))

        assertTrue("expected an error for a target that cannot be fingerprinted", result.isError)
        assertEquals(
            "the modal must not be raised for a target that cannot produce a ticket",
            0,
            presenter.requests.size
        )
    }

    @Test
    fun aButtonTheRequestNeverOfferedIsReportedAsNoDecisionAndNotAsMissingConfirmation() = runBlocking {
        // A dismissal can resolve the dialog with an id the model did not offer
        // (the demo presenter's fallback did exactly that). The answer is spent;
        // answering "user confirmation required, call the dialog and retry"
        // raises the modal again on top of a question the user already left.
        val buttons = listOf(UserConfirmationDialogButton("allow_proceed", "Proceed"))
        val presenter = RecordingPresenter(selectedButtonId = "cancel")
        val targetInput = simpleTarget()
        val tool = UserConfirmationDialogTool(
            presenter = presenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )

        val dialogResult = tool.execute(
            dialogCall(buttons = buttons, targetInput = targetInput),
            ToolExecutionContext(sessionId = SESSION)
        )

        assertFalse(
            "a spent answer must not be an error that reads as 'no confirmation yet': ${dialogResult.content}",
            dialogResult.isError
        )
        val parsed = Json.parseToJsonElement(dialogResult.content).jsonObject
        assertTrue("must say nobody decided: ${dialogResult.content}", parsed.containsKey("withoutUserDecision"))
        assertTrue(
            "must not hand back an id the request never offered: ${dialogResult.content}",
            !parsed.containsKey("selectedButtonId")
        )

        // The safety half: the same dialog result must still not authorize the tool.
        val delegate = RecordingTool()
        val protectedTool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val protectedResult = protectedTool.execute(
            ToolCall("call-1", protectedTool.name, targetInput),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(
                        ToolResult(
                            toolCallId = "dialog-1",
                            name = USER_CONFIRMATION_DIALOG_TOOL_NAME,
                            content = dialogResult.content,
                            isError = false
                        )
                    )
                )
            )
        )

        assertFalse("an unoffered button id must never run the protected tool", delegate.executed)
        assertTrue(
            "the refusal must not send the model back to the dialog: ${protectedResult.content}",
            !protectedResult.content.contains("then retry")
        )
    }

    @Test
    fun anUnofferedButtonIdWithoutAnyTicketStillFailsLoudly() {
        // Deliberate boundary of the fix above: with no ticket there is nothing
        // binding a "nobody decided" claim to this exact input, so the tool keeps
        // answering with an error instead of asserting what the user did not say.
        runBlocking {
            val presenter = RecordingPresenter(selectedButtonId = "cancel")
            val tool = UserConfirmationDialogTool(
                presenter = presenter,
                nowEpochMillis = { NOW },
                nonceGenerator = { NONCE }
            )

            val result = tool.execute(
                dialogCall(targetInput = null),
                ToolExecutionContext(sessionId = SESSION)
            )

            assertTrue(
                "a legacy target-less dialog cannot bind a no-decision claim, so it must stay an error",
                result.isError
            )
        }
    }

    @Test
    fun aMalformedTargetNamesItselfInsteadOfBlamingTheButtons() = runBlocking {
        // title, message and buttons were all valid here; only `target` was wrong.
        // The old wording sent the reader to the button list.
        val tool = UserConfirmationDialogTool(
            presenter = RecordingPresenter(selectedButtonId = "confirm"),
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )
        val input = buildJsonObject {
            put("title", "Confirm")
            put("message", "Confirm the operation")
            put("buttons", JsonArray(listOf(button("confirm", "Continue"))))
            put("target", JsonPrimitive("launch_android_app_intent"))
        }

        val result = tool.execute(ToolCall("dialog-1", tool.name, input), ToolExecutionContext(sessionId = SESSION))

        assertTrue(result.isError)
        assertTrue(
            "the refusal must name the argument that is unusable, got: ${result.content}",
            result.content.contains("target")
        )
    }

    /** Control arm: the ordinary accepted flow keeps working exactly as before. */
    @Test
    fun anOfferedButtonStillProducesAnAcceptedTicketAfterShowingTheDialog() = runBlocking {
        val presenter = RecordingPresenter(selectedButtonId = "confirm")
        val tool = UserConfirmationDialogTool(
            presenter = presenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )

        val result = tool.execute(dialogCall(targetInput = simpleTarget()), ToolExecutionContext(sessionId = SESSION))

        assertFalse(result.isError)
        assertEquals("the dialog must still be shown on the happy path", 1, presenter.requests.size)
        val parsed = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("confirm", (parsed["selectedButtonId"] as JsonPrimitive).content)
        assertEquals(
            UserConfirmationInputFingerprint.sha256(simpleTarget()),
            ((parsed["ticket"] as JsonObject)["inputFingerprint"] as JsonPrimitive).content
        )
    }

    @Test
    fun anOfferedNegativeButtonTheSdkDoesNotKnowIsStillReportedAsTheUsersDecline() = runBlocking {
        // The demo's own button vocabulary classifies `not_now`, `close`, `abort`,
        // `dismiss` and `later` as cancellations - that is what colors them as a
        // Cancel button in the dialog the user reads. The SDK's refusal set did not
        // contain them, so a user who tapped 「暂不」 was answered with the generic
        // "confirmation required, call the dialog first, then retry" wording and the
        // modal came back on top of a decision they had just made.
        for (declinedId in listOf("cancel", "not_now", "close", "abort", "dismiss", "later")) {
            val delegate = RecordingTool()
            val input = buildJsonObject { put("packageName", "com.example") }
            val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
            val dialog = UserConfirmationDialogTool(
                presenter = RecordingPresenter(selectedButtonId = declinedId),
                nowEpochMillis = { NOW },
                nonceGenerator = { NONCE }
            )
            val dialogResult = dialog.execute(
                dialogCall(
                    buttons = listOf(
                        UserConfirmationDialogButton("confirm", "继续"),
                        UserConfirmationDialogButton(declinedId, "暂不")
                    ),
                    targetInput = input
                ),
                ToolExecutionContext(sessionId = SESSION)
            )

            val result = tool.execute(
                ToolCall("call-1", tool.name, input),
                ToolExecutionContext(
                    sessionId = SESSION,
                    priorMessages = listOf(
                        AgentMessage.Tool(
                            ToolResult(
                                toolCallId = "dialog-1",
                                name = USER_CONFIRMATION_DIALOG_TOOL_NAME,
                                content = dialogResult.content,
                                isError = dialogResult.isError
                            )
                        )
                    )
                )
            )

            assertTrue("$declinedId must not authorize the tool", !delegate.executed)
            assertTrue(
                "tapping the 「$declinedId」 button is the user declining; got: ${result.content}",
                result.content.contains("declined")
            )
            assertFalse(
                "a decline must not invite another dialog: ${result.content}",
                result.content.contains("then retry")
            )
        }
    }

    private fun simpleTarget(): JsonObject = buildJsonObject { put("packageName", "com.example") }

    private fun button(id: String, label: String): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive(id), "label" to JsonPrimitive(label)))

    private fun dialogCall(
        buttons: List<UserConfirmationDialogButton> = listOf(UserConfirmationDialogButton("confirm", "Continue")),
        targetInput: JsonObject?
    ): ToolCall = ToolCall(
        id = "dialog-1",
        name = USER_CONFIRMATION_DIALOG_TOOL_NAME,
        input = buildJsonObject {
            put("title", "Confirm")
            put("message", "Confirm the operation")
            put("buttons", JsonArray(buttons.map { button(it.id, it.label) }))
            if (targetInput != null) {
                put(
                    "target",
                    buildJsonObject {
                        put("toolName", "launch_android_app_intent")
                        put("input", targetInput)
                    }
                )
            }
        }
    )

    private class RecordingPresenter(private val selectedButtonId: String) : UserConfirmationDialogPresenter {
        val requests = mutableListOf<UserConfirmationDialogRequest>()

        override suspend fun showConfirmationDialog(
            request: UserConfirmationDialogRequest
        ): UserConfirmationDialogResult {
            requests += request
            return UserConfirmationDialogResult(selectedButtonId)
        }
    }

    private class RecordingTool : AgentTool {
        override val name = "launch_android_app_intent"
        override val description = "records"
        override val inputSchema = JsonObject(emptyMap())
        var executed = false
            private set

        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            executed = true
            return ToolResult(call.id, name, "ok", isError = false)
        }
    }

    private companion object {
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
