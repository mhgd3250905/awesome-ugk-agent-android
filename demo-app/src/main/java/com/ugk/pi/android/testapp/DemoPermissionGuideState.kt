package com.ugk.pi.android.testapp

internal enum class PermissionGuideStep { ACCESSIBILITY, OVERLAY, CAMERA, NOTIFICATIONS, BACKGROUND }

/** One queue per genuine foreground entry; declined steps never loop. */
internal class DemoPermissionGuideState {
    var generation = -1L
    var queue = emptyList<PermissionGuideStep>()
    var position = 0
    var closed = false
    var externalFlow = false
    var awaitingPermission = false
    var awaitingNotificationAppPermission = false
    var pendingRuntimePermission: String? = null
    var runtimeSettingsFallback = false
    var awaitingBackgroundSettings = false

    fun completeRuntimeResult(granted: Boolean, shouldShowRationale: Boolean,
                              continueWithNotificationChannel: Boolean) {
        pendingRuntimePermission = null
        if (!granted && !shouldShowRationale && !runtimeSettingsFallback) {
            awaitingPermission = false
            awaitingNotificationAppPermission = false
            runtimeSettingsFallback = true
        } else completeAttempt(continueWithNotificationChannel)
    }

    /** A newly enabled app may still have a separately blocked reminder channel. */
    fun completeAttempt(continueWithNotificationChannel: Boolean) {
        if (awaitingPermission && !awaitingBackgroundSettings && !(awaitingNotificationAppPermission && continueWithNotificationChannel)) advance()
        awaitingPermission = false
        awaitingBackgroundSettings = false
        awaitingNotificationAppPermission = false
        pendingRuntimePermission = null
        runtimeSettingsFallback = false
    }

    fun enter(nextGeneration: Long, missing: List<PermissionGuideStep>) {
        if (generation != nextGeneration && !externalFlow) {
            queue = missing
            position = 0
            closed = false
            runtimeSettingsFallback = false
            pendingRuntimePermission = null
        }
        generation = nextGeneration
        externalFlow = false
    }

    fun current(missing: Set<PermissionGuideStep>): PermissionGuideStep? {
        if (closed || awaitingPermission) return null
        while (position < queue.size && queue[position] !in missing) advance()
        return queue.getOrNull(position)
    }

    fun advance() { position++; runtimeSettingsFallback = false }
    fun close() { closed = true }
}

/** Counts visible activities, without a timing heuristic for configuration changes. */
internal class PermissionGuideForegroundState {
    var generation = 0L
        private set
    private var started = 0
    private var recreating = false
    fun start() {
        if (started == 0 && !recreating) generation++
        recreating = false
        started++
    }
    fun stop(changingConfiguration: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) recreating = changingConfiguration
    }
}
