package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRunStateTest {

    @Test
    fun failedResultKeepsFullMessageWhileDetailStaysCompact() {
        val message = "error-" + "x".repeat(10_000)

        val state = DemoRunState.initial().reduce(AgentEvent.Failed(message))

        assertEquals(message, state.resultSummary)
        assertTrue(state.detailLabel.length <= DemoRunText.MAX_DETAIL_LENGTH)
        assertTrue(state.steps.single().resultSummary == message)
    }
}
