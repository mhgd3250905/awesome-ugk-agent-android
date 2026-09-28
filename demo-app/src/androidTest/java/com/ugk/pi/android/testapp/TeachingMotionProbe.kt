package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Explicit overlay-only probe; does not call a model or change saved recordings. */
@RunWith(AndroidJUnit4::class)
class TeachingMotionProbe {
    @Test fun slideLeavesNearestEdgeBeforePanelEntersFromTheSameEdge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        val screenWidth = instrumentation.targetContext.resources.displayMetrics.widthPixels
        lateinit var window: AgentFloatingWindow
        fun field(name: String) = AgentFloatingWindow::class.java.getDeclaredField(name).apply { isAccessible = true }
        for (left in listOf(true, false)) {
            lateinit var bubble: android.view.View
            lateinit var panel: android.view.View
            var sawSourceExit = false
            var sawPanelEntry = false
            try {
                instrumentation.runOnMainSync {
                    window = AgentFloatingWindow(instrumentation.targetContext)
                    field("collapsedX").setInt(window, if (left) 30 else screenWidth - 30)
                    window.show()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    bubble = field("collapsedView").get(window) as android.view.View
                    window.showExpanded()
                    panel = field("expandedView").get(window) as android.view.View
                }
                repeat(36) {
                    Thread.sleep(20)
                    instrumentation.runOnMainSync {
                        org.junit.Assert.assertEquals(1f, panel.scaleX, .001f)
                        org.junit.Assert.assertEquals(1f, panel.alpha, .001f)
                        for (name in listOf("expandedParams", "collapsedParams")) {
                            val params = field(name).get(window) as android.view.WindowManager.LayoutParams
                            org.junit.Assert.assertEquals(1f, params.alpha, .001f)
                            org.junit.Assert.assertEquals("System must not force the overlay translucent", 0,
                                params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        }
                        val source = IntArray(2)
                        val target = IntArray(2)
                        bubble.getLocationOnScreen(source)
                        panel.getLocationOnScreen(target)
                        val sourceOutside = !bubble.isAttachedToWindow ||
                            (if (left) source[0] + bubble.width <= 0 else source[0] >= screenWidth)
                        val targetVisible = target[0] < screenWidth && target[0] + panel.width > 0
                        if (sourceOutside) sawSourceExit = true
                        if (targetVisible) {
                            sawPanelEntry = true
                            assertTrue("Source must leave completely before the panel enters", sourceOutside)
                        }
                    }
                }
                assertTrue("Source must actually reach the requested screen edge", sawSourceExit)
                assertTrue("Panel must become visible", sawPanelEntry)
                instrumentation.runOnMainSync {
                    val position = IntArray(2)
                    panel.getLocationOnScreen(position)
                    assertTrue(position[0] >= 0 && position[0] + panel.width <= screenWidth)
                }
            } finally { instrumentation.runOnMainSync { runCatching { window.hide() } } }
        }
    }

    @Test fun expandedPanelStaysFullyVisibleAfterItsFirstAttachment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        lateinit var window: AgentFloatingWindow
        fun field(name: String): Any? = AgentFloatingWindow::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(window)
        for (teaching in listOf(false, true)) {
            try {
                instrumentation.runOnMainSync {
                    window = AgentFloatingWindow(instrumentation.targetContext)
                    window.setTeachingState(teaching)
                    window.show()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { window.showExpanded() }
                // Observe two settled frames far apart: an old pre-draw callback must not
                // restart the animation after another animator removes the source bubble.
                repeat(2) {
                    Thread.sleep(750)
                    instrumentation.runOnMainSync {
                        val panel = field("expandedView") as android.view.View
                        assertTrue(panel.isAttachedToWindow)
                        org.junit.Assert.assertEquals("Panel must stay opaque", 1f, panel.alpha, .001f)
                        org.junit.Assert.assertEquals(1f, panel.scaleX, .001f)
                        org.junit.Assert.assertNull("First-frame listener must fire once", field("transitionOpening"))
                        org.junit.Assert.assertNull(field("collapsedView"))
                    }
                }
            } finally { instrumentation.runOnMainSync { runCatching { window.hide() } } }
        }
    }

    @Test fun expansionKeepsSourceUntilReadyAndHideCancelsTheHandoff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        lateinit var window: AgentFloatingWindow
        fun field(name: String): Any? = AgentFloatingWindow::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(window)
        try {
            instrumentation.runOnMainSync {
                window = AgentFloatingWindow(instrumentation.targetContext)
                window.show()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val source = field("collapsedView") as android.view.View
                window.showExpanded()
                val panel = field("expandedView") as android.view.View
                val transition = field("surfaceTransition")
                // No traversal has happened yet: the old bubble must still cover the handoff.
                assertTrue(source.isAttachedToWindow)
                val params = field("expandedParams") as android.view.WindowManager.LayoutParams
                assertTrue(params.x + params.width <= 0 || params.x >= instrumentation.targetContext.resources.displayMetrics.widthPixels)
                org.junit.Assert.assertEquals(1f, panel.scaleX, .001f)
                window.showExpanded()
                org.junit.Assert.assertSame(panel, field("expandedView"))
                org.junit.Assert.assertSame(transition, field("surfaceTransition"))
                window.hide()
                assertFalse(window.isShowing())
            }
            Thread.sleep(500)
            instrumentation.runOnMainSync { assertFalse(window.isShowing()) }
        } finally { instrumentation.runOnMainSync { runCatching { window.hide() } } }
    }

    @Test fun slidingPanelDetachesBeforeTheScreenToolRuns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        lateinit var window: AgentFloatingWindow
        lateinit var outgoing: android.view.View
        try {
            instrumentation.runOnMainSync {
                window = AgentFloatingWindow(instrumentation.targetContext)
                window.showExpanded()
                outgoing = AgentFloatingWindow::class.java.getDeclaredField("expandedView")
                    .apply { isAccessible = true }.get(window) as android.view.View
            }
            instrumentation.waitForIdleSync()
            runBlocking { withContext(Dispatchers.Main) {
                window.prepareScreenOperation()
                assertFalse(window.isShowing())
                assertFalse(outgoing.isAttachedToWindow)
                org.junit.Assert.assertEquals("Slide must never scale the panel", 1f, outgoing.scaleX, .001f)
                window.finishScreenOperation()
                assertTrue(window.isShowing())
            } }
        } finally { instrumentation.runOnMainSync { runCatching { window.hide() } } }
    }

    @Test fun firstBackgroundShowIsDeferredAndForegroundReturnCancelsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        lateinit var window: AgentFloatingWindow
        instrumentation.runOnMainSync { window = AgentFloatingWindow(instrumentation.targetContext) }
        try {
            runBlocking { withContext(Dispatchers.Main) {
                window.prepareScreenOperation() // First launch starts with no overlay.
                window.show() // Activity.onPause during launch_android_app.
                assertFalse(window.isShowing())
                window.finishScreenOperation()
                assertTrue(window.isShowing())
                window.prepareScreenOperation()
                window.show()
                window.hideOrdinaryForActivity() // User returned before the tool completed.
                window.finishScreenOperation()
                assertFalse(window.isShowing())
            } }
        } finally { instrumentation.runOnMainSync { window.hide() } }
    }

    @Test fun ordinaryChatAlsoWaitsAndDetachesBeforeScreenTools() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        lateinit var window: AgentFloatingWindow
        try {
            instrumentation.runOnMainSync {
                window = AgentFloatingWindow(instrumentation.targetContext)
                window.showExpanded()
                assertTrue(window.isShowing())
            }
            runBlocking { withContext(Dispatchers.Main) {
                window.prepareScreenOperation()
                assertFalse(window.isShowing())
                window.showExpanded() // Opening is deferred while the tool owns the surface.
                assertFalse(window.isShowing())
                window.finishScreenOperation()
                assertTrue(window.isShowing())
            } }
        } finally { instrumentation.runOnMainSync { runCatching { window.hide() } } }
    }

    @Test fun overlayTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionProbe") == "true")
        lateinit var window: AgentFloatingWindow
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun settle() = Thread.sleep(600)
        try {
            main {
                window = AgentFloatingWindow(instrumentation.targetContext)
                window.setTeachingState(true)
                window.showExpanded()
                assertTrue(window.isTeachingChatShowing())
                window.bindTeachingSnapshot(AgentOverlaySnapshot(title = "动效验证", statusLabel = "运行中", isBusy = true))
            }
            settle()
            main { assertFalse(window.isTeachingChatShowing()); assertTrue(window.isShowing()); window.showExpanded() }
            settle()
            main {
                assertTrue(window.isTeachingChatShowing())
                window.setTeachingState(true)
                window.bindTeachingSnapshot(AgentOverlaySnapshot(title = "动效验证", statusLabel = "运行中", isBusy = true))
                assertTrue(window.isTeachingChatShowing())
                window.bindTeachingSnapshot(AgentOverlaySnapshot(title = "动效验证", statusLabel = "运行中", isBusy = false))
                window.bindTeachingSnapshot(AgentOverlaySnapshot(title = "动效验证", statusLabel = "运行中", isBusy = true))
            }
            runBlocking { withContext(Dispatchers.Main) { window.prepareScreenOperation() } }
            main {
                assertFalse(window.isShowing())
                window.finishScreenOperation()
                assertFalse(window.isTeachingChatShowing())
                window.showExpanded()
            }
            settle()
            // Opening during a run must collapse again before its next screen tool.
            main { assertTrue(window.isTeachingChatShowing()) }
            runBlocking { withContext(Dispatchers.Main) { window.prepareScreenOperation() } }
            main {
                assertFalse(window.isTeachingChatShowing())
                assertFalse(window.isShowing())
                window.finishScreenOperation()
                window.bindTeachingSnapshot(AgentOverlaySnapshot(title = "动效验证", statusLabel = "运行中", isBusy = false))
            }
            settle()
            main {
                // Completion leaves the shared bubble in place, just like ordinary chat.
                assertFalse(window.isTeachingChatShowing())
                assertTrue(window.isShowing())
                window.showExpanded()
            }
            settle()
            main {
                assertTrue(window.isTeachingChatShowing())
                // A teaching session's process ownership must not collapse an idle composer.
                window.setExternalAutomationMode(true)
                assertTrue(window.isTeachingChatShowing())
                window.hideOrdinaryForActivity()
                assertFalse(window.isShowing())
                window.show()
                assertTrue(window.isShowing())
                assertFalse(window.isTeachingChatShowing())
            }
        } finally {
            main { runCatching { window.hide() } }
        }
    }
}
