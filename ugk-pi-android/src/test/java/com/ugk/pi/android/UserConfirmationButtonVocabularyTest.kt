package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The button the host drew as a Cancel button has to be the id the protected Tool reads
 * back as the user's decline, and the button drawn as the affirmative has to read back as
 * their approval - for the spelling the model actually wrote.
 *
 * The button *sets* became one published definition in round 15; these cases pin the
 * other half of that rule, the comparison. The host classifies a button after trimming
 * and lowercasing it, while this Tool compared the id verbatim, so `Not_Now` was drawn as
 * 暂不 and answered with "call the dialog first, then retry": the modal came back on top
 * of the decision the user had just made, which is the exact harm round 15 set out to
 * remove. `CONFIRM` did the same to a yes.
 */
class UserConfirmationButtonVocabularyTest {

    /** Control: must already be green on main, so a red here would mean the fixture is wrong. */
    @Test
    fun anExactlyLowercaseDeclineIsReportedAsTheUsersRefusal() = runBlocking {
        val outcome = executeProtectedAfterDialog("cancel")

        assertTrue(outcome.isError)
        assertTrue(outcome.content, outcome.content.contains("declined"))
        assertFalse(outcome.content, outcome.content.contains("then retry"))
    }

    /** Control: the other existing arm, also green on main. */
    @Test
    fun anExactlyLowercaseApprovalRunsTheProtectedTool() = runBlocking {
        val delegate = RecordingTool()
        val outcome = delegate.runAfterDialog("confirm")

        assertFalse(outcome.content, outcome.isError)
        assertTrue(delegate.executed)
    }

    /**
     * Control for the fix direction: normalising the comparison must not turn "not in the
     * decline vocabulary" into "the user said no". Green before and after.
     */
    @Test
    fun anIdOutsideBothVocabulariesIsNotReadAsTheUsersDecline() = runBlocking {
        val outcome = executeProtectedAfterDialog("approve")

        assertTrue(outcome.isError)
        assertFalse(outcome.content, outcome.content.contains("declined"))
        assertTrue(outcome.content, outcome.content.contains("User confirmation required"))
    }

    @Test
    fun aMixedCaseDeclineIsStillTheUsersDecline() = runBlocking {
        val outcome = executeProtectedAfterDialog("Not_Now")

        assertTrue(outcome.content, outcome.content.contains("declined"))
        assertFalse(
            "a Cancel button must not send the model back to the dialog it just answered: " +
                outcome.content,
            outcome.content.contains("then retry")
        )
    }

    @Test
    fun anUpperCaseDeclineIsStillTheUsersDecline() = runBlocking {
        val outcome = executeProtectedAfterDialog("CANCEL")

        assertTrue(outcome.content, outcome.content.contains("declined"))
        assertFalse(outcome.content, outcome.content.contains("then retry"))
    }

    @Test
    fun aDeclineWithSurroundingSpacesIsStillTheUsersDecline() = runBlocking {
        val outcome = executeProtectedAfterDialog("  cancel  ")

        assertTrue(outcome.content, outcome.content.contains("declined"))
        assertFalse(outcome.content, outcome.content.contains("then retry"))
    }

    @Test
    fun aMixedCaseApprovalRunsTheProtectedTool() = runBlocking {
        val delegate = RecordingTool()
        val outcome = delegate.runAfterDialog("Confirm")

        assertFalse("the user's yes was discarded: ${outcome.content}", outcome.isError)
        assertTrue(delegate.executed)
    }

    @Test
    fun anUpperCaseAffirmativeRunsTheProtectedTool() = runBlocking {
        val delegate = RecordingTool()
        val outcome = delegate.runAfterDialog("OK")

        assertFalse("the user's yes was discarded: ${outcome.content}", outcome.isError)
        assertTrue(delegate.executed)
    }

    /**
     * A blank-ish id is not a decline either: the reader that supplies it would otherwise
     * be able to silence a protected Tool with an empty string.
     */
    @Test
    fun aBlankSelectedButtonIdIsNotReadAsTheUsersDecline() = runBlocking {
        val outcome = executeProtectedAfterDialog("   ")

        assertTrue(outcome.isError)
        assertFalse(outcome.content, outcome.content.contains("declined"))
    }

    /**
     * Both directions of the published vocabulary, so the two lists cannot be quietly
     * folded into "anything that is not accepted is declined".
     */
    @Test
    fun thePublishedVocabulariesStayDisjointAndCoverTheHostsCancelButtons() {
        assertEquals(
            "accepted and declined ids must not overlap",
            0,
            USER_CONFIRMATION_ACCEPTED_BUTTON_IDS.intersect(USER_CONFIRMATION_DECLINED_BUTTON_IDS).size
        )
        listOf("cancel", "deny", "no", "reject", "decline", "stop", "close", "abort", "dismiss", "later", "not_now")
            .forEach { id ->
                assertTrue("$id must read as a decline", id in USER_CONFIRMATION_DECLINED_BUTTON_IDS)
            }
    }

    private suspend fun executeProtectedAfterDialog(selectedButtonId: String): ToolResult =
        RecordingTool().runAfterDialog(selectedButtonId)

    private suspend fun RecordingTool.runAfterDialog(selectedButtonId: String): ToolResult {
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(this, nowEpochMillis = { NOW })
        val dialog = AgentMessage.Tool(
            ToolResult(
                toolCallId = "dialog-1",
                name = USER_CONFIRMATION_DIALOG_TOOL_NAME,
                content = buildJsonObject {
                    put("selectedButtonId", selectedButtonId)
                    put(
                        "ticket",
                        UserConfirmationTicket(
                            version = UserConfirmationTicket.CURRENT_VERSION,
                            sessionId = SESSION,
                            toolName = name,
                            inputFingerprint = UserConfirmationInputFingerprint.sha256(input),
                            nonce = NONCE,
                            issuedAtEpochMillis = NOW,
                            expiresAtEpochMillis = NOW + UserConfirmationTicket.DEFAULT_TTL_MILLIS
                        ).toJsonObject()
                    )
                }.toString()
            )
        )
        return tool.execute(
            ToolCall("intent-1", name, input),
            ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(dialog))
        )
    }

    private class RecordingTool : AgentTool {
        var executed = false
        override val name: String = "launch_android_app_intent"
        override val description: String = "Launches a test intent."
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }

        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            executed = true
            return ToolResult(call.id, name, "executed")
        }
    }

    private companion object {
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
