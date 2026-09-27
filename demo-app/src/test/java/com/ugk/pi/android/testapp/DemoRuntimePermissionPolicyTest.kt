package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoRuntimePermissionPolicyTest {
    @Test fun historyAndCurrentRationaleChooseARecoverableRoute() {
        assertEquals(RuntimePermissionAction.REQUEST, DemoRuntimePermissionPolicy.decide(false, false, false, false))
        assertEquals(RuntimePermissionAction.SETTINGS, DemoRuntimePermissionPolicy.decide(false, false, true, false))
        assertEquals(RuntimePermissionAction.SETTINGS, DemoRuntimePermissionPolicy.decide(false, false, false, true))
        assertEquals(RuntimePermissionAction.REQUEST, DemoRuntimePermissionPolicy.decide(false, true, true, true))
        assertEquals(RuntimePermissionAction.GRANTED, DemoRuntimePermissionPolicy.decide(true, false, true, true))
    }
}
