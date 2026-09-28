package com.ugk.pi.android.testapp

import org.junit.Assert.*
import org.junit.Test

class DemoOperationPostEvidenceTest {
    @Test fun pauseOrUnknownPageCannotAttachLaterObservationToEarlierEvent() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(7, "settings")
        evidence.invalidate()
        evidence.observePackage("settings")
        assertNull(evidence.pendingFor("settings"))
        assertFalse(evidence.complete(7, "settings"))
    }

    @Test fun switchingAppsClearsOldAssociationEvenWhenReturningToSameApp() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(7, "settings")
        evidence.observePackage("other")
        assertFalse(evidence.complete(7, "other"))
        evidence.observePackage("settings")
        assertFalse(evidence.complete(7, "settings"))
    }

    @Test fun onlyCurrentEventAndMatchingAppCanConsumeThePostFrameOnce() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(7, "settings")
        evidence.recordEvent(8, "settings")
        evidence.observePackage("settings") // Content events may settle the same action.
        assertEquals(8, evidence.pendingFor("settings"))
        assertFalse(evidence.complete(7, "settings"))
        assertFalse(evidence.complete(8, "other"))
        assertTrue(evidence.complete(8, "settings"))
        assertFalse(evidence.complete(8, "settings"))
    }

    @Test fun sameAppWindowNotificationsDoNotStealPendingClickPostFrame() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(5, "settings")
        evidence.recordEvent(6, "settings", isPageNotification = true)
        evidence.observePackage("settings") // content changed notification
        evidence.recordEvent(7, "settings", isPageNotification = true)
        assertEquals(5, evidence.pendingFor("settings"))
        assertTrue(evidence.complete(5, "settings"))
        assertNull(evidence.pendingFor("settings"))
    }

    @Test fun anotherClickOrScrollStillReplacesUnverifiedPriorAction() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(5, "settings")
        evidence.recordEvent(6, "settings") // scroll provenance is ambiguous: preserve boundary
        evidence.recordEvent(7, "settings", isPageNotification = true)
        assertFalse(evidence.complete(5, "settings"))
        assertTrue(evidence.complete(6, "settings"))
    }

    @Test fun isolatedWindowNotificationCanReceiveObservationWithoutBorrowingOldSegment() {
        val evidence = DemoOperationPostEvidence()
        evidence.recordEvent(5, "settings")
        evidence.invalidate() // pause or unknown screen
        evidence.recordEvent(7, "settings", isPageNotification = true)
        assertFalse(evidence.complete(5, "settings"))
        assertTrue(evidence.complete(7, "settings"))
        evidence.recordEvent(8, "other", isPageNotification = true)
        assertNull(evidence.pendingFor("settings"))
        assertTrue(evidence.complete(8, "other"))
    }
}
