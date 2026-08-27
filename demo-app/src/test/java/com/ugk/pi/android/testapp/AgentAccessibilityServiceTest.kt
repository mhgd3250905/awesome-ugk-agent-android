package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentAccessibilityServiceTest {
    @Test
    fun ignoresAccessibilityEventsFromTheHostOverlay() {
        assertNull(
            trackedExternalAccessibilityPackage(
                eventPackageName = "com.ugk.pi.android.testapp",
                ownPackageName = "com.ugk.pi.android.testapp"
            )
        )
    }

    @Test
    fun tracksExternalAccessibilityEventPackages() {
        assertEquals(
            "com.testerscommunity",
            trackedExternalAccessibilityPackage(
                eventPackageName = "com.testerscommunity",
                ownPackageName = "com.ugk.pi.android.testapp"
            )
        )
    }
}
