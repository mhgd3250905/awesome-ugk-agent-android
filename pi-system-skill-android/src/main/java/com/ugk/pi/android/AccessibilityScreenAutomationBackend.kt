package com.ugk.pi.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Supplies the host's currently connected AccessibilityService instance. */
fun interface AccessibilityServiceProvider {
    fun current(): AccessibilityService?
}

/**
 * Default Android implementation of [ScreenAutomationBackend].
 *
 * It keeps only bounded, value-based snapshots. AccessibilityNodeInfo objects
 * are never retained across Tool calls; an action re-resolves the current tree
 * and fails closed when the snapshot target is stale.
 */
class AccessibilityScreenAutomationBackend(
    private val serviceProvider: AccessibilityServiceProvider,
    private val ownPackageName: String,
    private val snapshotIdGenerator: () -> String = { UUID.randomUUID().toString() }
) : ScreenAutomationBackend {

    private val snapshotLock = Any()
    private val latestSnapshots = LinkedHashMap<String, ScreenUiSnapshot>()

    override fun readUiTree(
        sessionId: String,
        maxDepth: Int,
        maxNodes: Int
    ): ScreenReadResult {
        val service = serviceProvider.current()
            ?: return ScreenReadResult(
                code = ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE,
                message = "AccessibilityService is not connected. Check the user's accessibility setting first."
            )

        val safeDepth = maxDepth.coerceIn(0, ScreenAutomationLimits.MAX_MAX_DEPTH)
        val safeMaxNodes = maxNodes.coerceIn(1, ScreenAutomationLimits.MAX_MAX_NODES)
        val elements = mutableListOf<ScreenUiElement>()
        var nodeCount = 0
        var truncated = false
        var detectedPackage = "unknown"
        var nonOwnWindowCount = 0

        fun serializeNode(node: AccessibilityNodeInfo, depth: Int, path: String, windowIndex: Int) {
            if (nodeCount >= safeMaxNodes) {
                truncated = true
                return
            }
            if (depth > safeDepth) {
                truncated = true
                return
            }

            if (isOwnPackage(node.packageName)) {
                if (node.childCount > 0 && depth >= safeDepth) truncated = true
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        try {
                            serializeNode(child, depth + 1, "$path.$index", windowIndex)
                        } finally {
                            child.recycle()
                        }
                    }
                }
                return
            }

            nodeCount++
            elements += describeNode(node, path, windowIndex)
            if (node.childCount > 0 && depth >= safeDepth) truncated = true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    try {
                        serializeNode(child, depth + 1, "$path.$index", windowIndex)
                    } finally {
                        child.recycle()
                    }
                }
            }
        }

        val allWindows = try {
            service.windows
        } catch (error: RuntimeException) {
            return ScreenReadResult(
                code = ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE,
                message = error.message ?: "Unable to obtain AccessibilityService windows."
            )
        }
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        try {
            if (allWindows.isNotEmpty()) {
                var rootIndex = 0
                var activePackage: String? = null
                for (window in allWindows) {
                    val root = window.root ?: continue
                    try {
                        val windowPackage = root.packageName?.toString()
                        if (isOwnPackage(root.packageName)) continue
                        val isInputMethodWindow = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        if (window.isActive && !isInputMethodWindow && !windowPackage.isNullOrBlank()) {
                            activePackage = windowPackage
                        }
                        if (detectedPackage == "unknown" && !isInputMethodWindow) {
                            detectedPackage = windowPackage ?: "unknown"
                        }
                        serializeNode(root, 0, rootIndex.toString(), rootIndex)
                        rootIndex++
                        nonOwnWindowCount = rootIndex
                    } finally {
                        root.recycle()
                    }
                }
                if (nonOwnWindowCount == 0) {
                    return ScreenReadResult(
                        code = ScreenAutomationErrorCodes.WINDOW_UNAVAILABLE,
                        message = "No non-host accessibility window is available. The host overlay cannot be used as a screen target."
                    )
                }
                if (activePackage != null) detectedPackage = activePackage
            } else {
                val fallback = nonOwnActiveRoot(service)
                    ?: return ScreenReadResult(
                        code = ScreenAutomationErrorCodes.WINDOW_UNAVAILABLE,
                        message = "No non-host active accessibility window is available. The host overlay cannot be used as a screen target."
                    )
                try {
                    detectedPackage = fallback.packageName?.toString() ?: "unknown"
                    nonOwnWindowCount = 1
                    serializeNode(fallback, 0, "0", 0)
                } finally {
                    fallback.recycle()
                }
            }
        } catch (error: RuntimeException) {
            return ScreenReadResult(
                code = ScreenAutomationErrorCodes.WINDOW_UNAVAILABLE,
                message = error.message ?: "The accessibility window changed while reading the screen."
            )
        } finally {
            allWindows.forEach { it.recycle() }
        }

        val snapshot = ScreenUiSnapshot(
            snapshotId = snapshotIdGenerator(),
            sessionId = sessionId,
            packageName = detectedPackage,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            windowCount = nonOwnWindowCount,
            nodeCount = nodeCount,
            truncated = truncated,
            elements = elements.toList()
        )
        rememberSnapshot(snapshot)
        return ScreenReadResult(snapshot = snapshot)
    }

    override suspend fun performAction(
        sessionId: String,
        request: ScreenActionRequest
    ): ScreenOperationResult {
        if (request.action !in ScreenActionNames.supported || request.nodeId.isBlank()) {
            return failure(
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "Unsupported action or missing nodeId. Supported actions: ${ScreenActionNames.supported.joinToString()}.",
                nodeId = request.nodeId,
                action = request.action
            )
        }
        if (request.action == ScreenActionNames.SET_TEXT && request.text == null) {
            return failure(
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "Missing text for set_text; refusing to clear the target field implicitly.",
                nodeId = request.nodeId,
                action = request.action
            )
        }

        val snapshot = latestSnapshot(sessionId)
            ?: return failure(
                code = ScreenAutomationErrorCodes.SNAPSHOT_REQUIRED,
                message = "Call screen_read_ui_tree first and use its snapshotId.",
                nodeId = request.nodeId,
                action = request.action
            )
        if (request.snapshotId.isNullOrBlank()) {
            return failure(
                code = ScreenAutomationErrorCodes.SNAPSHOT_REQUIRED,
                message = "snapshotId from the latest screen_read_ui_tree result is required.",
                nodeId = request.nodeId,
                action = request.action
            )
        }
        if (request.snapshotId != snapshot.snapshotId) {
            return failure(
                code = ScreenAutomationErrorCodes.STALE_SNAPSHOT,
                message = "The UI snapshot is stale. Read the screen again before acting.",
                nodeId = request.nodeId,
                action = request.action,
                snapshotId = snapshot.snapshotId
            )
        }

        val expectedElement = snapshot.elements.firstOrNull { it.nodeId == request.nodeId }
            ?: return failure(
                code = ScreenAutomationErrorCodes.NODE_NOT_FOUND,
                message = "Node ${request.nodeId} was not present in the latest snapshot. Read the screen again.",
                nodeId = request.nodeId,
                action = request.action,
                snapshotId = snapshot.snapshotId
            )

        val service = serviceProvider.current()
            ?: return failure(
                code = ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE,
                message = "AccessibilityService is not connected.",
                nodeId = request.nodeId,
                action = request.action,
                snapshotId = snapshot.snapshotId
            )

        val path = parseScreenNodePath(request.nodeId)
            ?: return failure(
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "Invalid nodeId. Use the exact nodeId returned by screen_read_ui_tree.",
                nodeId = request.nodeId,
                action = request.action,
                snapshotId = snapshot.snapshotId
            )
        val resolved = runCatching { resolveCurrentTarget(service, path) }.getOrNull()
            ?: return failure(
                code = ScreenAutomationErrorCodes.NODE_NOT_FOUND,
                message = "Node ${request.nodeId} is no longer available. Read the screen again.",
                nodeId = request.nodeId,
                action = request.action,
                snapshotId = snapshot.snapshotId
            )

        try {
            val currentElement = describeNode(resolved.node, request.nodeId, path.rootIndex)
            if (!sameTarget(expectedElement, currentElement)) {
                return failure(
                    code = ScreenAutomationErrorCodes.STALE_SNAPSHOT,
                    message = "The target changed after the screen was read. Read the screen again.",
                    nodeId = request.nodeId,
                    action = request.action,
                    snapshotId = snapshot.snapshotId
                )
            }

            if (!currentElement.enabled || !currentElement.visibleToUser) {
                return failure(
                    code = ScreenAutomationErrorCodes.TARGET_NOT_INTERACTABLE,
                    message = "The target is disabled or not visible to the user.",
                    nodeId = request.nodeId,
                    action = request.action,
                    snapshotId = snapshot.snapshotId
                )
            }

            if (!supportsAction(resolved.node, request.action)) {
                return failure(
                    code = ScreenAutomationErrorCodes.ACTION_NOT_SUPPORTED,
                    message = "The target does not expose action ${request.action}.",
                    nodeId = request.nodeId,
                    action = request.action,
                    snapshotId = snapshot.snapshotId,
                    metadata = mapOf("supportedActions" to currentElement.actions.joinToString { it.name })
                )
            }

            val success = runCatching {
                when (request.action) {
                    ScreenActionNames.CLICK -> resolved.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ScreenActionNames.LONG_CLICK -> resolved.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    ScreenActionNames.SCROLL_FORWARD -> resolved.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    ScreenActionNames.SCROLL_BACKWARD -> resolved.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    ScreenActionNames.FOCUS -> resolved.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    ScreenActionNames.CLEAR_FOCUS -> resolved.node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    ScreenActionNames.SET_TEXT -> {
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                request.text
                            )
                        }
                        resolved.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                    else -> false
                }
            }.getOrDefault(false)
            return if (success) {
                ScreenOperationResult(
                    success = true,
                    code = ScreenAutomationErrorCodes.OK,
                    nodeId = request.nodeId,
                    action = request.action,
                    snapshotId = snapshot.snapshotId
                )
            } else {
                failure(
                    code = ScreenAutomationErrorCodes.ACTION_FAILED,
                    message = "AccessibilityService rejected action ${request.action}.",
                    nodeId = request.nodeId,
                    action = request.action,
                    snapshotId = snapshot.snapshotId
                )
            }
        } finally {
            if (resolved.node !== resolved.root) resolved.node.recycle()
            resolved.root.recycle()
        }
    }

    override suspend fun performGesture(request: ScreenGestureRequest): ScreenOperationResult {
        val service = serviceProvider.current()
            ?: return failure(
                code = ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE,
                message = "AccessibilityService is not connected.",
                action = request.action
            )
        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        val coordinates = resolveScreenGestureCoordinates(request.action, request.x, request.y, width, height)
            ?: return failure(
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "Use a supported gesture and coordinates inside the current screen bounds (${width}x${height}).",
                action = request.action
            )

        val path = Path().apply {
            moveTo(coordinates.startX.toFloat(), coordinates.startY.toFloat())
            if (coordinates.startX != coordinates.endX || coordinates.startY != coordinates.endY) {
                lineTo(coordinates.endX.toFloat(), coordinates.endY.toFloat())
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    coordinates.durationMillis
                )
            )
            .build()
        return when (dispatchGesture(service, gesture)) {
            GestureDispatchOutcome.COMPLETED -> ScreenOperationResult(
                success = true,
                code = ScreenAutomationErrorCodes.OK,
                action = request.action,
                metadata = mapOf(
                    "x" to coordinates.startX.toString(),
                    "y" to coordinates.startY.toString(),
                    "endX" to coordinates.endX.toString(),
                    "endY" to coordinates.endY.toString(),
                    "screenWidth" to width.toString(),
                    "screenHeight" to height.toString()
                )
            )
            GestureDispatchOutcome.REJECTED,
            GestureDispatchOutcome.CANCELLED -> failure(
                code = ScreenAutomationErrorCodes.GESTURE_REJECTED,
                message = "AccessibilityService cancelled or rejected the gesture.",
                action = request.action
            )
            GestureDispatchOutcome.TIMEOUT -> failure(
                code = ScreenAutomationErrorCodes.GESTURE_TIMEOUT,
                message = "Timed out waiting for AccessibilityService gesture completion.",
                action = request.action
            )
        }
    }

    override suspend fun pressKey(request: ScreenKeyRequest): ScreenOperationResult {
        if (request.key != "enter") {
            return failure(
                code = ScreenAutomationErrorCodes.KEY_UNSUPPORTED,
                message = "Only key='enter' is supported.",
                action = request.key
            )
        }
        val service = serviceProvider.current()
            ?: return failure(
                code = ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE,
                message = "AccessibilityService is not connected.",
                action = request.key
            )

        if (Build.VERSION.SDK_INT >= 30) {
            val focusNode = findNonOwnFocusedInput(service)
                ?: return failure(
                    code = ScreenAutomationErrorCodes.KEY_FAILED,
                    message = "No focused input field is available in a non-host accessibility window.",
                    action = request.key
                )
            return try {
                val success = focusNode.performAction(ACTION_IME_ACTION)
                if (success) {
                    ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.key, metadata = mapOf("method" to "ime_action"))
                } else {
                    failure(ScreenAutomationErrorCodes.KEY_FAILED, "The focused input rejected the IME action.", request.key)
                }
            } finally {
                focusNode.recycle()
            }
        }

        return failure(
            code = ScreenAutomationErrorCodes.KEY_UNSUPPORTED,
            message = "The Accessibility IME action requires Android API 30 or newer; refusing an unverified coordinate fallback.",
            action = request.key
        )
    }

    override fun performGlobalAction(request: ScreenGlobalActionRequest): ScreenOperationResult {
        val service = serviceProvider.current()
            ?: return failure(
                code = ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE,
                message = "AccessibilityService is not connected.",
                action = request.action
            )
        if (request.action !in ScreenGlobalActionNames.supported) {
            return failure(
                code = ScreenAutomationErrorCodes.GLOBAL_ACTION_UNSUPPORTED,
                message = "Unsupported global action. Supported actions: ${ScreenGlobalActionNames.supported.joinToString()}.",
                action = request.action
            )
        }
        if (request.action == ScreenGlobalActionNames.LOCK_SCREEN ||
            request.action == ScreenGlobalActionNames.TAKE_SCREENSHOT
        ) {
            if (Build.VERSION.SDK_INT < 28) {
                return failure(
                    code = ScreenAutomationErrorCodes.GLOBAL_ACTION_UNSUPPORTED,
                    message = "${request.action} requires Android API 28 or newer.",
                    action = request.action
                )
            }
        }

        val action = when (request.action) {
            ScreenGlobalActionNames.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ScreenGlobalActionNames.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ScreenGlobalActionNames.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            ScreenGlobalActionNames.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            ScreenGlobalActionNames.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            ScreenGlobalActionNames.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            ScreenGlobalActionNames.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            ScreenGlobalActionNames.TAKE_SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return failure(ScreenAutomationErrorCodes.GLOBAL_ACTION_UNSUPPORTED, "Unsupported global action.", request.action)
        }
        return if (runCatching { service.performGlobalAction(action) }.getOrDefault(false)) {
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)
        } else {
            failure(ScreenAutomationErrorCodes.GLOBAL_ACTION_FAILED, "AccessibilityService rejected the global action.", request.action)
        }
    }

    private fun rememberSnapshot(snapshot: ScreenUiSnapshot) {
        synchronized(snapshotLock) {
            latestSnapshots[snapshot.sessionId] = snapshot
            while (latestSnapshots.size > MAX_SNAPSHOT_SESSIONS) {
                latestSnapshots.entries.firstOrNull()?.let { latestSnapshots.remove(it.key) }
            }
        }
    }

    private fun latestSnapshot(sessionId: String): ScreenUiSnapshot? = synchronized(snapshotLock) {
        latestSnapshots[sessionId]
    }

    /**
     * Returns the active root only when it is not owned by the host. This is
     * intentionally fail-closed for the host overlay: an expanded overlay can
     * be the platform's active window even while another app is visible behind
     * it.
     */
    private fun nonOwnActiveRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        if (isOwnPackage(root.packageName)) {
            root.recycle()
            return null
        }
        return root
    }

    /** Finds a focused input from an external window, never from the host overlay. */
    private fun findNonOwnFocusedInput(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        try {
            for (window in windows) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                try {
                    if (isOwnPackage(root.packageName)) continue
                    val candidate = runCatching {
                        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    }.getOrNull() ?: continue
                    try {
                        if (isOwnPackage(candidate.packageName)) continue
                        return AccessibilityNodeInfo.obtain(candidate)
                    } finally {
                        candidate.recycle()
                    }
                } finally {
                    root.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }

        val fallbackRoot = nonOwnActiveRoot(service) ?: return null
        try {
            val candidate = runCatching {
                fallbackRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }.getOrNull() ?: return null
            try {
                if (isOwnPackage(candidate.packageName)) return null
                return AccessibilityNodeInfo.obtain(candidate)
            } finally {
                candidate.recycle()
            }
        } finally {
            fallbackRoot.recycle()
        }
    }

    private fun resolveCurrentTarget(
        service: AccessibilityService,
        path: ScreenNodePath
    ): ResolvedTarget? {
        val windows = service.windows
        var seenIndex = 0
        var selectedRoot: AccessibilityNodeInfo? = null
        var selectedNode: AccessibilityNodeInfo? = null
        try {
            for (window in windows) {
                val root = window.root ?: continue
                var keepRoot = false
                try {
                    if (isOwnPackage(root.packageName)) continue
                    if (seenIndex == path.rootIndex) {
                        val node = if (path.childIndices.isEmpty()) {
                            root
                        } else {
                            findNodeByPath(root, path.childIndices)
                        }
                        if (node != null) {
                            selectedRoot = root
                            selectedNode = node
                            keepRoot = true
                            break
                        }
                        return null
                    }
                    seenIndex++
                } finally {
                    if (!keepRoot) root.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
        val root = selectedRoot ?: return null
        val node = selectedNode ?: run {
            root.recycle()
            return null
        }
        return ResolvedTarget(root, node)
    }

    private fun findNodeByPath(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current = root
        var ownsCurrent = false
        try {
            for (index in path) {
                val child = current.getChild(index) ?: return null
                if (ownsCurrent) current.recycle()
                current = child
                ownsCurrent = true
            }
            ownsCurrent = false
            return current
        } finally {
            if (ownsCurrent) current.recycle()
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo, nodeId: String, windowIndex: Int): ScreenUiElement {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return ScreenUiElement(
            nodeId = nodeId,
            windowIndex = windowIndex,
            packageName = node.packageName?.toString() ?: "unknown",
            type = node.className?.toString()?.substringAfterLast('.') ?: "",
            text = node.text?.toString()?.take(ScreenAutomationLimits.MAX_TEXT_CHARS),
            contentDesc = node.contentDescription?.toString()?.take(ScreenAutomationLimits.MAX_CONTENT_DESCRIPTION_CHARS),
            hint = if (Build.VERSION.SDK_INT >= 26) {
                node.hintText?.toString()?.take(ScreenAutomationLimits.MAX_HINT_CHARS)
            } else {
                null
            },
            bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            actions = describeActions(node),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            focusable = node.isFocusable,
            visibleToUser = node.isVisibleToUser,
            viewId = node.viewIdResourceName
        )
    }

    private fun isOwnPackage(packageName: CharSequence?): Boolean =
        packageName?.toString() == ownPackageName

    private fun sameTarget(expected: ScreenUiElement, current: ScreenUiElement): Boolean {
        if (expected.packageName != current.packageName || expected.type != current.type) return false
        val expectedViewId = expected.viewId
        if (!expectedViewId.isNullOrBlank() || !current.viewId.isNullOrBlank()) {
            return expectedViewId == current.viewId &&
                expected.bounds == current.bounds &&
                expected.text == current.text &&
                expected.contentDesc == current.contentDesc
        }
        return expected.bounds == current.bounds &&
            expected.text == current.text &&
            expected.contentDesc == current.contentDesc
    }

    private fun describeActions(node: AccessibilityNodeInfo): List<ScreenUiAction> {
        val actions = node.actionList.map { action ->
            ScreenUiAction(
                id = action.id,
                name = actionName(action.id),
                label = action.label?.toString()?.take(100)
            )
        }.toMutableList()
        val actionIds = actions.map { it.id }.toMutableSet()

        fun addIfMissing(id: Int, name: String) {
            if (actionIds.add(id)) actions += ScreenUiAction(id = id, name = name)
        }

        if (node.isClickable) addIfMissing(AccessibilityNodeInfo.ACTION_CLICK, ScreenActionNames.CLICK)
        if (node.isScrollable) {
            addIfMissing(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, ScreenActionNames.SCROLL_FORWARD)
            addIfMissing(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, ScreenActionNames.SCROLL_BACKWARD)
        }
        if (node.isEditable) addIfMissing(AccessibilityNodeInfo.ACTION_SET_TEXT, ScreenActionNames.SET_TEXT)
        if (node.isFocusable) addIfMissing(AccessibilityNodeInfo.ACTION_FOCUS, ScreenActionNames.FOCUS)
        return actions
    }

    private fun supportsAction(node: AccessibilityNodeInfo, action: String): Boolean {
        val actionIds = node.actionList.map { it.id }.toSet()
        return when (action) {
            ScreenActionNames.CLICK -> AccessibilityNodeInfo.ACTION_CLICK in actionIds || node.isClickable
            ScreenActionNames.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK in actionIds
            ScreenActionNames.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds || node.isScrollable
            ScreenActionNames.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actionIds || node.isScrollable
            ScreenActionNames.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT in actionIds || node.isEditable
            ScreenActionNames.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS in actionIds || node.isFocusable
            ScreenActionNames.CLEAR_FOCUS -> AccessibilityNodeInfo.ACTION_CLEAR_FOCUS in actionIds
            else -> false
        }
    }

    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_FOCUS -> ScreenActionNames.FOCUS
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> ScreenActionNames.CLEAR_FOCUS
        AccessibilityNodeInfo.ACTION_CLICK -> ScreenActionNames.CLICK
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> ScreenActionNames.LONG_CLICK
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> ScreenActionNames.SCROLL_FORWARD
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> ScreenActionNames.SCROLL_BACKWARD
        AccessibilityNodeInfo.ACTION_SET_TEXT -> ScreenActionNames.SET_TEXT
        else -> "action_$id"
    }

    private fun failure(
        code: String,
        message: String,
        action: String? = null,
        nodeId: String? = null,
        snapshotId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): ScreenOperationResult = ScreenOperationResult(
        success = false,
        code = code,
        message = message,
        nodeId = nodeId,
        action = action,
        snapshotId = snapshotId,
        metadata = metadata
    )

    private enum class GestureDispatchOutcome {
        COMPLETED,
        REJECTED,
        CANCELLED,
        TIMEOUT
    }

    private suspend fun dispatchGesture(
        service: AccessibilityService,
        gesture: GestureDescription
    ): GestureDispatchOutcome = withTimeoutOrNull(GESTURE_CALLBACK_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)

            fun complete(outcome: GestureDispatchOutcome) {
                if (!completed.compareAndSet(false, true)) return
                if (continuation.isActive) continuation.resume(outcome)
            }

            val accepted = runCatching {
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        complete(GestureDispatchOutcome.COMPLETED)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        complete(GestureDispatchOutcome.CANCELLED)
                    }
                }, null)
            }.getOrDefault(false)
            if (!accepted) complete(GestureDispatchOutcome.REJECTED)
            continuation.invokeOnCancellation { completed.set(true) }
        }
    } ?: GestureDispatchOutcome.TIMEOUT

    private data class ResolvedTarget(
        val root: AccessibilityNodeInfo,
        val node: AccessibilityNodeInfo
    )

    private companion object {
        const val ACTION_IME_ACTION = 0x00200000
        const val GESTURE_CALLBACK_TIMEOUT_MILLIS = 2_000L
        const val MAX_SNAPSHOT_SESSIONS = 16
    }
}

internal data class ScreenGestureCoordinates(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMillis: Long
)

internal fun resolveScreenGestureCoordinates(
    action: String,
    x: Int?,
    y: Int?,
    screenWidth: Int,
    screenHeight: Int
): ScreenGestureCoordinates? {
    if (x == null || y == null || screenWidth <= 0 || screenHeight <= 0) return null
    if (x !in 0 until screenWidth || y !in 0 until screenHeight) return null
    val horizontalDistance = (screenWidth / 3).coerceAtLeast(1)
    val verticalDistance = (screenHeight / 3).coerceAtLeast(1)
    val end = when (action) {
        ScreenGestureNames.TAP,
        ScreenGestureNames.LONG_PRESS -> x to y
        ScreenGestureNames.SWIPE_UP -> x to (y - verticalDistance).coerceAtLeast(0)
        ScreenGestureNames.SWIPE_DOWN -> x to (y + verticalDistance).coerceAtMost(screenHeight - 1)
        ScreenGestureNames.SWIPE_LEFT -> (x - horizontalDistance).coerceAtLeast(0) to y
        ScreenGestureNames.SWIPE_RIGHT -> (x + horizontalDistance).coerceAtMost(screenWidth - 1) to y
        else -> return null
    }
    if (action.startsWith("swipe_") && end.first == x && end.second == y) return null
    return ScreenGestureCoordinates(
        startX = x,
        startY = y,
        endX = end.first,
        endY = end.second,
        durationMillis = when (action) {
            ScreenGestureNames.TAP -> 50L
            ScreenGestureNames.LONG_PRESS -> 500L
            else -> 300L
        }
    )
}

internal data class ScreenNodePath(
    val rootIndex: Int,
    val childIndices: List<Int>
)

internal fun parseScreenNodePath(nodeId: String): ScreenNodePath? {
    val trimmed = nodeId.trim()
    if (trimmed.isEmpty()) return null
    val segments = trimmed.split('.')
    if (segments.any { it.toIntOrNull() == null }) return null
    val indices = segments.map { it.toInt() }
    if (indices.any { it < 0 }) return null
    return ScreenNodePath(indices.first(), indices.drop(1))
}
