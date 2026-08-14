package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertEquals(0, runtime.cancelAllPlugins())
        runtime.close()
        runtime.close()
    }

    @Test
    fun builderFailsFastWhenPluginsRegisterDuplicateToolNames() {
        val builder = AgentRuntime.Builder()
            .llmProvider(RecordingProvider())
            .register(TestPlugin())

        val error = try {
            builder.register(TestPlugin(id = "second-plugin"))
            fail("Expected duplicate plugin Tool registration to fail")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals("Tool name already registered: 'plugin_probe'", error.message)
    }

    @Test
    fun runtimeForwardsCancellationAndClosesEachPluginOnlyOnce() {
        val closeOrder = mutableListOf<String>()
        val first = LifecyclePlugin(id = "first", cancellationCount = 2, closeOrder = closeOrder)
        val second = LifecyclePlugin(id = "second", cancellationCount = 3, closeOrder = closeOrder)
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingProvider())
            .register(first)
            .register(second)
            .build()

        assertEquals(5, runtime.cancelAllPlugins())
        assertEquals(5, runtime.cancelAllPlugins())
        assertEquals(2, first.cancelCalls)
        assertEquals(2, second.cancelCalls)

        runtime.close()
        runtime.close()

        assertEquals(1, first.closeCalls)
        assertEquals(1, second.closeCalls)
        assertEquals(listOf("second", "first"), closeOrder)
        assertEquals(0, runtime.cancelAllPlugins())
    }

    private class TestPlugin(
        override val id: String = "test-plugin"
    ) : AgentCapabilityPlugin {

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

    private class LifecyclePlugin(
        override val id: String,
        private val cancellationCount: Int,
        private val closeOrder: MutableList<String>
    ) : AgentCapabilityPlugin {
        var cancelCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set

        override fun tools(): List<AgentTool> = emptyList()

        override fun skills(): List<AndroidSkill> = emptyList()

        override fun cancelAll(): Int {
            cancelCalls++
            return cancellationCount
        }

        override fun close() {
            closeCalls++
            closeOrder += id
        }
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
