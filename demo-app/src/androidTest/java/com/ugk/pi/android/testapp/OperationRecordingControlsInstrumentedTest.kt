package com.ugk.pi.android.testapp

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationRecordingControlsInstrumentedTest {
    @Test fun guidedControlsCompleteOneStepAndReviewRequiresAiSummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            var completed = 0
            val overlay = DemoOperationRecordingOverlay(context, {}, {}, {}, { completed++ })
            val view = overlay.createControls()
            overlay.bindControls(DemoOperationSnapshot(phase = DemoOperationPhase.RECORDING,
                guidePhase = DemoOperationGuidePhase.ACTING, stepNumber = 3))
            assertEquals("第 3 步", view.findViewWithTag<TextView>("operation_overlay_timer").text.toString())
            view.findViewWithTag<View>("operation_overlay_complete").performClick()
            assertEquals(1, completed)
            overlay.bindControls(DemoOperationSnapshot(phase = DemoOperationPhase.PAUSED))
            assertFalse(view.findViewWithTag<View>("operation_overlay_complete").isEnabled)
            assertEquals("已暂停", view.findViewWithTag<TextView>("operation_overlay_timer").text.toString())
            val review = DemoOperationStepReviewOverlay(context, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> null })
            val state = DemoOperationSnapshot(phase = DemoOperationPhase.RECORDING,
                guidePhase = DemoOperationGuidePhase.REVIEW, guidedAiEnabled = true,
                reviewStep = DemoOperationStep(id = 1, localSummary = "点击设置"))
            val pending = review.createContent(state)
            fun labels(root: android.view.ViewGroup): List<String> = (0 until root.childCount)
                .map { root.getChildAt(it) }.filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(labels(pending).contains("确认并进行下一步"))
            assertTrue(labels(pending).contains("重新整理这一步"))
            assertTrue(labels(review.createContent(state.copy(guidePhase = DemoOperationGuidePhase.READY))).contains("先调整页面"))
            val ready = review.createContent(state.copy(reviewStep = state.reviewStep!!.copy(aiSummary = "打开设置页面")))
            assertTrue(labels(ready).contains("确认并进行下一步"))
            assertNotNull(ready.findViewWithTag<EditText>("operation_step_correction"))
            overlay.release()
        }
    }
}
