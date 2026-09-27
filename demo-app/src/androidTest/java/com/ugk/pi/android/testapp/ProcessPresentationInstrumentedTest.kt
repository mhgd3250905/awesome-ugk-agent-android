package com.ugk.pi.android.testapp

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real presentation Views with synthetic messages; never constructs the conversation runtime. */
@RunWith(AndroidJUnit4::class)
class ProcessPresentationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun collapsedSummaryUsesPublicPhaseAndKnownToolTitles() {
        val raw = "这段自由文本仅供详情展示，不应进入收起摘要。"
        val thinking = DemoRunState(
            status = DemoRunStatus.THINKING,
            detailLabel = raw,
            resultSummary = raw,
            steps = listOf(DemoRunStep("model", DemoRunStepKind.MODEL, "处理请求", DemoRunStatus.THINKING, raw, raw))
        )
        assertEquals("正在思考你的请求", thinking.compactProcessSummary())
        val unknownTool = thinking.copy(
            status = DemoRunStatus.TOOL_RUNNING,
            steps = listOf(DemoRunStep("tool", DemoRunStepKind.TOOL, "custom_unknown_tool", DemoRunStatus.TOOL_RUNNING, raw, raw))
        )
        assertEquals("正在调用工具", unknownTool.compactProcessSummary())
        val knownTool = unknownTool.copy(steps = listOf(unknownTool.steps.single().copy(title = "[动作] 读取屏幕")))
        assertEquals("正在读取屏幕", knownTool.compactProcessSummary())
    }

    @Test
    fun compactAndExpandedProcessViewsCaptureInBothThemes() {
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).forEach { mode ->
            withHost(mode) { activity ->
                val fixture = showConversation(activity, thinkingState())
                assertCompact(fixture.card, "正在想下一步怎么做")
                capture("collapsed-thinking-${mode.key}")

                instrumentation.runOnMainSync { fixture.card.bind(toolState()) }
                settle()
                assertCompact(fixture.card, "正在读取屏幕")
                if (mode == AppThemeMode.LIGHT) capture("collapsed-tool-light")

                instrumentation.runOnMainSync { fixture.card.performClick() }
                settle()
                assertTrue("The entire compact row must open its timeline", fixture.card.isExpanded())
                assertNotNull(stepRow(fixture.card, "read", "读取屏幕"))
                capture("expanded-${mode.key}")

                if (mode == AppThemeMode.LIGHT) {
                    val toolStep = requireNotNull(stepRow(fixture.card, "read", "读取屏幕"))
                    instrumentation.runOnMainSync { toolStep.performClick() }
                    settle()
                    val detail = requireDetail(fixture.card, "read")
                    assertFullDetail(detail, completeToolDetail())
                    assertFalse("Opening one item must not expand another item",
                        isExpanded(requireNotNull(stepRow(fixture.card, "plan", "理解你的请求"))))
                    reveal(detail)
                    capture("detail-light")

                    instrumentation.runOnMainSync {
                        fixture.card.bind(toolState().copy(
                            stage = DemoChatProcessStage.ERROR,
                            summary = "读取屏幕失败，点开查看",
                            isRunning = false,
                            expanded = true,
                            steps = listOf(
                                sampleSteps()[0],
                                sampleSteps()[1].copy(
                                    status = DemoChatProcessStepStatus.ERROR,
                                    detail = "无障碍服务暂时不可用，请在设置中重新启用后再试。",
                                    resultSummary = null
                                )
                            )
                        ))
                        fixture.conversationScroll.scrollTo(0, 0)
                    }
                    settle()
                    assertFalse(indicator(fixture.card).isAnimating())
                    assertNotNull(findText(fixture.card, "读取屏幕失败，点开查看"))
                    capture("error-light")
                }
            }
        }
    }

    @Test
    fun longDetailsStayInsideTheirOwnScrollAtLargeFont() {
        withHost(AppThemeMode.LIGHT, fontScale = 1.5f) { activity ->
            val fixture = showConversation(activity, toolState())
            val summary = requireNotNull(findText(fixture.card, "正在读取屏幕"))
            assertReadable(summary)
            assertEquals(1.5f, activity.resources.configuration.fontScale, 0.01f)
            instrumentation.runOnMainSync { fixture.card.performClick() }
            settle()
            val toolStep = requireNotNull(stepRow(fixture.card, "read", "读取屏幕"))
            instrumentation.runOnMainSync { toolStep.performClick() }
            settle()
            val detail = requireDetail(fixture.card, "read")
            assertReadable(requireNotNull(findText(fixture.card, "正在读取屏幕")))
            reveal(detail)
            assertFullDetail(detail, completeToolDetail())
            val expandedHeight = fixture.card.height
            instrumentation.runOnMainSync { detail.scrollTo(0, 0) }
            instrumentation.waitForIdleSync()
            assertEquals(0, detail.scrollY)
            assertTrue("A long result must remain scrollable", detail.canScrollVertically(1))
            instrumentation.runOnMainSync { detail.scrollTo(0, detail.getChildAt(0).height) }
            instrumentation.waitForIdleSync()
            assertTrue(detail.scrollY > 0)
            assertFalse("The end of the tool result is unreachable", detail.canScrollVertically(1))
            assertEquals("Scrolling a detail must not resize the process timeline", expandedHeight, fixture.card.height)
            capture("large-font")
        }
    }

    @Test
    fun newStepsPreserveReadersPositionAndStoppedStepsStayNeutral() {
        withHost(AppThemeMode.LIGHT) { activity ->
            val initial = toolState().copy(expanded = true)
            val fixture = showConversation(activity, initial)
            instrumentation.runOnMainSync {
                requireNotNull(stepRow(fixture.card, "read", "读取屏幕")).performClick()
            }
            settle()
            val scroll = requireDetail(fixture.card, "read")
            instrumentation.runOnMainSync { scroll.scrollTo(0, activity.dp(90)) }
            instrumentation.waitForIdleSync()
            val readingPosition = scroll.scrollY
            assertTrue(readingPosition > 0 && scroll.canScrollVertically(1))
            instrumentation.runOnMainSync {
                fixture.card.bind(initial.copy(steps = initial.steps + DemoChatProcessStep(
                    id = "next", title = "下一步", status = DemoChatProcessStepStatus.ACTIVE
                )))
            }
            settle()
            assertTrue("Adding a step rebuilt the reader's detail", scroll === requireDetail(fixture.card, "read"))
            assertEquals("Adding a step moved the reader", readingPosition, scroll.scrollY)
            instrumentation.runOnMainSync {
                fixture.card.bind(initial.copy(
                    stage = DemoChatProcessStage.STOPPED, summary = "已停止 · 查看过程", isRunning = false,
                    steps = initial.steps.map { it.copy(status = DemoChatProcessStepStatus.STOPPED) }
                ))
            }
            settle()
            val stopped = requireNotNull(stepRow(fixture.card, "read", "读取屏幕"))
            assertTrue(stopped.contentDescription.toString().contains("已停止"))
            assertFalse(stopped.contentDescription.toString().contains("执行失败"))
            assertFalse(indicator(fixture.card).isAnimating())
        }
    }

    @Test
    fun activityIndicatorStopsForTerminalHiddenAndDetachedViews() {
        withHost(AppThemeMode.LIGHT) { activity ->
            val fixture = showConversation(activity, thinkingState())
            assertAnimationMatchesSystem(fixture.card)
            instrumentation.runOnMainSync {
                fixture.card.bind(thinkingState().copy(stage = DemoChatProcessStage.ERROR, isRunning = true))
            }
            settle()
            assertAnimationMatchesSystem(fixture.card)
            instrumentation.runOnMainSync {
                fixture.card.bind(thinkingState().copy(stage = DemoChatProcessStage.WAITING_CONFIRMATION))
            }
            settle()
            assertFalse("Waiting for the user should remain still", indicator(fixture.card).isAnimating())
            instrumentation.runOnMainSync { fixture.card.bind(thinkingState()) }
            settle()
            listOf(
                DemoChatProcessStage.COMPLETED,
                DemoChatProcessStage.ERROR,
                DemoChatProcessStage.STOPPED
            ).forEach { terminal ->
                instrumentation.runOnMainSync {
                    fixture.card.bind(thinkingState().copy(stage = terminal, isRunning = false))
                }
                settle()
                assertFalse("A $terminal indicator kept animating", indicator(fixture.card).isAnimating())
                instrumentation.runOnMainSync { fixture.card.bind(thinkingState()) }
                settle()
                assertAnimationMatchesSystem(fixture.card)
            }
            instrumentation.runOnMainSync { fixture.card.bind(thinkingState().copy(isRunning = false)) }
            settle()
            assertFalse("A paused activity indicator kept animating", indicator(fixture.card).isAnimating())
            instrumentation.runOnMainSync {
                fixture.card.bind(thinkingState())
                fixture.card.visibility = View.INVISIBLE
            }
            settle()
            assertFalse("An invisible process kept animating", indicator(fixture.card).isAnimating())
            instrumentation.runOnMainSync { fixture.card.visibility = View.VISIBLE }
            settle()
            assertAnimationMatchesSystem(fixture.card)
            val detachedIndicator = indicator(fixture.card)
            val originalParams = fixture.card.layoutParams
            val originalIndex = fixture.messages.indexOfChild(fixture.card)
            instrumentation.runOnMainSync { fixture.messages.removeView(fixture.card) }
            settle()
            assertFalse("A detached process kept animating", detachedIndicator.isAnimating())
            instrumentation.runOnMainSync {
                fixture.messages.addView(fixture.card, originalIndex, originalParams)
            }
            settle()
            assertAnimationMatchesSystem(fixture.card)
        }
    }

    private data class Fixture(
        val card: DemoChatProcessCardView,
        val conversationScroll: ScrollView,
        val messages: LinearLayout
    )

    private fun showConversation(activity: DemoDialogTestHostActivity, state: DemoChatProcessState): Fixture {
        lateinit var fixture: Fixture
        instrumentation.runOnMainSync {
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ui.ConversationCanvas)
            }
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            val toolbar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(16))
                setBackgroundColor(Ui.Background)
            }
            toolbar.addView(ImageView(activity).apply {
                setImageResource(R.drawable.brand_owl_avatar)
                contentDescription = "UGK 猫头鹰"
            }, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
            toolbar.addView(TextView(activity).apply {
                text = "今天的安排"
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Ui.TextPrimary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = activity.dp(12)
            })
            root.addView(toolbar, fullWidth())

            val messages = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(8), activity.dp(30), activity.dp(8), activity.dp(24))
            }
            messages.addView(DemoChatMessageView(activity).apply {
                bind(DemoChatMessageRole.USER, "帮我查看今天的日程，提醒我下一项安排。")
            }, fullWidth())
            val card = DemoChatProcessCardView(activity).apply {
                tag = "process-fixture-card"
                bind(state)
            }
            messages.addView(card, fullWidth().apply {
                marginStart = activity.dp(12)
                marginEnd = activity.dp(12)
                topMargin = activity.dp(16)
            })
            val conversationScroll = ScrollView(activity).apply {
                tag = "process-fixture-conversation"
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(messages, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            root.addView(conversationScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(TextView(activity).apply {
                text = "发消息"
                textSize = 16f
                setTextColor(Ui.TextMuted)
                background = Ui.rounded(activity, Ui.SurfaceElevated, 24)
                setPadding(activity.dp(18), activity.dp(17), activity.dp(18), activity.dp(17))
            }, fullWidth().apply {
                marginStart = activity.dp(18)
                marginEnd = activity.dp(18)
                bottomMargin = activity.dp(18)
            })
            activity.setContentView(root)
            fixture = Fixture(card, conversationScroll, messages)
        }
        settle()
        return fixture
    }

    private fun thinkingState() = DemoChatProcessState(
        stage = DemoChatProcessStage.THINKING,
        summary = "正在想下一步怎么做",
        isRunning = true,
        steps = sampleSteps().take(1)
    )

    private fun toolState() = DemoChatProcessState(
        stage = DemoChatProcessStage.TOOL_CALL,
        toolName = "screen_read_ui_tree",
        summary = "正在读取屏幕",
        isRunning = true,
        resultSummary = longResult(),
        steps = sampleSteps(),
        footerLeft = "3 个步骤",
        footerRight = "进行中"
    )

    private fun sampleSteps() = listOf(
        DemoChatProcessStep(
            id = "plan",
            title = "理解你的请求",
            status = DemoChatProcessStepStatus.COMPLETE,
            detail = "已确认需要查看日程并找到下一项安排。"
        ),
        DemoChatProcessStep(
            id = "read",
            title = "读取屏幕",
            status = DemoChatProcessStepStatus.ACTIVE,
            detail = "screen_read_ui_tree · 已连接当前屏幕",
            resultSummary = longResult()
        ),
        DemoChatProcessStep(
            id = "reply",
            title = "整理下一项安排",
            status = DemoChatProcessStepStatus.PENDING,
            detail = "等待读取结果后继续。"
        )
    )

    private fun longResult(): String = buildString {
        append("完整工具输出，仅在单项详情展开后展示。\n")
        repeat(90) { index -> append("第 ${index + 1} 项：这是合成日程数据，下午安排了阅读和散步。\n") }
        append("工具结果末尾：全部合成数据已读取。")
    }

    private fun completeToolDetail() = "screen_read_ui_tree · 已连接当前屏幕\n\n${longResult()}"

    private fun assertCompact(card: DemoChatProcessCardView, summary: String) {
        assertFalse(card.isExpanded())
        assertTrue("Compact process needs a 48 dp touch target", card.height >= card.context.dp(48))
        assertTrue("Default-font compact process is taller than 56 dp", card.height <= card.context.dp(56) + 1)
        val summaryView = requireNotNull(findText(card, summary))
        assertReadable(summaryView)
        assertEquals("Compact summary should fit one line", 1, summaryView.lineCount)
        assertEquals(15f, summaryView.textSize / summaryView.resources.displayMetrics.scaledDensity, 0.6f)
        assertFalse("Raw tool output leaked into the collapsed process", visibleText(card).contains("完整工具输出"))
        assertFalse("A generic card title consumed the compact row", visibleText(card).contains("Agent 过程"))
    }

    private fun assertFullDetail(scroll: StepDetailScrollView, expected: String) {
        assertEquals(View.VISIBLE, scroll.visibility)
        assertTrue("Step detail must keep its fixed 170 dp height",
            kotlin.math.abs(scroll.context.dp(170) - scroll.height) <= 1)
        val text = requireNotNull(findText(scroll, expected))
        val layout = requireNotNull(text.layout)
        assertEquals("The long tool output was truncated", expected.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
        assertTrue("The full result needs internal scrolling", scroll.canScrollVertically(-1) || scroll.canScrollVertically(1))
    }

    private fun assertReadable(view: TextView) {
        val bounds = Rect()
        assertTrue("Summary is not visible", view.getGlobalVisibleRect(bounds))
        assertTrue("Summary is vertically clipped", bounds.height() >= view.height - 1)
        val layout = requireNotNull(view.layout)
        assertTrue("Text is clipped by its own target", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) assertEquals("Readable fixture summary is ellipsized", 0, layout.getEllipsisCount(line))
    }

    private fun indicator(card: View): DemoProcessIndicatorView =
        requireNotNull(findView(card) { it.tag == "process-activity-indicator" }) as DemoProcessIndicatorView

    private fun assertAnimationMatchesSystem(card: DemoChatProcessCardView) {
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(instrumentation.targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        assertEquals("Indicator must follow the system animation setting", enabled, indicator(card).isAnimating())
    }

    private fun stepRow(card: View, id: String, title: String): View? =
        findView(card) { it.tag == "process-step:$id" }
            ?: findView(card) { it !== card && it.isClickable && it.contentDescription?.toString()?.startsWith("$title，") == true }

    private fun isExpanded(row: View): Boolean = row.contentDescription?.toString()?.contains("已展开") == true

    private fun requireDetail(card: View, id: String): StepDetailScrollView =
        (findView(card) { it.tag == "process-detail:$id" }
            ?: findView(card) { it is StepDetailScrollView }) as? StepDetailScrollView
            ?: error("Missing expanded detail for $id")

    private fun visibleText(root: View): String = buildString {
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is TextView) append(view.text).append('\n')
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    private fun findText(root: View, text: String): TextView? =
        findView(root) { it is TextView && it.text.toString() == text } as? TextView

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findView(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun reveal(view: View) {
        instrumentation.runOnMainSync { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        instrumentation.waitForIdleSync()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(220)
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "process-ui")
        assertTrue("Cannot create screenshot directory", directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(directory, "$name.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("ProcessPresentationTest", "Screenshot: ${file.absolutePath}")
        } finally {
            bitmap.recycle()
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
                Intent(context, DemoDialogTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
