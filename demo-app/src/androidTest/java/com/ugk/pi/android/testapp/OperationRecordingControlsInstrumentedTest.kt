package com.ugk.pi.android.testapp

import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import android.widget.ImageButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the real native controls without installing an overlay or starting recording. */
@RunWith(AndroidJUnit4::class)
class OperationRecordingControlsInstrumentedTest {
    @Test
    fun compactLargeTextControlsKeepTimerAndBothActionsReachableAcrossStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val previous = ThemeStore(base).getThemeMode()
            try {
                for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
                    ThemeManager.setMode(base, mode)
                    val configuration = Configuration(base.resources.configuration).apply { fontScale = 1.5f }
                    val context = base.createConfigurationContext(configuration)
                    var pauses = 0
                    var finishes = 0
                    var opens = 0
                    val overlay = DemoOperationRecordingOverlay(context, { pauses++ }, { finishes++ }, { opens++ })
                    val view = overlay.createControls()
                    val recording = DemoOperationSnapshot(phase = DemoOperationPhase.RECORDING, elapsedMillis = 599000)
                    overlay.bindControls(recording)
                    val width = context.dp(208)
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(context.dp(300), View.MeasureSpec.AT_MOST))
                    view.layout(0, 0, width, view.measuredHeight)
                    assertTrue("52dp capsule height, allowing density rounding", kotlin.math.abs(context.dp(52) - view.height) <= 2)
                    val timer = view.findViewWithTag<TextView>("operation_overlay_timer")
                    assertEquals("09:59", timer.text.toString())
                    assertEquals(1, timer.lineCount)
                    assertEquals(0, timer.layout.getEllipsisCount(0))
                    assertTrue(timer.paint.measureText(timer.text.toString()) <= timer.width)
                    val handle = view.findViewWithTag<View>("operation_overlay_handle")
                    val pause = view.findViewWithTag<ImageButton>("operation_overlay_pause")
                    val finish = view.findViewWithTag<ImageButton>("operation_overlay_finish")
                    for (button in listOf(pause, finish)) {
                        assertEquals(context.dp(48), button.width)
                        assertEquals(context.dp(48), button.height)
                        assertTrue(button.left >= 0 && button.right <= width)
                        assertTrue(button.contentDescription.isNotBlank())
                    }
                    assertEquals(pause.top, finish.top)
                    assertEquals(0, pauses + finishes + opens)
                    assertEquals("暂停录制", pause.contentDescription)
                    pause.performClick()
                    overlay.bindControls(recording.copy(phase = DemoOperationPhase.PAUSED, message = "输入页面已暂停录制"))
                    assertEquals("继续录制", pause.contentDescription)
                    assertEquals("09:59", timer.text.toString())
                    assertTrue(handle.contentDescription.contains("输入页面已暂停录制"))
                    pause.performClick()
                    finish.performClick()
                    handle.performClick()
                    assertEquals(2, pauses)
                    assertEquals(1, finishes)
                    assertEquals(1, opens)
                    overlay.bindControls(recording.copy(phase = DemoOperationPhase.SAVING))
                    assertFalse(pause.isEnabled)
                    assertFalse(finish.isEnabled)
                    pause.performClick()
                    finish.performClick()
                    assertEquals(2, pauses)
                    assertEquals(1, finishes)
                    overlay.release()
                }
            } finally {
                ThemeManager.setMode(base, previous)
            }
        }
    }
}
