package com.ugk.pi.android.testapp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.attention.UrgentAccent
import com.ugk.pi.attention.UrgentAction
import com.ugk.pi.attention.UrgentBlockType
import com.ugk.pi.attention.UrgentContentBlock
import com.ugk.pi.attention.UrgentForm
import com.ugk.pi.attention.UrgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** UI-only fixtures: no MainActivity, LLM, saved conversations, task controller, or SDK events. */
@RunWith(AndroidJUnit4::class)
class NoteStyleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nativeNotesCaptureInLightAndDarkThemes() {
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).forEach { mode ->
            withHost(mode) { activity ->
                val task = shortTask()
                var state: DemoDelayedTaskState = DemoDelayedTaskState.Proposed(task)
                val sheet = DemoDelayedTaskDialog(activity) { state }
                try {
                    instrumentation.runOnMainSync {
                        sheet.showProposal(task, 0, {}, {}, {})
                    }
                    settle()
                    val proposal = requireNotNull(currentDialog(sheet)).window!!.decorView
                    assertFullyVisible(requireText(proposal, "确认并开始"))
                    assertNotNull(findText(proposal, task.instruction))
                    capture("proposal-${mode.key}")

                    instrumentation.runOnMainSync {
                        sheet.dismiss(animate = false)
                        val waiting = waitingFor(task)
                        state = waiting
                        sheet.showWaiting(waiting) {}
                    }
                    settle()
                    // Keep the actual countdown renderer at ten seconds when the screenshot is taken.
                    instrumentation.runOnMainSync {
                        val waiting = waitingFor(task)
                        state = waiting
                        sheet.showWaiting(waiting) {}
                    }
                    instrumentation.waitForIdleSync()
                    val waiting = requireNotNull(currentDialog(sheet)).window!!.decorView
                    assertFullyVisible(requireText(waiting, "停止任务"))
                    assertNotNull(findText(waiting, "00:10"))
                    capture("waiting-${mode.key}")

                    instrumentation.runOnMainSync {
                        sheet.dismiss(animate = false)
                        activity.setContentView(UrgentTakeoverView.build(
                            activity,
                            shortReminder(),
                            onClose = {},
                            onOpenApp = {},
                            onAction = { false },
                            onFormSubmit = { _, _ -> false }
                        ))
                    }
                    settle()
                    val urgent = activity.window.decorView
                    assertFullyVisible(requireText(urgent, "打开对话"))
                    assertNotNull(findText(urgent, shortReminder().title))
                    assertNotNull(findText(urgent, shortReminder().body))
                    capture("urgent-${mode.key}")
                } finally {
                    instrumentation.runOnMainSync { sheet.dismiss(animate = false) }
                }
            }
        }
    }

    @Test
    fun largeFontKeepsLongProposalScrollableAndTimerControlsVisible() {
        withHost(AppThemeMode.LIGHT, fontScale = 1.5f) { activity ->
            assertEquals(1.5f, activity.resources.configuration.fontScale, 0.01f)
            val task = shortTask().copy(instruction = longInstruction(), repeating = true)
            var state: DemoDelayedTaskState = DemoDelayedTaskState.Proposed(task)
            val confirmations = AtomicInteger()
            val stops = AtomicInteger()
            val sheet = DemoDelayedTaskDialog(activity) { state }
            try {
                instrumentation.runOnMainSync {
                    sheet.showProposal(task, queuedMessages = 3,
                        onConfirm = {
                            confirmations.incrementAndGet()
                            val waiting = waitingFor(task).copy(completedRuns = 2)
                            state = waiting
                            sheet.showWaiting(waiting) {
                                stops.incrementAndGet()
                                state = DemoDelayedTaskState.Idle
                                sheet.dismiss()
                            }
                        },
                        onReject = {},
                        onBackgroundSettings = {}
                    )
                }
                settle()
                val proposal = requireNotNull(currentDialog(sheet)).window!!.decorView
                val confirm = requireText(proposal, "确认并开始")
                val cancel = requireText(proposal, "取消")
                assertFullyVisible(confirm)
                assertFullyVisible(cancel)
                val instruction = requireText(proposal, task.instruction)
                assertEntireTextLaidOut(instruction, task.instruction)
                assertNotNull("The queued-message consequence must remain in the proposal", findView(proposal) {
                    it is TextView && it.text.contains("3") && it.text.contains("清空")
                })
                val scroll = requireNotNull(findView(proposal) { it is ScrollView }) as ScrollView
                assertTrue("Long proposal must have a scrollable body", scroll.canScrollVertically(1))
                instrumentation.runOnMainSync { scroll.scrollTo(0, scroll.getChildAt(0).height) }
                instrumentation.waitForIdleSync()
                assertTrue(scroll.scrollY > 0)
                assertFalse("The proposal body cannot reach its end", scroll.canScrollVertically(1))
                assertFullyVisible(confirm)
                assertFullyVisible(cancel)
                assertFullyVisible(requireText(proposal, "后台设置"))
                capture("proposal-large-text-bottom")
                instrumentation.runOnMainSync { confirm.performClick() }
                settle()
                assertEquals(1, confirmations.get())

                val waiting = requireNotNull(currentDialog(sheet)).window!!.decorView
                val stop = requireText(waiting, "停止周期任务")
                assertFullyVisible(stop)
                val summary = requireText(waiting, task.instruction)
                assertEquals("The compact waiting preview must retain its full accessible text",
                    task.instruction, summary.contentDescription.toString())
                assertNotNull(findView(waiting) { it is TextView && it.text.contains("2") && it.text.contains("轮") })
                capture("waiting-large-text")
                instrumentation.runOnMainSync { stop.performClick() }
                settle()
                assertEquals(1, stops.get())
                assertTrue(state is DemoDelayedTaskState.Idle)
            } finally {
                instrumentation.runOnMainSync { sheet.dismiss(animate = false) }
            }
        }
    }

    @Test
    fun urgentControlsPreserveCallbacksBoundedInputAndStructuredContent() {
        withHost(AppThemeMode.DARK, fontScale = 1.5f) { activity ->
            val action = UrgentAction("drink", "请确认你已经起身活动并喝过水，然后告诉我是否需要继续提醒。".repeat(2).take(40))
            val form = UrgentForm("reply", "告诉我你的安排", "输入你的安排", "发送安排")
            val message = shortReminder().copy(
                body = "这段备用正文不应与结构化内容重复显示。",
                blocks = listOf(
                    UrgentContentBlock(UrgentBlockType.HEADING, "先照顾好自己"),
                    UrgentContentBlock(UrgentBlockType.PARAGRAPH, longInstruction()),
                    UrgentContentBlock(UrgentBlockType.CALLOUT, "慢慢喝，不着急"),
                    UrgentContentBlock(UrgentBlockType.BULLET, "站起来活动一下")
                ),
                actions = listOf(action),
                form = form
            )
            val closed = AtomicInteger()
            val opened = AtomicInteger()
            val actions = mutableListOf<UrgentAction>()
            val submissions = mutableListOf<Pair<UrgentForm, String>>()
            lateinit var root: View
            instrumentation.runOnMainSync {
                root = UrgentTakeoverView.build(activity, message,
                    onClose = { closed.incrementAndGet() },
                    onOpenApp = { opened.incrementAndGet() },
                    onAction = { actions.add(it); false },
                    onFormSubmit = { submittedForm, value ->
                        submissions.add(submittedForm to value)
                        true
                    }
                )
                activity.setContentView(root)
            }
            settle()
            assertNull("Structured blocks must replace the fallback body", findText(root, message.body))
            message.blocks.forEach { block -> assertNotNull(findText(root, block.text)) }
            assertEntireTextLaidOut(requireText(root, longInstruction()), longInstruction())
            val close = requireNotNull(findView(root) { it.contentDescription == "关闭全屏提醒" })
            val open = requireText(root, "打开对话")
            assertFullyVisible(close)
            assertFullyVisible(open)
            val scroll = requireNotNull(findView(root) { it is ScrollView }) as ScrollView
            assertTrue("Long urgent content must scroll independently of its fixed actions", scroll.canScrollVertically(1))

            val actionButton = requireText(root, action.label)
            reveal(actionButton)
            assertFullyVisible(actionButton)
            instrumentation.runOnMainSync { actionButton.performClick() }
            instrumentation.waitForIdleSync()
            assertEquals(listOf(action), actions)
            val error = requireText(root, "暂时无法提交，请稍后再试")
            assertEquals(View.VISIBLE, error.visibility)
            assertEquals(0, closed.get())
            assertEquals(0, opened.get())

            val field = requireNotNull(findView(root) { it is EditText }) as EditText
            val submit = requireText(root, form.submitLabel)
            reveal(submit)
            assertFullyVisible(submit)
            instrumentation.runOnMainSync {
                field.setText("   ")
                submit.performClick()
            }
            assertTrue("Blank input must not be sent to the Agent", submissions.isEmpty())
            assertEquals(View.VISIBLE, requireText(root, "请先输入内容").visibility)
            instrumentation.runOnMainSync {
                field.setText("  我会在休息时喝水  ")
                submit.performClick()
            }
            assertEquals(listOf(form to "我会在休息时喝水"), submissions)
            instrumentation.runOnMainSync {
                field.setText("水".repeat(510))
                submit.performClick()
            }
            assertEquals(500, field.text.length)
            assertEquals(form to "水".repeat(500), submissions.last())
            assertEquals(2, submissions.size)
            assertFullyVisible(open)
            capture("urgent-large-text-controls")
            instrumentation.runOnMainSync { close.performClick() }
            assertEquals(1, closed.get())
            assertEquals(0, opened.get())
            assertEquals(1, actions.size)
            assertEquals(2, submissions.size)
            instrumentation.runOnMainSync { open.performClick() }
            assertEquals(1, opened.get())
            assertEquals(1, closed.get())
            assertEquals(1, actions.size)
            assertEquals(2, submissions.size)
        }
    }

    private fun withHost(
        mode: AppThemeMode,
        fontScale: Float = 1f,
        block: (DemoDialogTestHostActivity) -> Unit
    ) {
        val context = instrumentation.targetContext
        val originalMode = ThemeStore(context).getThemeMode()
        val originalScale = DemoDialogTestHostActivity.fontScaleOverride
        var activity: DemoDialogTestHostActivity? = null
        try {
            instrumentation.runOnMainSync {
                ThemeManager.init(context)
                ThemeManager.setMode(context, mode)
                DemoDialogTestHostActivity.fontScaleOverride = fontScale
            }
            val launched = instrumentation.startActivitySync(
                Intent(context, DemoDialogTestHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as DemoDialogTestHostActivity
            activity = launched
            instrumentation.waitForIdleSync()
            block(launched)
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                DemoDialogTestHostActivity.fontScaleOverride = originalScale
                ThemeManager.setMode(context, originalMode)
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun shortTask() = DemoDelayedTask(
        "note-ui-synthetic", "note-ui-conversation", "note-ui-session", "提醒我去喝水", 10
    )

    private fun waitingFor(task: DemoDelayedTask) = DemoDelayedTaskState.Waiting(
        task,
        SystemClock.elapsedRealtime() + task.delaySeconds * 1_000L,
        System.currentTimeMillis() + task.delaySeconds * 1_000L
    )

    private fun shortReminder() = UrgentMessage(
        title = "喝水提醒",
        body = "10 秒到了，请起身去喝一杯水吧。",
        reason = "你设置的 10 秒喝水提醒已到",
        accent = UrgentAccent.BLUE
    )

    private fun longInstruction(): String {
        val end = "这是任务最后一行。"
        return "请提醒我起身活动并喝一杯温水。".repeat(200).take(2_000 - end.length) + end
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun reveal(view: View) {
        instrumentation.runOnMainSync {
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "note-ui")
        assertTrue("Cannot create screenshot directory", directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val destination = File(directory, "$name.png")
            destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("NoteStyleTest", "Screenshot: ${destination.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue("${view.contentDescription ?: (view as? TextView)?.text} is not visible", view.getGlobalVisibleRect(visible))
        assertTrue("Control is vertically clipped", visible.height() >= view.height - 1)
        assertTrue("Control is horizontally clipped", visible.width() >= view.width - 1)
        if (view is TextView) {
            val layout = requireNotNull(view.layout)
            assertTrue("Control label is taller than its touch target",
                layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals("Control label is ellipsized", 0, layout.getEllipsisCount(line))
            }
        }
    }

    private fun assertEntireTextLaidOut(view: TextView, expected: String) {
        val layout = requireNotNull(view.layout)
        assertEquals(expected, view.text.toString())
        assertTrue(layout.lineCount > 1)
        assertEquals("The end of the long text was not laid out", expected.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertEquals("Long content was ellipsized", 0, layout.getEllipsisCount(line))
        }
    }

    private fun currentDialog(sheet: DemoDelayedTaskDialog): Dialog? =
        DemoDelayedTaskDialog::class.java.getDeclaredField("dialog").let { field ->
            field.isAccessible = true
            field.get(sheet) as? Dialog
        }

    private fun requireText(root: View, label: String): TextView =
        requireNotNull(findText(root, label)) { "Missing text: $label" }

    private fun findText(root: View, label: String): TextView? =
        findView(root) { it is TextView && it.text.toString() == label } as? TextView

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findView(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }
}
