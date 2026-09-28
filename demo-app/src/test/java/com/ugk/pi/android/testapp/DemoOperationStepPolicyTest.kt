package com.ugk.pi.android.testapp

import org.junit.Assert.*
import org.junit.Test

class DemoOperationStepPolicyTest {
    private val action = DemoOperationEvent(1, 10, 1, "app", null, null, "button", emptyList(), "pre", "post")
    private val step = DemoOperationStep(1, listOf(1), "pre", "post")
    @Test fun changedCorrectionOrClearedAnalysisCannotConfirm() {
        val reviewed = step.copy(aiSummary = "summary", userCorrection = "target")
        assertTrue(DemoOperationStepPolicy.correctionReviewed(reviewed, " target "))
        assertFalse(DemoOperationStepPolicy.correctionReviewed(reviewed, "new target"))
        assertFalse(DemoOperationStepPolicy.correctionReviewed(reviewed.copy(aiSummary = null), "target"))
    }
    @Test fun explicitBoundarySurvivesReadingTimeButNeverCrossesPackages() {
        val old = DemoOperationFrame("pre", 1, "frame-old.jpg", "app", 100, 100, 1)
        assertEquals("pre", DemoOperationStepPolicy.preFrame(old, "app"))
        assertNull(DemoOperationStepPolicy.preFrame(old, "other.app"))
        assertNull(DemoOperationStepPolicy.preFrame(null, "app"))
    }
    @Test fun requiresCompleteSingleActionEvidence() {
        assertNull(DemoOperationStepPolicy.confirmationError(step, listOf(action)))
        assertNotNull(DemoOperationStepPolicy.confirmationError(step.copy(postFrameId = null), listOf(action)))
        assertNotNull(DemoOperationStepPolicy.confirmationError(step, listOf(action.copy(preFrameId = null))))
        assertNotNull(DemoOperationStepPolicy.confirmationError(step.copy(eventIds = listOf(1, 2)), listOf(action, action.copy(id = 2))))
    }
    @Test fun preparationRequiresRealWindowEventAndSavedFrame() {
        val preparation = step.copy(preparation = true, preFrameId = null)
        assertNull(DemoOperationStepPolicy.confirmationError(preparation, listOf(action.copy(type = 32))))
        assertNotNull(DemoOperationStepPolicy.confirmationError(preparation, listOf(action)))
        assertNotNull(DemoOperationStepPolicy.confirmationError(preparation.copy(discarded = true), listOf(action.copy(type = 32))))
    }
}
