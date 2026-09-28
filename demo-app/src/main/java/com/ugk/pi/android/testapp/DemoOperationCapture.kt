package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

internal data class DemoOperationPage(val packageName: String, val sensitive: Boolean, val nodes: List<DemoOperationNode> = emptyList(), val windowSignature: List<String> = emptyList())
internal data class DemoOperationImage(val bytes: ByteArray, val width: Int, val height: Int)
internal data class DemoOperationScrollObservation(val deltaX: Int?, val deltaY: Int?, val x: Int, val y: Int, val fromIndex: Int, val toIndex: Int) {
    val isZeroMovement: Boolean get() = deltaX == 0 && deltaY == 0
}

/** A reported zero displacement is a layout notification, not proof of user input. */
internal fun DemoOperationEvent.isZeroMovementScrollNotification(): Boolean =
    type == 4096 && scrollDeltaX == 0 && scrollDeltaY == 0

/** Keep external stacking order, but not layer offsets caused by our own overlay. */
internal fun demoOperationWindowSignature(activeWindowId: Int, externalWindows: List<Pair<Int, String>>): List<String> =
    listOf("active:$activeWindowId") + externalWindows.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }).map { it.second }

/** No native event/node escapes these synchronous reads. Text entry is never copied. */
internal object DemoOperationCapture {
    private val imageWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "operation-image-encoder").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    fun foregroundPackage(service: AccessibilityService, ownPackage: String): String? {
        val root = service.rootInActiveWindow
        val active = try { root?.packageName?.toString() } finally { root?.recycle() }
        if (!active.isNullOrBlank() && active != ownPackage) return active
        val windows = service.windows
        return try {
            windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) null
                else window.root?.let { node ->
                    try { node.packageName?.toString()?.takeUnless { it == ownPackage } }
                    finally { node.recycle() }
                }
            } ?: ownPackage.takeIf {
                // Host overlay focus alone does not prove that the host app is foreground.
                windows.any { window ->
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) false
                    else window.root?.let { node ->
                        try { node.packageName?.toString() == ownPackage } finally { node.recycle() }
                    } == true
                }
            }
        } finally { windows.forEach { it.recycle() } }
    }

    fun page(service: AccessibilityService, ownPackage: String, excludedPackages: Set<String> = emptySet()): DemoOperationPage? {
        val activeRoot = service.rootInActiveWindow
        val root = if (activeRoot == null || activeRoot.packageName?.toString() == ownPackage) {
            activeRoot?.recycle()
            // A focusable guided overlay owns the active root; select the top external app.
            val available = service.windows
            try {
                available.sortedByDescending { it.layer }.firstNotNullOfOrNull { window ->
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) null
                    else window.root?.let { candidate ->
                        if (candidate.packageName?.toString() == ownPackage) { candidate.recycle(); null } else candidate
                    }
                }
            } finally { available.forEach { it.recycle() } }
        } else activeRoot
        if (root == null) return null
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == ownPackage || pkg in excludedPackages) null
            else {
                var unsafe = sensitive(root, intArrayOf(0), 0)
                // Screenshots cover the display, not just the active app. Fail closed on IME or
                // an input field in another visible window, before copying any text.
                val windows = service.windows
                val externalWindows = mutableListOf<Pair<Int, String>>()
                try {
                    windows.forEach { window ->
                        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) unsafe = true
                        val other = window.root
                        if (other != null) try {
                            if (other.packageName?.toString() != ownPackage) {
                                if (sensitive(other, intArrayOf(0), 0)) unsafe = true
                                val bounds = Rect().also { window.getBoundsInScreen(it) }
                                externalWindows += window.layer to "${window.id}:${window.type}:${other.packageName}:$bounds"
                            }
                        } finally { other.recycle() }
                        else {
                            // Unknown roots cannot be assumed to belong to our overlay.
                            val bounds = Rect().also { window.getBoundsInScreen(it) }
                            externalWindows += window.layer to "${window.id}:${window.type}:unknown:$bounds"
                        }
                    }
                } finally { windows.forEach { it.recycle() } }
                DemoOperationPage(pkg, unsafe, if (unsafe) emptyList() else collectNodes(root), demoOperationWindowSignature(root.windowId, externalWindows))
            }
        } finally { root.recycle() }
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<DemoOperationNode> {
        val nodes = mutableListOf<DemoOperationNode>()
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (nodes.size >= 200 || depth > 30) return
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser) nodes += DemoOperationNode(path, node.viewIdResourceName?.take(160),
                node.className?.toString()?.take(120), node.text?.toString()?.take(120),
                node.contentDescription?.toString()?.take(120), listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                node.isClickable, node.isScrollable, node.isChecked, node.isCheckable)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, "$path.$i", depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, "0", 0)
        return nodes
    }

    private fun sensitive(node: AccessibilityNodeInfo, count: IntArray, depth: Int): Boolean {
        if (++count[0] > 500 || depth > 30 || node.isPassword || node.isEditable) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (sensitive(child, count, depth + 1)) return true } finally { child.recycle() }
        }
        return false
    }

    fun scroll(event: AccessibilityEvent): DemoOperationScrollObservation? {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return null
        return DemoOperationScrollObservation(
            if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaX else null,
            if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else null,
            event.scrollX, event.scrollY, event.fromIndex, event.toIndex)
    }

    fun event(event: AccessibilityEvent, id: Int, preFrameId: String?, scroll: DemoOperationScrollObservation? = DemoOperationCapture.scroll(event)): DemoOperationEvent {
        val node = event.source
        return try {
            val rect = Rect()
            node?.getBoundsInScreen(rect)
            DemoOperationEvent(id, System.currentTimeMillis(), event.eventType,
                event.packageName?.toString().orEmpty(), (node?.className ?: event.className)?.toString()?.take(160),
                node?.viewIdResourceName?.take(200),
                if (event.isPassword || node?.isEditable == true || node?.isPassword == true ||
                    node != null && node.packageName?.toString() != event.packageName?.toString()) null
                else (node?.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: event.text.take(4).joinToString(" ").takeIf { it.isNotBlank() })?.take(120),
                listOf(rect.left, rect.top, rect.right, rect.bottom), preFrameId,
                scrollDeltaX = scroll?.deltaX, scrollDeltaY = scroll?.deltaY,
                scrollX = scroll?.x, scrollY = scroll?.y, fromIndex = scroll?.fromIndex, toIndex = scroll?.toIndex)
        } finally { node?.recycle() }
    }

    fun screenshot(service: AccessibilityService, done: (Result<DemoOperationImage>) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(Result.failure(IllegalStateException("截图要求 Android 11+"))); return }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) = done(Result.failure(IllegalStateException("截图失败：$errorCode")))
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        imageWorker.execute {
                        val encoded = runCatching {
                            val bitmap = try {
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截图为空")
                                try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截图无法复制") }
                                finally { hardware.recycle() }
                            } finally { buffer.close() }
                            try {
                                val ratio = minOf(1.0, 960.0 / maxOf(bitmap.width, bitmap.height))
                                val scaled = Bitmap.createScaledBitmap(bitmap,
                                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                                    (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                                try {
                                    val bytes = ByteArrayOutputStream().use { out ->
                                        check(scaled.compress(Bitmap.CompressFormat.JPEG, 65, out))
                                        out.toByteArray()
                                    }
                                    DemoOperationImage(bytes, scaled.width, scaled.height)
                                } finally { if (scaled !== bitmap) scaled.recycle() }
                            } finally { bitmap.recycle() }
                        }
                        main.post { done(encoded) }
                        }
                    }
                })
        } catch (error: Exception) { done(Result.failure(error)) }
    }
}
