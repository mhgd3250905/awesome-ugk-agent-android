package com.ugk.pi.android.testapp

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AgentSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A repeating in-conversation task whose rounds keep failing must not re-arm
 * forever.
 *
 * When an Agent turn ends with [com.ugk.pi.android.AgentEvent.Failed] the
 * dispatcher reports it through the same round-completion path as a success, so
 * every failed round installs the next timer: the loop keeps spending API
 * quota, keeps appending "任务未完成" turns to the conversation, and keeps
 * evicting the reader's own history while the failure lasts.
 */
@RunWith(AndroidJUnit4::class)
class DemoDelayedTaskFailureLoopInstrumentedTest {

    @Test
    fun repeatingTaskStopsAfterConsecutiveFailedRounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rounds = runRepeatingTask { task, controller ->
            controller.complete(task.id, null, DemoDelayedTaskRound.FAILED)
            Unit
        }
        try {
            assertTrue(rounds.awaitTermination(TIMEOUT_MILLIS))

            assertNotNull(
                "a repeating task must stop after consecutive failed rounds " +
                    "(observed ${rounds.count.get()} rounds without terminating)",
                rounds.terminated
            )
            assertEquals(
                "the loop must stop exactly on the failure budget",
                DemoDelayedTaskController.MAX_CONSECUTIVE_FAILED_ROUNDS,
                rounds.count.get()
            )
        } finally {
            rounds.controller.stop()
            rounds.cleanup()
        }
    }

    /** Control: a repeating task whose rounds complete must keep restarting. */
    @Test
    fun repeatingTaskKeepsRunningWhileRoundsComplete() {
        val rounds = runRepeatingTask { task, controller ->
            controller.complete(task.id, "本轮结果", DemoDelayedTaskRound.COMPLETED)
            Unit
        }
        try {
            assertFalse(
                "completed rounds must not consume the failure budget",
                rounds.awaitTermination(TIMEOUT_MILLIS)
            )
            assertTrue(
                "a healthy repeating task must run more than one round",
                rounds.count.get() >= 2
            )
            assertTrue(rounds.controller.snapshot() is DemoDelayedTaskState.Waiting)
        } finally {
            rounds.controller.stop()
            rounds.cleanup()
        }
    }

    private class RoundObserver(
        val controller: DemoDelayedTaskController,
        val count: AtomicInteger,
        val cleanup: () -> Unit
    ) {
        @Volatile
        var terminated: Boolean? = null

        /** True once the controller reached Idle (the loop stopped by itself). */
        fun awaitTermination(timeoutMillis: Long): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (SystemClock.elapsedRealtime() < deadline) {
                if (controller.snapshot() is DemoDelayedTaskState.Idle && count.get() > 0) {
                    terminated = true
                    return true
                }
                Thread.sleep(100L)
            }
            return false
        }
    }

    /**
     * Starts a repeating one-second task and invokes [onRound] the way
     * [DemoDelayedMessageDispatcher] does when a round ends, from the timer
     * callback the controller itself publishes as Executing.
     */
    private fun runRepeatingTask(onRound: (DemoDelayedTask, DemoDelayedTaskController) -> Unit): RoundObserver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = DemoConversationRuntime(context)
        val conversation = runtime.conversationStore.create("周期任务失败治理测试")
        runtime.activeConversationId = conversation.id
        runtime.session = AgentSession(conversation.id)
        val count = AtomicInteger(0)
        var controllerRef: DemoDelayedTaskController? = null
        val controller = DemoDelayedTaskController(context, runtime) { task ->
            count.incrementAndGet()
            onRound(task, controllerRef ?: return@DemoDelayedTaskController)
        }
        controllerRef = controller
        val proposed = runBlocking {
            controller.propose(conversation.id, "每秒检查一次进度", 1L, repeating = true).getOrThrow()
        }
        assertTrue(controller.confirm(proposed.id))
        return RoundObserver(controller, count) { runtime.conversationStore.delete(conversation.id) }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 12_000L
    }
}
