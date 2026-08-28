package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeAgentInstructionsTest {
    @Test
    fun `runtime instructions are global and do not enter session history`() = runBlocking {
        val provider = RecordingProvider()
        val session = AgentSession(
            id = "runtime-instructions",
            messages = listOf(AgentMessage.System("Host system prompt"))
        )
        val runtime = AgentRuntime(
            llmProvider = provider,
            toolRegistry = ToolRegistry(),
            agentInstructions = listOf("SDK runtime AGENTS.md contract")
        )

        runtime.run(session, "hello").toList()

        assertEquals(
            listOf("SDK runtime AGENTS.md contract", "Host system prompt"),
            provider.requests.single().messages.filterIsInstance<AgentMessage.System>().map { it.content }
        )
        assertFalse(session.messages.any { it == AgentMessage.System("SDK runtime AGENTS.md contract") })
    }

    @Test
    fun `registered plugin contributes runtime instructions automatically`() = runBlocking {
        val provider = RecordingProvider()
        val plugin = object : AgentCapabilityPlugin {
            override val id: String = "runtime-contract-test"
            override fun tools(): List<AgentTool> = emptyList()
            override fun skills(): List<AndroidSkill> = emptyList()
            override fun agentInstructions(): List<String> = listOf("plugin AGENTS.md contract")
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(plugin)
            .build()

        runtime.run(AgentSession(id = "plugin-instructions"), "hello").toList()

        assertEquals(
            listOf("plugin AGENTS.md contract"),
            provider.requests.single().messages.filterIsInstance<AgentMessage.System>().map { it.content }
        )
    }

    private class RecordingProvider : LLMProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse(content = "done")
        }
    }
}
