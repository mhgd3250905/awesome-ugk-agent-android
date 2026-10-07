package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
     * A blank-ish id is not a decline either, asserted on the classifier itself: the Tool
     * cannot receive one, because its reader already rejects a blank `selectedButtonId`, so a
     * case driven through the Tool could never tell the two apart.
     */
    @Test
    fun aBlankIdIsNeitherApprovalNorRefusalAtTheClassifier() {
        listOf("", "   ", "\t\n").forEach { blank ->
            assertEquals(
                "\"$blank\" must not be read as a decision",
                UserConfirmationButtonIntent.UNRECOGNIZED,
                userConfirmationButtonIntent(blank)
            )
        }
    }

    /**
     * The value domain of the comparison, spelled through the classifier. Every spelling here
     * is something a model can write into `buttons[].id`, and the host draws the button from
     * the same normalisation, so the two must never disagree about what the user pressed.
     */
    @Test
    fun everySpellingOfThePublishedVocabulariesReadsTheSameWay() {
        USER_CONFIRMATION_ACCEPTED_BUTTON_IDS.forEach { id ->
            assertEquals(id, UserConfirmationButtonIntent.ACCEPTED, userConfirmationButtonIntent(id))
            assertEquals(id, UserConfirmationButtonIntent.ACCEPTED, userConfirmationButtonIntent(id.uppercase(Locale.ROOT)))
            assertEquals(id, UserConfirmationButtonIntent.ACCEPTED, userConfirmationButtonIntent(" $id "))
            assertEquals(id, UserConfirmationButtonIntent.ACCEPTED, userConfirmationButtonIntent(id.replaceFirstChar { c -> c.uppercaseChar() }))
        }
        USER_CONFIRMATION_DECLINED_BUTTON_IDS.forEach { id ->
            assertEquals(id, UserConfirmationButtonIntent.DECLINED, userConfirmationButtonIntent(id))
            assertEquals(id, UserConfirmationButtonIntent.DECLINED, userConfirmationButtonIntent(id.uppercase(Locale.ROOT)))
            assertEquals(id, UserConfirmationButtonIntent.DECLINED, userConfirmationButtonIntent("\t$id\n"))
            assertEquals(id, UserConfirmationButtonIntent.DECLINED, userConfirmationButtonIntent(id.replaceFirstChar { c -> c.uppercaseChar() }))
        }
        // Neither, and it stays neither: the fold must not become "anything that is not an
        // accepted id is a refusal".
        listOf("approve", "maybe", "use", "review_later", "confirm2", "oka", "cancell").forEach { id ->
            assertEquals(id, UserConfirmationButtonIntent.UNRECOGNIZED, userConfirmationButtonIntent(id))
        }
    }

    /**
     * Turkish dotted-I is the reachable reason the comparison pins `Locale.ROOT`: with the
     * device locale in charge, `DISMISS` and `CONTINUE` stop matching their vocabularies, so
     * one device would honour the user's decline and another would ask them again.
     */
    @Test
    fun theReadingDoesNotDependOnTheDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(UserConfirmationButtonIntent.DECLINED, userConfirmationButtonIntent("DISMISS"))
            assertEquals(UserConfirmationButtonIntent.ACCEPTED, userConfirmationButtonIntent("CONTINUE"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun anOverlappingInjectedVocabularyStaysAnAuthorizationNotARefusal() {
        // Documented precedence: accepted is checked first, so a host that injects an id into
        // both sets cannot have an approval silently re-read as the user's "no".
        val shared = "confirm"
        assertEquals(
            UserConfirmationButtonIntent.ACCEPTED,
            userConfirmationButtonIntent(
                buttonId = shared.uppercase(Locale.ROOT),
                acceptedButtonIds = setOf(shared),
                declinedButtonIds = setOf(shared)
            )
        )
    }

    @Test
    fun normalizationTrimsAndFoldsCaseWithoutInventingAnId() {
        assertEquals("not_now", normalizeUserConfirmationButtonId("  Not_Now \t"))
        assertEquals("", normalizeUserConfirmationButtonId("   "))
        // A dotless-i mapping would produce "dısmıss"; the root fold must not.
        assertEquals("dismiss", normalizeUserConfirmationButtonId("DISMISS"))
    }

    /**
     * Control, green on main: a host that echoes exactly the id it was offered is not read as
     * "an id that was never offered".
     */
    @Test
    fun anOfferedIdEchoedVerbatimIsStillTheUsersChoice() = runBlocking {
        val outcome = runDialog(listOf("cancel" to "取消"), echo = "cancel")

        assertFalse(outcome.content, outcome.isError)
        assertTrue(outcome.content, outcome.content.contains("\"selectedButtonId\":\"cancel\""))
    }

    @Test
    fun aHostThatEchoesAnOfferedIdInAnotherSpellingIsStillTheUsersChoice() = runBlocking {
        val outcome = runDialog(listOf("ok" to "确认"), echo = "OK")

        assertFalse(
            "the button was shown to the user; echoing its id with different case is the same " +
                "choice, not a dialog nobody answered: ${outcome.content}",
            outcome.isError
        )
        assertTrue(outcome.content, outcome.content.contains("\"selectedButtonId\":\"OK\""))
        assertFalse(outcome.content, outcome.content.contains("withoutUserDecision"))
    }

    /**
     * The answer is an id, so an offer whose ids collapse onto one normalised id cannot be
     * attributed. Refused before anybody is asked, rather than resolved by a guess about which
     * button the user pressed.
     */
    @Test
    fun aRequestWhoseButtonIdsCollideAfterNormalisationIsRefusedBeforeTheUserIsAsked() = runBlocking {
        val outcome = runDialog(listOf("OK" to "确认", "ok " to "取消"), echo = "ok")

        assertTrue(
            "an ambiguous offer must not be presented at all: ${outcome.content}",
            outcome.isError
        )
        assertTrue(outcome.content, outcome.content.contains("share one button id"))
        assertFalse(
            "the user was never asked, so nothing may read as their decision: ${outcome.content}",
            outcome.content.contains("selectedButtonId")
        )
    }

    private suspend fun runDialog(
        offered: List<Pair<String, String>>,
        echo: String
    ): ToolResult {
        val presenter = object : UserConfirmationDialogPresenter {
            override suspend fun showConfirmationDialog(
                request: UserConfirmationDialogRequest
            ) = UserConfirmationDialogResult(selectedButtonId = echo)
        }
        val dialog = UserConfirmationDialogTool(
            presenter = presenter,
            nowEpochMillis = { NOW },
            nonceGenerator = { NONCE }
        )
        val dialogInput = buildJsonObject {
            put("title", "确认")
            put("message", "是否执行")
            put(
                "buttons",
                JsonArray(
                    offered.map { (id, label) ->
                        buildJsonObject {
                            put("id", id)
                            put("label", label)
                        }
                    }
                )
            )
            putJsonObject("target") {
                put("toolName", "launch_android_app_intent")
                put("input", buildJsonObject { put("target", "open_url") })
            }
        }
        return dialog.execute(
            ToolCall("dialog-1", dialog.name, dialogInput),
            ToolExecutionContext(sessionId = SESSION)
        )
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
