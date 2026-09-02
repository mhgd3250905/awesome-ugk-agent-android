package com.ugk.pi.android.testapp

import android.app.Activity
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Play update availability translated into a pure decision input.
 */
enum class InAppUpdateAvailability { UPDATE_AVAILABLE, OTHER }

/**
 * Play install status translated into a pure decision input. Only the state
 * this flow acts on is modelled.
 */
enum class InAppInstallStatus { DOWNLOADED, OTHER }

/**
 * The single action the host Activity should perform for one Play check.
 */
enum class InAppUpdateAction { START_UPDATE_FLOW, SHOW_RESTART_SNACKBAR, NO_ACTION }

/**
 * Pure decision table for the FLEXIBLE in-app update flow.
 *
 * Inputs mirror the Play AppUpdateInfo fields; outputs stay framework-free
 * so the anti-nagging policy is testable without Android or Play.
 */
object InAppUpdatePolicy {

    /**
     * A downloaded-but-not-installed update wins over everything: the
     * restart prompt may repeat on every foreground check. The Play dialog
     * itself only auto-starts once per process, and a user cancel keeps it
     * suppressed until the next process.
     */
    fun decide(
        availability: InAppUpdateAvailability,
        flexibleUpdateAllowed: Boolean,
        installStatus: InAppInstallStatus,
        updateFlowLaunchedThisProcess: Boolean
    ): InAppUpdateAction = when {
        installStatus == InAppInstallStatus.DOWNLOADED ->
            InAppUpdateAction.SHOW_RESTART_SNACKBAR
        availability == InAppUpdateAvailability.UPDATE_AVAILABLE &&
            flexibleUpdateAllowed &&
            !updateFlowLaunchedThisProcess ->
            InAppUpdateAction.START_UPDATE_FLOW
        else -> InAppUpdateAction.NO_ACTION
    }
}

/**
 * Bookkeeping backing [InAppUpdatePolicy] decisions. Pure Kotlin, safe for
 * JVM unit tests.
 */
class InAppUpdateFlowState {
    var updateFlowLaunchedThisProcess: Boolean = false
        private set

    fun markUpdateFlowLaunched() {
        updateFlowLaunchedThisProcess = true
    }

    fun decide(
        availability: InAppUpdateAvailability,
        flexibleUpdateAllowed: Boolean,
        installStatus: InAppInstallStatus
    ): InAppUpdateAction = InAppUpdatePolicy.decide(
        availability = availability,
        flexibleUpdateAllowed = flexibleUpdateAllowed,
        installStatus = installStatus,
        updateFlowLaunchedThisProcess = updateFlowLaunchedThisProcess
    )
}

/**
 * Process-wide holder for the anti-nag state: Activity recreation (config
 * changes such as dark mode) rebuilds the controller, so the launched flag
 * must live at process scope to keep the Play dialog from auto-starting
 * twice within one process.
 */
object InAppUpdateProcessScope {
    val flowState: InAppUpdateFlowState = InAppUpdateFlowState()
}

/**
 * Thin Google Play In-App Updates (FLEXIBLE) shell around
 * [InAppUpdateFlowState].
 *
 * All Play and Material calls live here. The host registers the result
 * launcher during construction (before STARTED), calls [checkOnResume] from
 * onResume, forwards a non-OK launcher result to [onUpdateFlowAbandoned] and
 * [release] from onDestroy. The anti-nag state defaults to the process-wide
 * [InAppUpdateProcessScope.flowState] so Activity recreation cannot re-arm
 * the Play dialog. Sideloaded builds and devices without Google Play stay
 * silent no-ops: every Play interaction is guarded and the appUpdateInfo
 * task failure is swallowed with a log line only.
 */
class InAppUpdateController(
    private val activity: Activity,
    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest>,
    state: InAppUpdateFlowState = InAppUpdateProcessScope.flowState,
) {
    private val state = state
    // Per-controller: each Activity instance owns its own manager and
    // listener, so pairing them in the process-shared state would let one
    // generation's unregister clear another generation's registration.
    private var downloadListenerRegistered = false
    private val appUpdateManager: AppUpdateManager? = try {
        AppUpdateManagerFactory.create(activity)
    } catch (error: Exception) {
        Log.w(TAG, "Unable to create AppUpdateManager", error)
        null
    }
    private val downloadListener = InstallStateUpdatedListener { installState ->
        if (installState.installStatus() == InstallStatus.DOWNLOADED) {
            activity.runOnUiThread { showRestartSnackbar() }
        }
    }

    /**
     * Foreground check: starts the Play update dialog at most once per
     * process, re-prompts a downloaded update's restart snackbar otherwise.
     */
    fun checkOnResume() {
        val manager = appUpdateManager ?: return
        playSilently("query appUpdateInfo") {
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    val action = state.decide(
                        availability = toAvailability(info),
                        flexibleUpdateAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                        installStatus = toInstallStatus(info)
                    )
                    activity.runOnUiThread { dispatch(action, info) }
                }
                .addOnFailureListener { error ->
                    // Sideloaded builds and devices without Play never report
                    // an available update; this failure is expected and must
                    // stay invisible to the user.
                    Log.w(TAG, "In-app update availability check failed", error)
                }
        }
    }

    /**
     * The user dismissed the Play dialog (or the flow failed). The prompt
     * stays suppressed for this process and nothing is left downloading.
     */
    fun onUpdateFlowAbandoned() {
        unregisterDownloadListener()
    }

    fun release() {
        unregisterDownloadListener()
    }

    private fun dispatch(action: InAppUpdateAction, info: AppUpdateInfo) {
        when (action) {
            InAppUpdateAction.START_UPDATE_FLOW -> launchUpdateFlow(info)
            InAppUpdateAction.SHOW_RESTART_SNACKBAR -> showRestartSnackbar()
            InAppUpdateAction.NO_ACTION -> Unit
        }
    }

    private fun launchUpdateFlow(info: AppUpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        // The flag flips before launching so a cancel or failure can never
        // re-trigger the dialog within this process.
        if (state.updateFlowLaunchedThisProcess) return
        state.markUpdateFlowLaunched()
        val manager = appUpdateManager ?: return
        registerDownloadListener(manager)
        playSilently("start flexible update flow") {
            manager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
            )
        }
    }

    private fun registerDownloadListener(manager: AppUpdateManager) {
        if (downloadListenerRegistered) return
        playSilently("register install state listener") {
            manager.registerListener(downloadListener)
            downloadListenerRegistered = true
        }
    }

    private fun unregisterDownloadListener() {
        if (!downloadListenerRegistered) return
        val manager = appUpdateManager ?: return
        playSilently("unregister install state listener") {
            manager.unregisterListener(downloadListener)
            downloadListenerRegistered = false
        }
    }

    private fun showRestartSnackbar() {
        if (activity.isFinishing || activity.isDestroyed) return
        val anchor = activity.findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(anchor, "新版本已下载，重启后完成安装", Snackbar.LENGTH_INDEFINITE)
            .setAction("重启") { completeUpdate() }
            .show()
    }

    private fun completeUpdate() {
        val manager = appUpdateManager ?: return
        playSilently("complete downloaded update") {
            manager.completeUpdate()
        }
    }

    private fun toAvailability(info: AppUpdateInfo): InAppUpdateAvailability =
        if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            InAppUpdateAvailability.UPDATE_AVAILABLE
        } else {
            InAppUpdateAvailability.OTHER
        }

    private fun toInstallStatus(info: AppUpdateInfo): InAppInstallStatus =
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            InAppInstallStatus.DOWNLOADED
        } else {
            InAppInstallStatus.OTHER
        }

    private inline fun playSilently(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            // Play core can throw synchronously on devices without a usable
            // Play app; those installs must remain silent no-ops.
            Log.w(TAG, "In-app update failed to $operation", error)
        }
    }

    private companion object {
        private const val TAG = "InAppUpdateController"
    }
}
