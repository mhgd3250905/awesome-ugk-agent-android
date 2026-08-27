package com.ugk.pi.android

/**
 * Host-independent contract for Android AccessibilityService screen control.
 *
 * The SDK owns the Agent tools and the Skill instructions. A host only supplies
 * this backend, which keeps the Android service instance and host-specific
 * overlay filtering out of the reusable Skill module.
 */
interface ScreenAutomationBackend {
    fun readUiTree(
        sessionId: String,
        maxDepth: Int = ScreenAutomationLimits.DEFAULT_MAX_DEPTH,
        maxNodes: Int = ScreenAutomationLimits.DEFAULT_MAX_NODES
    ): ScreenReadResult

    suspend fun performAction(
        sessionId: String,
        request: ScreenActionRequest
    ): ScreenOperationResult

    suspend fun performGesture(request: ScreenGestureRequest): ScreenOperationResult

    suspend fun pressKey(request: ScreenKeyRequest): ScreenOperationResult

    fun performGlobalAction(request: ScreenGlobalActionRequest): ScreenOperationResult
}

/**
 * 可选的视觉兜底能力。单独定义接口，避免破坏已有宿主对
 * [ScreenAutomationBackend] 的实现与二进制兼容性。
 */
interface ScreenVisualAutomationBackend {
    suspend fun captureVisualObservation(sessionId: String): ScreenVisualCaptureResult

    suspend fun performVisualGesture(
        sessionId: String,
        request: ScreenVisualGestureRequest
    ): ScreenOperationResult
}

data class ScreenActionRequest(
    val snapshotId: String?,
    val nodeId: String,
    val action: String,
    val text: String? = null
)

data class ScreenGestureRequest(
    val action: String,
    val x: Int?,
    val y: Int?
)

data class ScreenKeyRequest(
    val key: String
)

data class ScreenGlobalActionRequest(
    val action: String
)

data class ScreenVisualCaptureResult(
    val observation: ScreenVisualObservation? = null,
    val code: String = ScreenAutomationErrorCodes.OK,
    val message: String? = null
) {
    val success: Boolean
        get() = observation != null && code == ScreenAutomationErrorCodes.OK
}

/**
 * 一次截图观察。图片坐标在传给模型时会被缩放，因此视觉手势使用
 * [ScreenVisualTarget] 的 0..1 归一化坐标，而不是直接复用图片像素。
 */
data class ScreenVisualObservation(
    val observationId: String,
    val sessionId: String,
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val displayId: Int,
    val rotation: Int,
    val capturedAtEpochMillis: Long,
    val image: AgentImageContent
)

data class ScreenVisualTarget(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

data class ScreenVisualGestureRequest(
    val observationId: String?,
    val action: String,
    val target: ScreenVisualTarget,
    val targetDescription: String? = null
)

data class ScreenReadResult(
    val snapshot: ScreenUiSnapshot? = null,
    val code: String = ScreenAutomationErrorCodes.OK,
    val message: String? = null
) {
    val success: Boolean
        get() = snapshot != null && code == ScreenAutomationErrorCodes.OK
}

data class ScreenOperationResult(
    val success: Boolean,
    val code: String,
    val message: String? = null,
    val nodeId: String? = null,
    val action: String? = null,
    val snapshotId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class ScreenUiSnapshot(
    val snapshotId: String,
    val sessionId: String,
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val windowCount: Int,
    val nodeCount: Int,
    val truncated: Boolean,
    val elements: List<ScreenUiElement>
)

data class ScreenUiElement(
    val nodeId: String,
    val windowIndex: Int,
    val packageName: String,
    val type: String,
    val text: String? = null,
    val contentDesc: String? = null,
    val hint: String? = null,
    val bounds: ScreenBounds,
    val actions: List<ScreenUiAction> = emptyList(),
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    val visibleToUser: Boolean = true,
    val viewId: String? = null
)

data class ScreenUiAction(
    val id: Int,
    val name: String,
    val label: String? = null
)

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

object ScreenAutomationLimits {
    const val DEFAULT_MAX_DEPTH = 15
    const val MAX_MAX_DEPTH = 30
    const val DEFAULT_MAX_NODES = 200
    const val MAX_MAX_NODES = 500
    const val MAX_VISUAL_OBSERVATION_AGE_MILLIS = 15_000L
    const val MAX_VISUAL_IMAGE_DIMENSION = 1_280
    const val VISUAL_JPEG_QUALITY = 80
    const val MAX_TEXT_CHARS = 200
    const val MAX_CONTENT_DESCRIPTION_CHARS = 200
    const val MAX_HINT_CHARS = 100
}

object ScreenAutomationErrorCodes {
    const val OK = "OK"
    const val ACCESSIBILITY_UNAVAILABLE = "ACCESSIBILITY_UNAVAILABLE"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val SNAPSHOT_REQUIRED = "SNAPSHOT_REQUIRED"
    const val STALE_SNAPSHOT = "STALE_SNAPSHOT"
    const val WINDOW_UNAVAILABLE = "WINDOW_UNAVAILABLE"
    const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
    const val TARGET_NOT_INTERACTABLE = "TARGET_NOT_INTERACTABLE"
    const val ACTION_NOT_SUPPORTED = "ACTION_NOT_SUPPORTED"
    const val ACTION_FAILED = "ACTION_FAILED"
    const val GESTURE_REJECTED = "GESTURE_REJECTED"
    const val GESTURE_TIMEOUT = "GESTURE_TIMEOUT"
    const val KEY_UNSUPPORTED = "KEY_UNSUPPORTED"
    const val KEY_FAILED = "KEY_FAILED"
    const val GLOBAL_ACTION_UNSUPPORTED = "GLOBAL_ACTION_UNSUPPORTED"
    const val GLOBAL_ACTION_FAILED = "GLOBAL_ACTION_FAILED"
    const val VISUAL_SCREENSHOT_UNSUPPORTED = "VISUAL_SCREENSHOT_UNSUPPORTED"
    const val VISUAL_SCREENSHOT_FAILED = "VISUAL_SCREENSHOT_FAILED"
    const val VISUAL_SCREENSHOT_TIMEOUT = "VISUAL_SCREENSHOT_TIMEOUT"
    const val VISUAL_OBSERVATION_REQUIRED = "VISUAL_OBSERVATION_REQUIRED"
    const val VISUAL_OBSERVATION_STALE = "VISUAL_OBSERVATION_STALE"
    const val VISUAL_TARGET_INVALID = "VISUAL_TARGET_INVALID"
}

object ScreenActionNames {
    const val CLICK = "click"
    const val LONG_CLICK = "long_click"
    const val SCROLL_FORWARD = "scroll_forward"
    const val SCROLL_BACKWARD = "scroll_backward"
    const val SET_TEXT = "set_text"
    const val FOCUS = "focus"
    const val CLEAR_FOCUS = "clear_focus"

    val supported = listOf(
        CLICK,
        LONG_CLICK,
        SCROLL_FORWARD,
        SCROLL_BACKWARD,
        SET_TEXT,
        FOCUS,
        CLEAR_FOCUS
    )
}

object ScreenGestureNames {
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val SWIPE_UP = "swipe_up"
    const val SWIPE_DOWN = "swipe_down"
    const val SWIPE_LEFT = "swipe_left"
    const val SWIPE_RIGHT = "swipe_right"

    val supported = listOf(TAP, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT)
}

object ScreenGlobalActionNames {
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val NOTIFICATIONS = "notifications"
    const val QUICK_SETTINGS = "quick_settings"
    const val POWER_DIALOG = "power_dialog"
    const val LOCK_SCREEN = "lock_screen"
    const val TAKE_SCREENSHOT = "take_screenshot"

    val supported = listOf(
        BACK,
        HOME,
        RECENTS,
        NOTIFICATIONS,
        QUICK_SETTINGS,
        POWER_DIALOG,
        LOCK_SCREEN,
        TAKE_SCREENSHOT
    )
}
