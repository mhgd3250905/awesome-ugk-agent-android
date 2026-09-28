package com.ugk.pi.android.testapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AccessibilityScreenAutomationBackend
import com.ugk.pi.android.AccessibilityServiceProvider
import com.ugk.pi.android.ScreenAutomationErrorCodes
import com.ugk.pi.android.ScreenVisualGestureRequest
import com.ugk.pi.android.ScreenVisualTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in local fixture: no model calls or changes to saved teaching records. */
@RunWith(AndroidJUnit4::class)
class VisualGestureDiagnosticsProbe {
    @Test fun slowDecisionAndAnimatedBackgroundDoNotBlockGestures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("visualRevalidationProbe") == "true")
        var activity: Activity? = null
        try {
            withTimeout(30_000) {
                while (AgentAccessibilityService.instance == null) delay(200)
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // OEM background-start restrictions may require the operator to open the app via adb.
                activity = instrumentation.waitForMonitorWithTimeout(monitor, 30_000)
            } finally { instrumentation.removeMonitor(monitor) }
            val fixtureActivity = checkNotNull(activity)
            lateinit var root: FrameLayout
            lateinit var button: Button
            val clicks = AtomicInteger()
            instrumentation.runOnMainSync {
                fixtureActivity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                root = FrameLayout(fixtureActivity).apply { setBackgroundColor(Color.rgb(238, 241, 245)) }
                button = Button(fixtureActivity).apply {
                    text = "Visual revalidation test"
                    setOnClickListener { clicks.incrementAndGet() }
                }
                root.addView(button, FrameLayout.LayoutParams(600, 180, Gravity.CENTER))
                fixtureActivity.setContentView(root)
            }
            delay(900)
            var clockOffset = 0L
            val backend = AccessibilityScreenAutomationBackend(
                AccessibilityServiceProvider { AgentAccessibilityService.instance },
                ownPackageName = "com.ugk.test.fixture.exclusion",
                nowEpochMillis = { System.currentTimeMillis() + clockOffset }
            )
            lateinit var target: ScreenVisualTarget
            instrumentation.runOnMainSync {
                val xy = IntArray(2)
                button.getLocationOnScreen(xy)
                val metrics = checkNotNull(AgentAccessibilityService.instance).resources.displayMetrics
                target = ScreenVisualTarget(xy[0].toDouble() / metrics.widthPixels,
                    xy[1].toDouble() / metrics.heightPixels,
                    (xy[0] + button.width).toDouble() / metrics.widthPixels,
                    (xy[1] + button.height).toDouble() / metrics.heightPixels)
            }
            val first = backend.captureVisualObservation("revalidation-probe")
            assertTrue(first.code + ": " + first.message, first.success)
            assertEquals("Fixture must be the foreground screen", instrumentation.targetContext.packageName,
                first.observation!!.packageName)
            clockOffset = 40_000L
            val clicked = backend.performVisualGesture("revalidation-probe",
                ScreenVisualGestureRequest(first.observation!!.observationId, "tap", target))
            assertTrue(clicked.toString(), clicked.success)
            delay(400)
            assertEquals("The actual button must receive the gesture", 1, clicks.get())
            delay(400)
            val second = backend.captureVisualObservation("revalidation-probe")
            assertTrue(second.code + ": " + second.message, second.success)
            instrumentation.runOnMainSync { root.setBackgroundColor(Color.rgb(30, 50, 70)) }
            delay(400)
            clockOffset = 80_000L
            val backgroundChangedTap = backend.performVisualGesture("revalidation-probe",
                ScreenVisualGestureRequest(second.observation!!.observationId, "tap", target))
            assertTrue(backgroundChangedTap.toString(), backgroundChangedTap.success)
            assertEquals(ScreenAutomationErrorCodes.OK, backgroundChangedTap.code)
            assertEquals("Background changes must not block the target tap", 2, clicks.get())
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                // Reproduce the platform's protected-view case: dispatch can complete without a click.
                instrumentation.runOnMainSync {
                    button.setAccessibilityDataSensitive(android.view.View.ACCESSIBILITY_DATA_SENSITIVE_YES)
                }
                delay(500)
                val protectedObservation = backend.captureVisualObservation("revalidation-probe")
                assertTrue(protectedObservation.code, protectedObservation.success)
                val protectedTap = backend.performVisualGesture("revalidation-probe",
                    ScreenVisualGestureRequest(protectedObservation.observation!!.observationId, "tap", target))
                assertTrue(protectedTap.toString(), protectedTap.success)
                assertEquals("Protected view does not receive a click even though dispatch completes", 2, clicks.get())
                assertEquals("unchanged", protectedTap.metadata["screenChange"])
                assertEquals("false", protectedTap.metadata["effectVerified"])
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
}
