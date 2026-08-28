package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import com.ugk.pi.android.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoConversationRuntimeTest {

    @Test
    fun independentRuntimeInstancesDoNotShareConversationState() {
        val first = DemoConversationRuntime()
        val second = DemoConversationRuntime()

        first.activeConversationId = "conversation-1"
        first.session = AgentSession("session-1")
        first.draft = "draft from first"
        first.transcript += DemoTranscriptEntry("user", "hello")
        first.accessibilityPromptShown = true
        first.overlayPromptShown = true
        first.activeContextWindow = "2M"
        first.activeAutoCompaction = false
        first.activeCompactionThreshold = 0.80
        first.rememberSession("conversation-1", first.session!!)

        assertNull(second.activeConversationId)
        assertNull(second.session)
        assertEquals("", second.draft)
        assertTrue(second.transcript.isEmpty())
        assertFalse(second.accessibilityPromptShown)
        assertFalse(second.overlayPromptShown)
        assertNull(second.activeContextWindow)
        assertTrue(second.activeAutoCompaction)
        assertEquals(ContextCompactor.DEFAULT_THRESHOLD, second.activeCompactionThreshold, 0.0)
        assertNull(second.sessionFor("conversation-1"))
        assertNotSame(first.runCoordinator, second.runCoordinator)
    }

    @Test
    fun sameProcessScopeReturnsTheSameConversationRuntime() {
        val firstScope = DemoProcessScope.get(TestApplicationContext())
        val secondScope = DemoProcessScope.get(TestApplicationContext())

        assertSame(firstScope.conversationRuntime, secondScope.conversationRuntime)
    }

    @Test
    fun sessionCachePreservesActiveSessionAndEvictsOnlyOlderEntries() {
        val runtime = DemoConversationRuntime()
        val sessions = (0..30).map { index ->
            AgentSession("session-$index").also {
                runtime.rememberSession("conversation-$index", it)
            }
        }

        assertEquals("conversation-30", runtime.activeConversationId)
        assertSame(sessions.last(), runtime.session)
        assertSame(sessions.last(), runtime.sessionFor("conversation-30"))
        assertNull(runtime.sessionFor("conversation-0"))
        assertEquals(30, runtime.sessions.size)
    }

    @Test
    fun contextPolicyStateDrivesDefaultBudgetAndCanBeUpdatedPerRuntime() {
        val runtime = DemoConversationRuntime()

        runtime.activeContextWindow = "2M"
        runtime.activeAutoCompaction = false
        runtime.activeCompactionThreshold = 0.80

        assertEquals(800 to 80_000, runtime.budgetForContextWindow())
        assertEquals("2M", runtime.activeContextWindow)
        assertFalse(runtime.activeAutoCompaction)
        assertEquals(0.80, runtime.activeCompactionThreshold, 0.0)
    }

    private class TestApplicationContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
