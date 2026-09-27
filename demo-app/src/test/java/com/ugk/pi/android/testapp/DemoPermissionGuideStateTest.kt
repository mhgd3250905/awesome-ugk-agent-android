package com.ugk.pi.android.testapp

import org.junit.Assert.*
import org.junit.Test

class DemoPermissionGuideStateTest {
    private val all = PermissionGuideStep.entries.filter { it != PermissionGuideStep.BACKGROUND }

    @Test fun backgroundSettingsReturnDoesNotCompleteTheSuggestion() {
        val state = DemoPermissionGuideState()
        val missing = setOf(PermissionGuideStep.BACKGROUND)
        state.enter(1, missing.toList())
        repeat(2) {
            state.awaitingPermission = true
            state.awaitingBackgroundSettings = true
            state.externalFlow = true
            state.enter((it + 2).toLong(), missing.toList())
            state.completeAttempt(false)
            assertEquals(PermissionGuideStep.BACKGROUND, state.current(missing))
        }
        state.advance() // skip only this entry
        assertNull(state.current(missing))
        state.enter(4, missing.toList())
        assertEquals(PermissionGuideStep.BACKGROUND, state.current(missing))
        assertTrue(DemoBackgroundGuidancePolicy.needsReview(0))
        assertFalse(DemoBackgroundGuidancePolicy.needsReview(DemoBackgroundGuidancePolicy.VERSION))
    }

    @Test fun unknownHistoryDenialOffersSettingsOnceAndOrdinaryDenialMovesOn() {
        val state = DemoPermissionGuideState()
        val camera = setOf(PermissionGuideStep.CAMERA)
        state.enter(1, camera.toList())
        state.awaitingPermission = true
        state.pendingRuntimePermission = "camera"
        state.completeRuntimeResult(false, false, false)
        assertTrue(state.runtimeSettingsFallback)
        assertNull(state.pendingRuntimePermission)
        assertEquals(PermissionGuideStep.CAMERA, state.current(camera))
        state.awaitingPermission = true // settings returns without grant
        state.completeAttempt(false)
        assertNull(state.current(camera))
        assertFalse(state.runtimeSettingsFallback)

        state.enter(2, camera.toList())
        state.awaitingPermission = true
        state.completeRuntimeResult(false, true, false)
        assertNull(state.current(camera))
        assertFalse(state.runtimeSettingsFallback)
    }

    @Test fun runtimeGrantStillContinuesToBlockedNotificationChannel() {
        val state = DemoPermissionGuideState()
        state.enter(1, listOf(PermissionGuideStep.NOTIFICATIONS))
        state.awaitingPermission = true
        state.awaitingNotificationAppPermission = true
        state.pendingRuntimePermission = "notification"
        state.completeRuntimeResult(true, false, true)
        assertEquals(PermissionGuideStep.NOTIFICATIONS, state.current(setOf(PermissionGuideStep.NOTIFICATIONS)))
        assertFalse(state.runtimeSettingsFallback)
        assertNull(state.pendingRuntimePermission)
    }

    @Test fun newlyEnabledAppContinuesToChannelOnceButDeclinedPermissionsDoNotLoop() {
        val state = DemoPermissionGuideState()
        val notifications = setOf(PermissionGuideStep.NOTIFICATIONS)
        state.enter(1, notifications.toList())
        state.awaitingPermission = true
        state.awaitingNotificationAppPermission = true
        state.completeAttempt(continueWithNotificationChannel = true)
        assertEquals(PermissionGuideStep.NOTIFICATIONS, state.current(notifications))
        assertFalse(state.awaitingNotificationAppPermission)
        state.awaitingPermission = true // channel settings return, still blocked
        state.completeAttempt(continueWithNotificationChannel = true)
        assertNull(state.current(notifications))

        state.enter(2, notifications.toList())
        state.awaitingPermission = true
        state.awaitingNotificationAppPermission = true
        state.completeAttempt(continueWithNotificationChannel = false) // app permission denied
        assertNull(state.current(notifications))
    }

    @Test fun skipsGrantedAndDoesNotRepeatDeclinedStepsWithinEntry() {
        val state = DemoPermissionGuideState()
        state.enter(1, all.drop(1))
        assertEquals(PermissionGuideStep.OVERLAY, state.current(all.toSet()))
        state.advance()
        assertEquals(PermissionGuideStep.NOTIFICATIONS, state.current(setOf(PermissionGuideStep.NOTIFICATIONS)))
        state.advance()
        state.enter(1, all)
        assertNull(state.current(all.toSet()))
        state.enter(2, all)
        assertEquals(PermissionGuideStep.ACCESSIBILITY, state.current(all.toSet()))
    }

    @Test fun closedEntryStaysClosedUntilNextRealEntry() {
        val state = DemoPermissionGuideState()
        state.enter(1, all)
        state.close()
        state.externalFlow = true
        state.enter(2, all)
        assertNull(state.current(all.toSet()))
        state.enter(3, all)
        assertEquals(PermissionGuideStep.ACCESSIBILITY, state.current(all.toSet()))
    }

    @Test fun externalPermissionReturnPreservesQueueAndWaitsForResult() {
        val state = DemoPermissionGuideState()
        state.enter(1, all)
        state.externalFlow = true
        state.awaitingPermission = true
        state.enter(2, all)
        assertEquals(0, state.position)
        assertNull(state.current(all.toSet()))
        state.awaitingPermission = false
        state.advance()
        assertEquals(PermissionGuideStep.OVERLAY, state.current(all.toSet()))
    }

    @Test fun noMissingPermissionsMeansNoSheet() {
        val state = DemoPermissionGuideState()
        state.enter(1, emptyList())
        assertNull(state.current(emptySet()))
    }

    @Test fun foregroundCounterDistinguishesConfigAndInternalNavigationFromHome() {
        val state = PermissionGuideForegroundState()
        state.start()
        assertEquals(1L, state.generation)
        state.start() // settings activity starts before Main stops
        state.stop(false)
        assertEquals(1L, state.generation)
        state.stop(true)
        state.start() // recreation
        assertEquals(1L, state.generation)
        state.stop(false)
        state.start() // actual background entry
        assertEquals(2L, state.generation)
    }
}
