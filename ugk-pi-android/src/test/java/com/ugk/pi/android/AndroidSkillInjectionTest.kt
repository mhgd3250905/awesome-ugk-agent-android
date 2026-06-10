package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSkillInjectionTest {
    @Test
    fun `injects matching skill details into model request without mutating session history`() = runBlocking {
        val provider = RecordingLLMProvider()
        val skill = AndroidSkill(
            id = "app-settings-inspection",
            description = "Use when the user asks about app permissions, notification settings, battery optimization, or Android app settings.",
            instructions = "This skill explains how to inspect app settings with prebuilt host methods.",
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "get_app_environment_info",
                    purpose = "Read app package, notification, permission, and battery optimization state.",
                    whenToUse = "Use when the user asks why app settings or permissions look wrong.",
                    resultSemantics = "enabled means the setting allows the app; disabled means the user should open settings."
                )
            )
        )
        val runtime = AgentRuntime(
            llmProvider = provider,
            toolRegistry = ToolRegistry().register(NoopTool("get_app_environment_info")),
            skillProvider = StaticAndroidSkillProvider(listOf(skill))
        )
        val session = AgentSession(
            id = "s1",
            messages = mutableListOf(AgentMessage.System("Base system prompt."))
        )

        runtime.run(session, "Why are notifications disabled in the app settings?").toList()

        val systemMessages = provider.requests.single().messages.filterIsInstance<AgentMessage.System>()
        assertEquals(2, systemMessages.size)
        assertEquals("Base system prompt.", systemMessages.first().content)
        assertTrue(systemMessages.last().content.contains("app-settings-inspection"))
        assertTrue(systemMessages.last().content.contains("get_app_environment_info"))
        assertTrue(systemMessages.last().content.contains("battery optimization"))
        assertEquals(0, session.messages.filterIsInstance<AgentMessage.System>().count {
            it.content.contains("app-settings-inspection")
        })
    }

    @Test
    fun `does not inject unrelated skills`() = runBlocking {
        val provider = RecordingLLMProvider()
        val runtime = AgentRuntime(
            llmProvider = provider,
            toolRegistry = ToolRegistry().register(NoopTool("get_app_environment_info")),
            skillProvider = StaticAndroidSkillProvider(
                listOf(
                    AndroidSkill(
                        id = "app-settings-inspection",
                        description = "Use when the user asks about app permissions or Android app settings.",
                        instructions = "Inspect app settings.",
                        methods = listOf(
                            AndroidSkillMethod(
                                toolName = "get_app_environment_info",
                                purpose = "Read app settings state.",
                                whenToUse = "Use for settings questions.",
                                resultSemantics = "Returns setting state."
                            )
                        )
                    )
                )
            )
        )

        runtime.run(AgentSession("s2"), "Tell me a short joke.").toList()

        assertFalse(
            provider.requests.single().messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("app-settings-inspection") }
        )
    }

    @Test
    fun `matches non ascii trigger text`() = runBlocking {
        val provider = RecordingLLMProvider()
        val runtime = AgentRuntime(
            llmProvider = provider,
            toolRegistry = ToolRegistry().register(NoopTool("get_app_environment_info")),
            skillProvider = StaticAndroidSkillProvider(
                listOf(
                    AndroidSkill(
                        id = "app-settings-inspection",
                        description = "Use for Android app settings.",
                        triggers = listOf("\u901a\u77e5", "\u6743\u9650"),
                        instructions = "Inspect notification and permission state.",
                        methods = listOf(
                            AndroidSkillMethod(
                                toolName = "get_app_environment_info",
                                purpose = "Read app settings state.",
                                whenToUse = "Use for settings questions.",
                                resultSemantics = "Returns setting state."
                            )
                        )
                    )
                )
            )
        )

        runtime.run(AgentSession("s3"), "\u4e3a\u4ec0\u4e48\u901a\u77e5\u6743\u9650\u6709\u95ee\u9898\uff1f").toList()

        assertTrue(
            provider.requests.single().messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("app-settings-inspection") }
        )
    }

    private class RecordingLLMProvider : LLMProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse("done")
        }
    }

    private class NoopTool(
        override val name: String
    ) : AgentTool {
        override val description = "No-op test tool."
        override val inputSchema = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult = ToolResult(call.id, name, "ok")
    }
}
