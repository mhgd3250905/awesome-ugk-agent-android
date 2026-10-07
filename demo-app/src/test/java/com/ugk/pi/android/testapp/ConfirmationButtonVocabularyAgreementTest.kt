package com.ugk.pi.android.testapp

import com.ugk.pi.android.USER_CONFIRMATION_ACCEPTED_BUTTON_IDS
import com.ugk.pi.android.USER_CONFIRMATION_DECLINED_BUTTON_IDS
import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
import kotlinx.coroutines.runBlocking
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
        val id = "  cancel  "
        assertTrue(
            "the SDK reads a padded decline as the user's refusal; the classifier that colours " +
                "the button has to agree or the button the user pressed is not the one the " +
                "answer describes",
            ConfirmationVisualPolicy.isCancellation(button(id, "取消"))
        )
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

    @Test
    fun theFallbackForAnUnattendedRunResolvesWithAnIdThatCannotReadAsApproval() {
        val result = runBlocking {
            HeadlessConfirmationDialogPresenter.showConfirmationDialog(
                UserConfirmationDialogRequest(
                    title = "确认",
                    message = "执行操作",
                    buttons = listOf(button("confirm", "确认"), button("Dismiss", "关闭"))
                )
            )
        }

        assertFalse(
            "the headless fallback answered with ${result.selectedButtonId}, which the SDK " +
                "reads as the user's approval",
            result.selectedButtonId.trim().lowercase(Locale.ROOT) in USER_CONFIRMATION_ACCEPTED_BUTTON_IDS
        )
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
}
