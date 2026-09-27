package com.ugk.pi.android.testapp

import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DemoDelayedTaskDialogInstrumentedTest {

    @Test
    fun buttonActionsKeepTheirSheetsVisibleUntilExitFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DemoDialogTestHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as DemoDialogTestHostActivity
        instrumentation.waitForIdleSync()

        val task = DemoDelayedTask("dialog-motion-test", "test-conversation", "test-session", "测试倒计时弹窗", 60)
        val waiting = DemoDelayedTaskState.Waiting(
            task,
            SystemClock.elapsedRealtime() + 60_000,
            System.currentTimeMillis() + 60_000
        )
        var state: DemoDelayedTaskState = DemoDelayedTaskState.Proposed(task)
        val sheet = DemoDelayedTaskDialog(activity) { state }
        try {
            instrumentation.runOnMainSync {
                sheet.showProposal(task, 0,
                    onConfirm = {
                        state = waiting
                        sheet.showWaiting(waiting) {
                            state = DemoDelayedTaskState.Idle
                            sheet.dismiss()
                        }
                    },
                    onReject = {
                        state = DemoDelayedTaskState.Idle
                        sheet.dismiss()
                    },
                    onBackgroundSettings = {}
                )
            }
            SystemClock.sleep(450)

            val proposal = requireNotNull(currentDialog(sheet))
            instrumentation.runOnMainSync {
                requireNotNull(findButton(proposal.window!!.decorView, "确认并开始")).performClick()
            }
            assertTrue("Proposal window closed before its exit animation", proposal.isShowing)
            SystemClock.sleep(100)
            assertTrue("Proposal window vanished during its exit animation", proposal.isShowing)
            assertTrue("Proposal did not move toward the bottom", requireNotNull(closingCard(sheet)).translationY > 0f)
            assertTrue("Proposal faded before sliding away", requireNotNull(closingCard(sheet)).alpha == 1f)
            SystemClock.sleep(400)
            assertFalse(proposal.isShowing)

            val countdown = requireNotNull(currentDialog(sheet))
            assertTrue(countdown.isShowing)
            SystemClock.sleep(450)
            instrumentation.runOnMainSync {
                requireNotNull(findButton(countdown.window!!.decorView, "停止任务")).performClick()
            }
            assertTrue("Countdown window closed before its exit animation", countdown.isShowing)
            SystemClock.sleep(100)
            assertTrue("Countdown window vanished during its exit animation", countdown.isShowing)
            assertTrue("Countdown did not move toward the top", requireNotNull(closingCard(sheet)).translationY < 0f)
            assertTrue("Countdown faded before sliding away", requireNotNull(closingCard(sheet)).alpha == 1f)
            SystemClock.sleep(400)
            assertFalse(countdown.isShowing)
            assertTrue(state is DemoDelayedTaskState.Idle)

            state = DemoDelayedTaskState.Proposed(task)
            instrumentation.runOnMainSync {
                sheet.showProposal(task, 0,
                    onConfirm = {},
                    onReject = {
                        state = DemoDelayedTaskState.Idle
                        sheet.dismiss()
                    },
                    onBackgroundSettings = {}
                )
            }
            SystemClock.sleep(450)
            val canceledProposal = requireNotNull(currentDialog(sheet))
            instrumentation.runOnMainSync {
                requireNotNull(findButton(canceledProposal.window!!.decorView, "取消")).performClick()
            }
            SystemClock.sleep(100)
            assertTrue(canceledProposal.isShowing)
            assertTrue(requireNotNull(closingCard(sheet)).translationY > 0f)
            SystemClock.sleep(400)
            assertFalse(canceledProposal.isShowing)

            val settingsOpened = AtomicBoolean(false)
            state = DemoDelayedTaskState.Proposed(task)
            instrumentation.runOnMainSync {
                sheet.showProposal(task, 0,
                    onConfirm = {},
                    onReject = {},
                    onBackgroundSettings = { settingsOpened.set(true) }
                )
            }
            SystemClock.sleep(450)
            val settingsProposal = requireNotNull(currentDialog(sheet))
            instrumentation.runOnMainSync {
                requireNotNull(findText(settingsProposal.window!!.decorView, "后台设置")).performClick()
            }
            assertFalse("Settings opened before the sheet exited", settingsOpened.get())
            SystemClock.sleep(100)
            assertTrue(settingsProposal.isShowing)
            assertTrue(requireNotNull(closingCard(sheet)).translationY > 0f)
            SystemClock.sleep(400)
            assertFalse(settingsProposal.isShowing)
            assertTrue("Settings callback was lost after the exit", settingsOpened.get())
        } finally {
            instrumentation.runOnMainSync {
                sheet.dismiss(animate = false)
                activity.finish()
            }
        }
    }

    private fun currentDialog(sheet: DemoDelayedTaskDialog): Dialog? =
        DemoDelayedTaskDialog::class.java.getDeclaredField("dialog").let { field ->
            field.isAccessible = true
            field.get(sheet) as? Dialog
        }

    private fun closingCard(sheet: DemoDelayedTaskDialog): View? =
        DemoDelayedTaskDialog::class.java.getDeclaredField("closingCard").let { field ->
            field.isAccessible = true
            field.get(sheet) as? View
        }

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text.toString() == label) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findButton(root.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private fun findText(root: View, label: String): TextView? {
        if (root is TextView && root.text.toString() == label) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findText(root.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }
}
