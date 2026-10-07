package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.android.USER_CONFIRMATION_ACCEPTED_BUTTON_IDS
import com.ugk.pi.android.USER_CONFIRMATION_DECLINED_BUTTON_IDS
import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogTool
import com.ugk.pi.android.UserConfirmationRequiredTool
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
 * The demo has three readers of one model-authored button id: the classifier that decides
 * how a button is drawn, the fallback that resolves a dialog nobody is looking at, and the
 * full-authorization auto-approval. They compared the id with different normalisations -
 * two of them used `lowercase()` with no locale, so on a Turkish device `DISMISS` became
 * `dısmıss` and stopped matching the vocabulary the first reader still matched.
 *
 * These cases pin the agreement, not one reader's private list: a button drawn as a Cancel
 * button cannot be the id the host resolves with as if it meant anything else.
 */
class ConfirmationButtonVocabularyAgreementTest {

    /** Control, green on main: the exact lowercase spellings already agree. */
    @Test
    fun aLowercaseCancelButtonIsDrawnAsCancelAndNeverAutoApproved() {
        assertTrue(ConfirmationVisualPolicy.isCancellation(button("cancel", "取消")))
        assertFalse(idIsOfferedAsApproval("cancel"))
    }

    /** Control, green on main: an id outside both vocabularies is not drawn as Cancel. */
    @Test
    fun anUnrelatedButtonIdIsNotDrawnAsCancel() {
        assertFalse(ConfirmationVisualPolicy.isCancellation(button("approve", "批准")))
    }

    @Test
    fun aCancelButtonSpellingWithSurroundingSpacesIsStillDrawnAsCancel() {
        // The label deliberately carries no refusal meaning: "取消" is itself in the host's
        // label vocabulary, so a fixture using it would pass without the id arm existing at
        // all and prove nothing about the id.
        val id = "  cancel  "
        assertTrue(
            "the SDK reads a padded decline as the user's refusal; the classifier that colours " +
                "the button has to agree or the button the user pressed is not the one the " +
                "answer describes",
            ConfirmationVisualPolicy.isCancellation(button(id, "暂不"))
        )
    }

    @Test
    fun aButtonWhoseIdMeansYesIsNeverDrawnAsTheRefusal() {
        listOf("OK", "Confirm", " ok ", "YES").forEach { id ->
            assertFalse(
                "$id is the id the protected Tool reads as the user's approval, so painting it " +
                    "with the refusal's colour would promise a no and then run the operation",
                ConfirmationVisualPolicy.isCancellation(button(id, "取消"))
            )
        }
    }

    @Test
    fun everyReaderUsesThePublishedDeclinedVocabulary() {
        val declinedButNotUnderstoodByTheHost = listOf("Not_Now", "ABORT", "Dismiss", " LATER ")
        declinedButNotUnderstoodByTheHost.forEach { id ->
            assertTrue(
                "$id is in the published declined vocabulary",
                id.trim().lowercase(Locale.ROOT) in USER_CONFIRMATION_DECLINED_BUTTON_IDS
            )
            assertTrue(
                "the visual classifier must not draw $id as anything but a Cancel button",
                ConfirmationVisualPolicy.isCancellation(button(id, "暂不"))
            )
            assertFalse(
                "full authorization must not resolve $id as if it were an approval",
                idIsOfferedAsApproval(id)
            )
        }
    }

    /**
     * What actually matters about an unattended run: nothing executes. The fallback picks an
     * id for a user who is not there, so the answer must never read as an approval - whether
     * the model spelled its refusal `cancel` or `Not_Now`. Green on main and green here, which
     * is the point: the safety property did not move, only the wording of the answer does, and
     * this is the arm no reader may cross.
     */
    @Test
    fun anUnattendedRunNeverAuthorizesTheProtectedTool() {
        listOf(
            emptyList(),
            listOf(button("confirm", "确认")),
            listOf(button("Confirm", "确认"), button("cancel", "取消")),
            listOf(button("confirm", "确认"), button("Not_Now", "暂不")),
            listOf(button("OK", "确认"), button(" LATER ", "稍后"))
        ).forEach { offered ->
            val delegate = RefusingTool()
            val outcome = runBlocking {
                val dialog = UserConfirmationDialogTool(
                    presenter = HeadlessConfirmationDialogPresenter,
                    nowEpochMillis = { NOW },
                    nonceGenerator = { NONCE }
                )
                val dialogInput = buildJsonObject {
                    put("title", "确认")
                    put("message", "执行操作")
                    put(
                        "buttons",
                        JsonArray(
                            if (offered.isEmpty()) {
                                listOf(buildJsonObject {
                                    put("id", "confirm")
                                    put("label", "确认")
                                })
                            } else {
                                offered.map { b ->
                                    buildJsonObject {
                                        put("id", b.id)
                                        put("label", b.label)
                                    }
                                }
                            }
                        )
                    )
                    putJsonObject("target") {
                        put("toolName", delegate.name)
                        put("input", buildJsonObject { put("command", "erase_everything") })
                    }
                }
                val first = dialog.execute(
                    ToolCall("dialog-1", dialog.name, dialogInput),
                    ToolExecutionContext(sessionId = SESSION)
                )
                UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW }).execute(
                    ToolCall("protected-1", delegate.name, buildJsonObject { put("command", "erase_everything") }),
                    ToolExecutionContext(
                        sessionId = SESSION,
                        priorMessages = if (first.isError) emptyList() else listOf(AgentMessage.Tool(first))
                    )
                )
            }

            assertTrue(
                "an unattended run executed nothing and answered: ${outcome.content}",
                outcome.isError
            )
            assertFalse("the protected Tool ran with no user in the loop", delegate.executed)
        }
    }

    /**
     * The presenter's documented rule held to the published vocabulary instead of a private
     * six-id copy: when the offer does contain a cancellation button the answer stays a plain
     * refusal rather than "nobody decided", so the model keeps the do-not-ask-again wording.
     */
    @Test
    fun theHeadlessFallbackTreatsEveryPublishedRefusalAsACancellation() {
        listOf("not_now", "ABORT", "Dismiss", " LATER ", "decline").forEach { refusal ->
            val result = runBlocking {
                HeadlessConfirmationDialogPresenter.showConfirmationDialog(
                    UserConfirmationDialogRequest(
                        title = "确认",
                        message = "执行操作",
                        buttons = listOf(button("confirm", "确认"), button(refusal, "暂不"))
                    )
                )
            }

            assertEquals("the fallback resolves with $refusal", refusal, result.selectedButtonId)
            assertFalse(
                "$refusal is in the published refusal vocabulary, so a cancellation button was " +
                    "offered and the documented rule applies to it exactly as it does to cancel",
                result.withoutUserDecision
            )
        }
    }

    private class RefusingTool : AgentTool {
        var executed = false
        override val name: String = "dangerous_tool"
        override val description: String = "Test protected tool."
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            executed = true
            return ToolResult(call.id, name, "executed")
        }
    }

    /**
     * The two claims this file's own comments make about mirroring the SDK, made checkable:
     * an auto-approval may only ever be an id the SDK reads as approval, and the ids the
     * headless fallback treats as cancellations must stay inside the published refusal set
     * - never a private list that can drift out of it.
     */
    @Test
    fun theAutoApprovalPrefersAnAcceptedIdAndNeverARefusalOverAnotherButton() {
        assertEquals(
            "confirm",
            AgentAuthorizationPolicy.autoApproveButtonId(listOf(button("not_now", "暂不"), button("confirm", "确认")))
        )
        // No approvable button, but one that means neither yes nor no: resolving the dialog
        // with a refusal id would have the host report the user's "no" on their behalf.
        assertEquals(
            "approve",
            AgentAuthorizationPolicy.autoApproveButtonId(
                listOf(button("Cancel", "取消"), button("ABORT", "中止"), button("approve", "批准"))
            )
        )
        // Nothing but refusals: the answer may stay a refusal, it may not become an approval.
        val onlyRefusals = AgentAuthorizationPolicy.autoApproveButtonId(
            listOf(button("Cancel", "取消"), button("ABORT", "中止"))
        )
        assertFalse(
            "auto-approval returned $onlyRefusals as if it authorized the operation",
            onlyRefusals.trim().lowercase(Locale.ROOT) in USER_CONFIRMATION_ACCEPTED_BUTTON_IDS
        )
    }

    /**
     * A device's default locale must not decide whether a button is a cancellation: the
     * Turkish dotted-I is the reachable case, and it is the reason the shared comparison
     * pins `Locale.ROOT`.
     */
    @Test
    fun theCancellationVocabularyDoesNotDependOnTheDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val drawnAsCancel = ConfirmationVisualPolicy.isCancellation(button("DISMISS", "关闭"))
            val offeredAsApproval = idIsOfferedAsApproval("DISMISS")

            assertTrue("DISMISS .lowercase() under tr-TR: ${"DISMISS".lowercase()}", drawnAsCancel)
            assertFalse(
                "the visual classifier calls DISMISS a Cancel button while full authorization " +
                    "resolves it as an approval - the two readers split on the device locale",
                offeredAsApproval
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** True when full authorization would resolve the dialog with [id] as the chosen button. */
    private fun idIsOfferedAsApproval(id: String): Boolean =
        AgentAuthorizationPolicy.autoApproveButtonId(listOf(button(id, "暂不"), button("approve", "批准"))) == id

    private fun button(id: String, label: String): UserConfirmationDialogButton =
        UserConfirmationDialogButton(id, label)

    private companion object {
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
