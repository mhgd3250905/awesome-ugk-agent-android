package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
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

    /**
     * Activity-recreation contract: the AgentRuntime and its applied config
     * live on this process-level instance, so a recreated Activity observes
     * them intact — an in-flight run is neither closed nor re-created. With
     * the previous Activity-owned fields the recreation read null and killed
     * the running Agent while clearing the overlay message queue.
     */
    @Test
    fun activityRecreationKeepsProcessOwnedAgentRuntimeAndAppliedConfig() {
        val processRuntime = DemoConversationRuntime()
        val firstAgentRuntime = newAgentRuntime()
        val firstConfig = demoRuntimeConfig()

        // First Activity instance installs the runtime on process-owned state.
        processRuntime.agentRuntime = firstAgentRuntime
        processRuntime.appliedRuntimeConfig = firstConfig

        // Simulated recreation: the second Activity instance owns no runtime
        // fields and only reads the same process-level state.
        assertSame(firstAgentRuntime, processRuntime.agentRuntime)
        assertEquals(firstConfig, processRuntime.appliedRuntimeConfig)

        // A later replacement (finishing teardown or a real provider change)
        // is written through the same single owner; state never diverges.
        val secondConfig = firstConfig.copy(apiKey = "rotated-credential")
        val secondAgentRuntime = newAgentRuntime()
        processRuntime.agentRuntime = secondAgentRuntime
        processRuntime.appliedRuntimeConfig = secondConfig

        assertSame(secondAgentRuntime, processRuntime.agentRuntime)
        assertNotSame(firstAgentRuntime, processRuntime.agentRuntime)
        assertEquals(secondConfig, processRuntime.appliedRuntimeConfig)
    }

    private fun demoRuntimeConfig(): DemoRuntimeConfig = DemoRuntimeConfig(
        baseUrl = "https://provider.example",
        apiKey = "test-credential",
        model = "stable-model",
        maxOutputTokens = 8192,
        protocol = ProviderProtocol.AUTO,
        contextWindow = "200K",
        autoCompaction = true,
        compactionThreshold = 0.70
    )

    private fun newAgentRuntime(): AgentRuntime = AgentRuntime.Builder()
        .llmProvider(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                ModelResponse(content = "unused")
        })
        .build()

    private class TestApplicationContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
