package com.ugk.pi.android.testapp

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.provider.Settings
import android.os.SystemClock
import android.view.InputDevice
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses actual WindowManager overlays; grant SYSTEM_ALERT_WINDOW and use a docked keyboard. */
@RunWith(AndroidJUnit4::class)
class FloatingConversationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realKeyboardAvoidanceKeepsComposerVisibleAndRestoresWindow() = withOverlay { activity, overlay ->
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        var originalY = 0
        var originalHeight = 0
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            main {
                val params = field(overlay, "expandedParams") as WindowManager.LayoutParams
                originalY = params.y
                originalHeight = params.height
                input(overlay).setText("真实键盘测试草稿")
                input(overlay).requestFocus()
            }
            awaitCondition("Overlay input never gained window focus") {
                var focused = false
                main { focused = input(overlay).hasWindowFocus() }
                focused
            }
            main {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input(overlay), InputMethodManager.SHOW_IMPLICIT)
            }
            awaitCondition("Real IME window was not shown") { keyboardBounds() != null }
            var imeGeometry = ""
            try { awaitCondition("Overlay did not avoid the real keyboard") {
                val keyboard = keyboardBounds()
                var fits = false
                main {
                    val params = field(overlay, "expandedParams") as WindowManager.LayoutParams
                    val location = IntArray(2)
                    input(overlay).getLocationOnScreen(location)
                    val rootLocation = IntArray(2)
                    root(overlay).getLocationOnScreen(rootLocation)
                    imeGeometry = "keyboard=$keyboard, rootY=${rootLocation[1]}, rootHeight=${root(overlay).height}, " +
                        "inputBottom=${location[1] + input(overlay).height}, y=${params.y}, height=${params.height}, " +
                        "originalY=$originalY, originalHeight=$originalHeight, preImeY=${field(overlay, "preImeY")}, " +
                        "insets=${root(overlay).rootWindowInsets}"
                    fits = keyboard != null && location[1] + input(overlay).height <= keyboard.top &&
                        (params.y != originalY || params.height != originalHeight)
                }
                fits
            } } catch (failure: AssertionError) {
                screenshot(activity, "keyboard-failure")
                throw AssertionError("${failure.message}: $imeGeometry", failure)
            }
            screenshot(activity, "keyboard")
            main {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input(overlay).windowToken, 0)
            }
            awaitCondition("IME dismissal did not restore overlay position and height") {
                var restored = false
                main {
                    val params = field(overlay, "expandedParams") as WindowManager.LayoutParams
                    restored = params.y == originalY && params.height == originalHeight
                }
                keyboardBounds() == null && restored
            }
            main { assertEquals("真实键盘测试草稿", input(overlay).text.toString()) }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    @Test
    fun injectedHeaderDragMovesWindowAndButtonSwipeDoesNotClick() = withOverlay { _, overlay ->
        var oldY = 0
        var opened = 0
        var hidden = 0
        main {
            oldY = (field(overlay, "expandedParams") as WindowManager.LayoutParams).y
            overlay.onOpenApp = { opened++ }
            overlay.onHide = { hidden++ }
        }
        swipe(overlay, "overlay-header-drag", 0f, -70f)
        main {
            assertTrue("Header drag did not move the actual window",
                (field(overlay, "expandedParams") as WindowManager.LayoutParams).y < oldY)
        }
        for (tag in listOf("overlay-open-app", "overlay-hide", "overlay-collapse")) {
            swipe(overlay, tag, 0f, 60f)
            main {
                assertTrue("Swiping $tag dismissed/collapsed the overlay", root(overlay).isAttachedToWindow)
                assertEquals(0, opened)
                assertEquals(0, hidden)
            }
        }
    }

    @Test
    fun imageThumbnailOpensAndClosesAnApplicationOverlayPreview() = withOverlay { activity, overlay ->
        val image = File.createTempFile("overlay-test-image-", ".png", activity.cacheDir)
        try {
            activity.resources.openRawResource(R.drawable.brand_owl_avatar).use { source ->
                image.outputStream().use { source.copyTo(it) }
            }
            main {
                overlay.bindSnapshot(snapshot().copy(messages = listOf(
                    AgentOverlayMessage("image", "user", "合成图片", listOf(image.absolutePath))
                ), process = null))
                val thumbnail = descendants(root(overlay)).first {
                    it.contentDescription == "已发送图片，点击全屏预览"
                }
                thumbnail.performClick()
                val dialog = field(overlay, "imagePreview") as Dialog
                assertTrue(dialog.isShowing)
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    requireNotNull(dialog.window).attributes.type)
                val imageView = descendants(requireNotNull(dialog.window).decorView)
                    .first { it is android.widget.ImageView && it.isClickable }
                imageView.performClick()
                assertFalse(dialog.isShowing)
                assertTrue(root(overlay).isAttachedToWindow)
            }
        } finally { image.delete() }
    }

    @Test
    fun streamingAnswerKeepsItsViewAndDoesNotDuplicateTheStoredReply() = withOverlay { activity, overlay ->
        val busy = snapshot().copy(
            statusLabel = "思考中", isBusy = true,
            messages = listOf(AgentOverlayMessage("user", "user", "请整理这份资料")),
            process = DemoChatProcessState(DemoChatProcessStage.THINKING, summary = "正在思考你的请求")
        )
        main {
            overlay.bindSnapshot(busy)
            overlay.onSendMessage = { true }
            input(overlay).setText("再列出重点")
        }
        screenshot(activity, "busy", overlay)
        main {
            overlay.setAssistantPreview("正在整理")
            val preview = tagged(overlay, "overlay-assistant-preview")
            overlay.setAssistantPreview("正在整理资料")
            assertSame(preview, tagged(overlay, "overlay-assistant-preview"))
            val stored = busy.copy(messages = busy.messages +
                AgentOverlayMessage("assistant", "assistant", "正在整理资料"))
            overlay.bindSnapshot(stored)
            assertNull(preview.parent)
            assertNotNull(tagged(overlay, "overlay-message:assistant"))
            overlay.bindSnapshot(stored.copy(isBusy = false,
                process = DemoChatProcessState(DemoChatProcessStage.COMPLETED)))
            assertNull(preview.parent)
        }
    }

    @Test
    fun busySendRetainsRejectedDraftAndClearsOnlyAcceptedDraft() = withOverlay { _, overlay ->
        val sent = mutableListOf<String>()
        val drafts = mutableListOf<String>()
        var accepted = false
        var stopped = false
        main {
            overlay.bindSnapshot(snapshot().copy(isBusy = true, queuedMessages = 2))
            overlay.onSendMessage = { sent += it; accepted }
            overlay.onDraftChanged = { drafts += it }
            overlay.onStopAgent = { stopped = true }
            overlay.addLog("旧记录：之前的任务成功")
            input(overlay).setText("下一条消息")
            tagged(overlay, "overlay-send").performClick()
            assertEquals("下一条消息", input(overlay).text.toString())
            assertEquals("消息未发送，请稍后重试", (tagged(overlay, "overlay-queue") as TextView).text.toString())
            accepted = true
            tagged(overlay, "overlay-send").performClick()
            assertEquals("", input(overlay).text.toString())
            assertEquals(listOf("下一条消息", "下一条消息"), sent)
            assertTrue(drafts.contains("下一条消息"))
            assertTrue(tagged(overlay, "overlay-queue").isShown)
            tagged(overlay, "overlay-stop").performClick()
            assertTrue(stopped)
            input(overlay).setText("收起后保留")
            tagged(overlay, "overlay-collapse").performClick()
            overlay.showExpanded()
            assertEquals("收起后保留", input(overlay).text.toString())
        }
    }

    @Test
    fun processAndStepExpansionSurviveSnapshotStatusLogAndDraftUpdates() = withOverlay { activity, overlay ->
        val shortSnapshot = snapshot().copy(messages = listOf(
            AgentOverlayMessage("user", "user", "请整理这份资料"),
            AgentOverlayMessage("assistant", "assistant", "资料已经整理完成。")
        ))
        main {
            overlay.bindSnapshot(shortSnapshot)
            assertFalse(process(overlay).isExpanded())
        }
        screenshot(activity, "collapsed-process", overlay)
        main { tagged(overlay, "process-header").performClick() }
        screenshot(activity, "expanded-process", overlay)
        main {
            tagged(overlay, "process-step:tool").performClick()
            assertEquals(View.VISIBLE, tagged(overlay, "process-detail:tool").visibility)
        }
        screenshot(activity, "detail", overlay)
        main {
            overlay.bindSnapshot(shortSnapshot.copy(statusLabel = "刷新状态"))
            overlay.setStatus("继续处理")
            overlay.addLog("合成活动记录")
            overlay.setAssistantPreview("正在整理结果")
            input(overlay).setText("保持展开")
            assertTrue(process(overlay).isExpanded())
            assertEquals(View.VISIBLE, tagged(overlay, "process-detail:tool").visibility)
            val all = descendants(root(overlay)).toList()
            assertTrue(all.indexOf(tagged(overlay, "overlay-message:user")) < all.indexOf(process(overlay)))
            assertTrue(all.indexOf(process(overlay)) < all.indexOf(tagged(overlay, "overlay-message:assistant")))
            assertTrue(tagged(overlay, "overlay-message:assistant") is DemoChatMessageView)
        }
    }

    @Test
    fun activityLogStartsCollapsedAndCanBeOpened() = withOverlay { _, overlay ->
        main {
            overlay.bindSnapshot(snapshot())
            overlay.addLog("合成记录：工具完成")
            assertFalse(tagged(overlay, "overlay-activity-details").isShown)
            tagged(overlay, "overlay-activity-toggle").performClick()
            assertTrue(tagged(overlay, "overlay-activity-details").isShown)
        }
    }

    @Test
    fun narrowLargeFontOverlayKeepsTitleAndComposerVisibleInBothThemes() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            withOverlay(mode, 1.5f) { activity, overlay ->
                main {
                    overlay.bindSnapshot(snapshot().copy(title = "这是一个很长很长的对话标题用于验证标题只占一行"))
                    val params = field(overlay, "expandedParams") as WindowManager.LayoutParams
                    assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
                    params.width = (280 * activity.resources.displayMetrics.density).toInt()
                    (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(root(overlay), params)
                }
                instrumentation.waitForIdleSync()
                main {
                    assertEquals(1, (tagged(overlay, "overlay-title") as TextView).maxLines)
                    for (tag in listOf("overlay-input", "overlay-send", "overlay-open-app", "overlay-hide")) {
                        val view = tagged(overlay, tag)
                        val visible = Rect()
                        assertTrue("$tag is clipped or hidden", view.getGlobalVisibleRect(visible))
                        assertTrue("$tag has no usable bounds", visible.width() > 0 && visible.height() > 0)
                    }
                }
                screenshot(activity, mode.key)
            }
        }
    }

    private fun snapshot() = AgentOverlaySnapshot(
        title = "合成对话", statusLabel = "完成", conversationId = "overlay-test", runId = "run-1",
        messages = listOf(
            AgentOverlayMessage("user", "user", "请整理这份资料"),
            AgentOverlayMessage("assistant", "assistant", "## 结果\n\n**重点**与[链接](https://example.com)\n\n- 第一项\n- 第二项\n\n```kotlin\nval result = 1\n```\n\n| 项目 | 状态 |\n| --- | --- |\n| 测试 | 完成 |")
        ),
        process = DemoChatProcessState(
            stage = DemoChatProcessStage.COMPLETED,
            steps = listOf(DemoChatProcessStep("tool", "读取资料", DemoChatProcessStepStatus.COMPLETE,
                detail = "读取合成测试资料", resultSummary = "完整工具结果"))
        )
    )

    private fun withOverlay(
        mode: AppThemeMode = AppThemeMode.LIGHT,
        fontScale: Float = 1f,
        block: (DemoDialogTestHostActivity, AgentFloatingWindow) -> Unit
    ) {
        val context = instrumentation.targetContext
        assertTrue("Grant overlay permission before this suite", Settings.canDrawOverlays(context))
        val previousMode = ThemeStore(context).getThemeMode()
        val previousScale = DemoDialogTestHostActivity.fontScaleOverride
        var activity: DemoDialogTestHostActivity? = null
        var overlay: AgentFloatingWindow? = null
        try {
            main { ThemeManager.setMode(context, mode) }
            DemoDialogTestHostActivity.fontScaleOverride = fontScale
            val host = instrumentation.startActivitySync(Intent(context, DemoDialogTestHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DemoDialogTestHostActivity
            activity = host
            main { overlay = AgentFloatingWindow(host).also { it.showExpanded() } }
            instrumentation.waitForIdleSync()
            val window = requireNotNull(overlay)
            main { assertTrue(root(window).isAttachedToWindow) }
            block(host, window)
        } finally {
            main {
                overlay?.let { window ->
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow((field(window, "expandedView") as? View)?.windowToken, 0)
                    window.hide()
                }
                activity?.finish()
                ThemeManager.setMode(context, previousMode)
                DemoDialogTestHostActivity.fontScaleOverride = previousScale
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun screenshot(context: Context, name: String, overlay: AgentFloatingWindow? = null) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        main {
            overlay?.let { (field(it, "scrollView") as android.widget.ScrollView).scrollTo(0, 0) }
        }
        val frames = CountDownLatch(1)
        main {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        assertTrue("Screenshot frames did not settle", frames.await(2, TimeUnit.SECONDS))
        SystemClock.sleep(150)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "overlay-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun keyboardBounds(): Rect? = instrumentation.uiAutomation.windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun swipe(overlay: AgentFloatingWindow, tag: String, dx: Float, dy: Float) {
        var x = 0f
        var y = 0f
        main {
            val target = tagged(overlay, tag)
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            x = location[0] + target.width / 2f
            y = location[1] + target.height / 2f
        }
        val down = SystemClock.uptimeMillis()
        for (step in 0..6) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 6 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x + dx * step / 6, y + dy * step / 6, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (step < 6) SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }
    private fun field(overlay: AgentFloatingWindow, name: String): Any? =
        AgentFloatingWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(overlay)
    private fun root(overlay: AgentFloatingWindow) = requireNotNull(field(overlay, "expandedView") as? View)
    private fun tagged(overlay: AgentFloatingWindow, tag: String): View =
        requireNotNull(descendants(root(overlay)).firstOrNull { it.tag == tag }) { "Missing view: $tag" }
    private fun input(overlay: AgentFloatingWindow) = tagged(overlay, "overlay-input") as EditText
    private fun process(overlay: AgentFloatingWindow) = tagged(overlay, "overlay-process") as DemoChatProcessCardView
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
