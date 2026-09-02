package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppUpdateControllerTest {

    @Test
    fun updateAvailableAndFlexibleAllowedStartsUpdateFlow() {
        assertEquals(
            InAppUpdateAction.START_UPDATE_FLOW,
            InAppUpdatePolicy.decide(
                availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                flexibleUpdateAllowed = true,
                installStatus = InAppInstallStatus.OTHER,
                updateFlowLaunchedThisProcess = false
            )
        )
    }

    @Test
    fun updateAvailableWithoutFlexibleAllowanceDoesNothing() {
        assertEquals(
            InAppUpdateAction.NO_ACTION,
            InAppUpdatePolicy.decide(
                availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                flexibleUpdateAllowed = false,
                installStatus = InAppInstallStatus.OTHER,
                updateFlowLaunchedThisProcess = false
            )
        )
    }

    @Test
    fun unavailableUpdateDoesNothing() {
        assertEquals(
            InAppUpdateAction.NO_ACTION,
            InAppUpdatePolicy.decide(
                availability = InAppUpdateAvailability.OTHER,
                flexibleUpdateAllowed = true,
                installStatus = InAppInstallStatus.OTHER,
                updateFlowLaunchedThisProcess = false
            )
        )
    }

    @Test
    fun alreadyPromptedUpdateDoesNothingThisProcess() {
        assertEquals(
            InAppUpdateAction.NO_ACTION,
            InAppUpdatePolicy.decide(
                availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                flexibleUpdateAllowed = true,
                installStatus = InAppInstallStatus.OTHER,
                updateFlowLaunchedThisProcess = true
            )
        )
    }

    @Test
    fun userCancelledFlowDoesNotRepromptThisProcess() {
        // The flag flips before the Play dialog launches, so the cancel path
        // of the launcher result keeps the flow suppressed automatically.
        val state = InAppUpdateFlowState()
        state.markUpdateFlowLaunched()
        assertEquals(
            InAppUpdateAction.NO_ACTION,
            state.decide(
                availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                flexibleUpdateAllowed = true,
                installStatus = InAppInstallStatus.OTHER
            )
        )
    }

    @Test
    fun downloadedInstallStatusPromptsRestartSnackbar() {
        // Even after the flow already launched this process, a downloaded
        // update still asks for a restart.
        val state = InAppUpdateFlowState()
        state.markUpdateFlowLaunched()
        assertEquals(
            InAppUpdateAction.SHOW_RESTART_SNACKBAR,
            state.decide(
                availability = InAppUpdateAvailability.OTHER,
                flexibleUpdateAllowed = false,
                installStatus = InAppInstallStatus.DOWNLOADED
            )
        )
    }

    @Test
    fun downloadedPromptWinsOverStartingAnotherUpdateFlow() {
        assertEquals(
            InAppUpdateAction.SHOW_RESTART_SNACKBAR,
            InAppUpdatePolicy.decide(
                availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                flexibleUpdateAllowed = true,
                installStatus = InAppInstallStatus.DOWNLOADED,
                updateFlowLaunchedThisProcess = false
            )
        )
    }

    @Test
    fun downloadedRestartPromptIsRepeatable() {
        val state = InAppUpdateFlowState()
        repeat(3) {
            assertEquals(
                InAppUpdateAction.SHOW_RESTART_SNACKBAR,
                state.decide(
                    availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                    flexibleUpdateAllowed = true,
                    installStatus = InAppInstallStatus.DOWNLOADED
                )
            )
        }
    }

    @Test
    fun processScopeKeepsFlowSuppressedAcrossActivityRecreation() {
        // A config change (dark mode, density, locale) recreates the Activity
        // and its controller; both generations must observe the same
        // process-scoped state so the Play dialog never auto-starts twice
        // within one process.
        val firstGeneration = InAppUpdateProcessScope.flowState
        firstGeneration.markUpdateFlowLaunched()
        val recreatedGeneration = InAppUpdateProcessScope.flowState
        assertSame(firstGeneration, recreatedGeneration)
        assertEquals(
            InAppUpdateAction.NO_ACTION,
            recreatedGeneration.decide(
                availability = InAppUpdateAvailability.UPDATE_AVAILABLE,
                flexibleUpdateAllowed = true,
                installStatus = InAppInstallStatus.OTHER
            )
        )
    }

    @Test
    fun updateFlowLaunchedFlagIsSticky() {
        val state = InAppUpdateFlowState()
        state.markUpdateFlowLaunched()
        state.markUpdateFlowLaunched()
        assertTrue(state.updateFlowLaunchedThisProcess)
    }
}
