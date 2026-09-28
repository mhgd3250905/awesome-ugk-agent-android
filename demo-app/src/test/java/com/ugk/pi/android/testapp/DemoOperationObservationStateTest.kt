package com.ugk.pi.android.testapp

import org.junit.Assert.*
import org.junit.Test

class DemoOperationObservationStateTest {
    @Test fun ownOverlayLayerOffsetsDoNotChangeExternalWindowSignature() {
        val before = demoOperationWindowSignature(7, listOf(0 to "7:app:bounds", 2 to "8:system:bounds"))
        val shifted = demoOperationWindowSignature(7, listOf(1 to "7:app:bounds", 3 to "8:system:bounds"))
        assertEquals(before, shifted)
        val state = DemoOperationObservationState()
        state.observe(page.copy(windowSignature = before), false)
        assertFalse(state.observe(page.copy(windowSignature = shifted), false))
        assertNotEquals(before, demoOperationWindowSignature(7, listOf(3 to "7:app:bounds", 1 to "8:system:bounds")))
        assertNotEquals(before, demoOperationWindowSignature(8, listOf(0 to "7:app:bounds", 2 to "8:system:bounds")))
        assertNotEquals(before, demoOperationWindowSignature(7, listOf(0 to "7:app:bounds", 2 to "8:system:changed-bounds")))
        assertNotEquals(before, demoOperationWindowSignature(7, listOf(0 to "7:app:bounds")))
    }

    @Test fun onlyTwoObservedZeroDeltasAreLayoutNotifications() {
        val event = DemoOperationEvent(1, 100, 4096, "app", null, null, null, emptyList())
        assertFalse(event.isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollDeltaX = 0).isZeroMovementScrollNotification())
        assertTrue(event.copy(scrollDeltaX = 0, scrollDeltaY = 0).isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollDeltaX = 0, scrollDeltaY = 20).isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollDeltaX = -1, scrollDeltaY = 0).isZeroMovementScrollNotification())
        assertFalse(event.copy(type = 1, scrollDeltaX = 0, scrollDeltaY = 0).isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollX = 0, scrollY = 0, fromIndex = 0, toIndex = 5).isZeroMovementScrollNotification())
    }

    @Test fun zeroDeltaLayoutPreservesClickButUnknownOrMovingScrollEndsItsSegment() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(5, "app")
        val zero = DemoOperationScrollObservation(0, 0, 0, 0, 0, 5)
        evidence.recordEvent(6, "app", isPageNotification = zero.isZeroMovement)
        assertEquals(5, evidence.pendingFor("app"))
        val unknown = zero.copy(deltaY = null)
        evidence.recordEvent(7, "app", isPageNotification = unknown.isZeroMovement)
        assertEquals(7, evidence.pendingFor("app"))
        val moving = zero.copy(deltaY = 10)
        evidence.recordEvent(8, "app", isPageNotification = moving.isZeroMovement)
        assertEquals(8, evidence.pendingFor("app"))
    }

    private val node = DemoOperationNode("0.1", "app:id/button", "TextView", "Open", null,
        listOf(1, 2, 20, 30), true, false, false)
    private val page = DemoOperationPage("app", false, listOf(node), listOf("active:7"))

    @Test fun samePageFocusAndSelectionNotificationsKeepCurrentRevision() {
        val state = DemoOperationObservationState()
        assertTrue(state.observe(page, false))
        repeat(20) { assertFalse(state.observe(page.copy(nodes = page.nodes.toList()), false)) }
    }

    @Test fun actionOrContentSignalInvalidatesEvenWhenTreeAppearsIdentical() {
        val state = DemoOperationObservationState()
        state.observe(page, false)
        assertTrue(state.observe(page, true))
        assertFalse(state.observe(page, false))
    }

    @Test fun changedTextBoundsCheckedStateWindowOrPackageInvalidates() {
        listOf(
            page.copy(nodes = listOf(node.copy(text = "Changed"))),
            page.copy(nodes = listOf(node.copy(bounds = listOf(10, 20, 200, 300)))),
            page.copy(nodes = listOf(node.copy(checked = true))),
            page.copy(windowSignature = listOf("active:8")),
            page.copy(packageName = "other")
        ).forEach { changed ->
            val state = DemoOperationObservationState()
            state.observe(page, false)
            assertTrue(state.observe(changed, false))
        }
    }

    @Test fun pauseCancelOrPackageBoundaryInvalidationStartsNewObservationSegment() {
        val state = DemoOperationObservationState()
        state.observe(page, false)
        state.invalidate()
        assertTrue(state.observe(page, false))
    }

    @Test fun preFrameRequiresSamePackageAndNonnegativeBoundedAge() {
        val frame = DemoOperationFrame("frame", 50_000L, "frame.jpg", "app", 100, 100, 1)
        assertEquals("frame", DemoOperationObservationState.reliablePreFrame(frame, "app", 60_000L))
        assertNull(DemoOperationObservationState.reliablePreFrame(frame, "app", 60_001L))
        assertNull(DemoOperationObservationState.reliablePreFrame(frame, "other", 50_000L))
        assertNull(DemoOperationObservationState.reliablePreFrame(frame, "app", 49_999L))
        assertNull(DemoOperationObservationState.reliablePreFrame(null, "app", 50_000L))
    }
}
