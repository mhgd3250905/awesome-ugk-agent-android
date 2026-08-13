package com.ugk.pi.android.testapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoChatProcessCardInstrumentedTest {

    @Test
    fun outerAndStepExpansionStayIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var card: DemoChatProcessCardView? = null

        instrumentation.runOnMainSync {
            card = DemoChatProcessCardView(instrumentation.targetContext).apply {
                bind(
                    DemoChatProcessState(
                        stage = DemoChatProcessStage.COMPLETED,
                        resultSummary = "最终回答",
                        steps = listOf(
                            DemoChatProcessStep(
                                id = "analysis",
                                title = "已完成分析",
                                status = DemoChatProcessStepStatus.COMPLETE,
                                detail = "已规划 1 个工具步骤"
                            ),
                            DemoChatProcessStep(
                                id = "tool",
                                title = "screen_read_ui_tree",
                                status = DemoChatProcessStepStatus.COMPLETE,
                                detail = "工具已完成",
                                resultSummary = "完整工具结果，不应在步骤默认状态显示"
                            )
                        ),
                        expanded = true
                    )
                )
            }
        }

        val rendered = requireNotNull(card)
        assertTrue(rendered.isExpanded())
        assertFalse(findText(rendered, "完整工具结果，不应在步骤默认状态显示"))

        val toolRow = findView(rendered) { view ->
            view.contentDescription?.toString()?.contains("screen_read_ui_tree") == true
        }
        assertNotNull(toolRow)
        instrumentation.runOnMainSync { toolRow?.performClick() }
        assertTrue(findText(rendered, "完整工具结果，不应在步骤默认状态显示"))

        val collapseFooter = findView(rendered) { view ->
            view.contentDescription == "收起整个过程"
        }
        assertNotNull(collapseFooter)
        instrumentation.runOnMainSync { collapseFooter?.performClick() }
        assertFalse(rendered.isExpanded())

        instrumentation.runOnMainSync { rendered.setExpanded(true) }
        assertFalse(findText(rendered, "完整工具结果，不应在步骤默认状态显示"))
    }

    private fun findText(root: View, expected: String): Boolean =
        findView(root) { view -> view is TextView && view.text.toString().contains(expected) } != null

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
