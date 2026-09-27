package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The outcome a delayed task round reports to its controller.
 *
 * [DemoDelayedTaskController.complete] restarts a repeating task unless the round
 * is reported as failed, so this mapping is what keeps a broken provider, an
 * exhausted quota or a dead network from restarting the loop forever. Before it
 * existed, the dispatcher reported every terminal event through the success path.
 */
class DemoDelayedTaskRoundTest {

    @Test
    fun `a failed turn is a failed round`() {
        assertEquals(
            DemoDelayedTaskRound.FAILED,
            demoDelayedTaskRound(AgentEvent.Failed("网络不可用"))
        )
    }

    @Test
    fun `a completed turn with an answer is a completed round`() {
        assertEquals(
            DemoDelayedTaskRound.COMPLETED,
            demoDelayedTaskRound(AgentEvent.Completed("检查完成，进度 42%"))
        )
    }

    @Test
    fun `a completed turn without any answer is a failed round`() {
        // An empty answer did nothing the instruction asked for. Counting it as a
        // success would reset the failure budget and restart the loop forever.
        assertEquals(
            DemoDelayedTaskRound.FAILED,
            demoDelayedTaskRound(AgentEvent.Completed(""))
        )
        assertEquals(
            DemoDelayedTaskRound.FAILED,
            demoDelayedTaskRound(AgentEvent.Completed("   "))
        )
    }
}
