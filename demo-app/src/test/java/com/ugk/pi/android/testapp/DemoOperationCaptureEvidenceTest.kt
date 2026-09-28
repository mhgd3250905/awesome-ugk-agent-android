package com.ugk.pi.android.testapp

import org.junit.Assert.*
import org.junit.Test

class DemoOperationCaptureEvidenceTest {
    @Test fun overlayWindowsAreExcludedFromExternalWindowSignature() {
        val before = demoOperationWindowSignature(7, listOf(0 to "7:app:bounds", 2 to "8:system:bounds"))
        val shifted = demoOperationWindowSignature(7, listOf(1 to "7:app:bounds", 3 to "8:system:bounds"))
        assertEquals(before, shifted)
        assertNotEquals(before, demoOperationWindowSignature(7, listOf(3 to "7:app:bounds", 1 to "8:system:bounds")))
        assertNotEquals(before, demoOperationWindowSignature(8, listOf(0 to "7:app:bounds", 2 to "8:system:bounds")))
        assertNotEquals(before, demoOperationWindowSignature(7, listOf(0 to "7:app:bounds", 2 to "8:system:changed-bounds")))
        assertNotEquals(before, demoOperationWindowSignature(7, listOf(0 to "7:app:bounds")))
    }

    @Test fun onlyKnownZeroDeltaScrollNotificationsAreIgnoredAsLayoutEvents() {
        val event = DemoOperationEvent(1, 100, 4096, "app", null, null, null, emptyList())
        assertFalse(event.isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollDeltaX = 0).isZeroMovementScrollNotification())
        assertTrue(event.copy(scrollDeltaX = 0, scrollDeltaY = 0).isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollDeltaX = 0, scrollDeltaY = 20).isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollDeltaX = -1, scrollDeltaY = 0).isZeroMovementScrollNotification())
        assertFalse(event.copy(type = 1, scrollDeltaX = 0, scrollDeltaY = 0).isZeroMovementScrollNotification())
        assertFalse(event.copy(scrollX = 0, scrollY = 0, fromIndex = 0, toIndex = 5).isZeroMovementScrollNotification())
    }
}
