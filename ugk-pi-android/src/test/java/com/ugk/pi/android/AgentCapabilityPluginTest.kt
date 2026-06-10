package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCapabilityPluginTest {
    @Test
    fun builderRegistersPluginToolsAndSkillsIntoRuntime() = runBlocking {
        val provider = RecordingProvider()
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(TestPlugin())
            .build()

        runtime.run(AgentSession("plugin-session"), "Use plugin skill now.").toList()

        val request = provider.requests.single()
        assertEquals(listOf("plugin_probe"), request.tools.map { it.name })
        assertTrue(
            request.messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("test-plugin-skill") }
        )
    }

    private class TestPlugin : AgentCapabilityPlugin {
        override val id: String = "test-plugin"

        override fun tools(): List<AgentTool> = listOf(ProbeTool())

        override fun skills(): List<AndroidSkill> = listOf(
            AndroidSkill(
                id = "test-plugin-skill",
                description = "Use when the user asks for plugin skill behavior.",
                triggers = listOf("plugin skill"),
                instructions = "Call the plugin probe when useful.",
                methods = listOf(
                    AndroidSkillMethod(
                        toolName = "plugin_probe",
                        purpose = "Probes plugin registration.",
                        whenToUse = "Use when the user asks for plugin skill behavior.",
                        resultSemantics = "Returns ok."
                    )
                )
            )
        )
    }

    private class ProbeTool : AgentTool {
        override val name: String = "plugin_probe"
        override val description: String = "Probe tool registered by a plugin."
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult = ToolResult(call.id, name, "ok")
    }

    private class RecordingProvider : LLMProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse("done")
        }
    }
}
