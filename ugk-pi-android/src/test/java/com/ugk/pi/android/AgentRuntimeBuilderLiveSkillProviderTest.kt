package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeBuilderLiveSkillProviderTest {
    @Test
    fun `builder supplied provider is queried per run and reflects updated skills`() = runBlocking {
        val llm = RecordingLLMProvider()
        val skillProvider = MutableAndroidSkillProvider(
            listOf(skill("live-skill", "android settings help", "VERSION_ONE_MARKER"))
        )
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillProvider(skillProvider)
            .build()

        runtime.run(AgentSession("s1"), "android settings question").toList()
        runtime.run(AgentSession("s2"), "android settings question").toList()

        assertEquals(2, llm.requests.size)
        val firstInjection = systemSkillMessage(llm.requests[0])
        val secondInjection = systemSkillMessage(llm.requests[1])
        assertTrue(firstInjection.contains("VERSION_ONE_MARKER"))
        assertFalse(firstInjection.contains("VERSION_TWO_MARKER"))
        assertTrue(secondInjection.contains("VERSION_TWO_MARKER"))
        assertFalse(secondInjection.contains("VERSION_ONE_MARKER"))
    }

    @Test
    fun `builder supplied provider replaces plugin registered skills`() = runBlocking {
        val llm = RecordingLLMProvider()
        val pluginSkill = skill("plugin-skill", "plugin only skill", "PLUGIN_SKILL_MARKER")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .register(
                object : AgentCapabilityPlugin {
                    override val id = "test-plugin"
                    override fun tools(): List<AgentTool> = emptyList()
                    override fun skills(): List<AndroidSkill> = listOf(pluginSkill)
                }
            )
            .skillProvider(
                MutableAndroidSkillProvider(
                    listOf(skill("live-skill", "android settings help", "LIVE_SKILL_MARKER"))
                )
            )
            .build()

        runtime.run(AgentSession("s3"), "android settings question").toList()

        val injection = systemSkillMessage(llm.requests.single())
        assertTrue(injection.contains("LIVE_SKILL_MARKER"))
        assertFalse(injection.contains("PLUGIN_SKILL_MARKER"))
    }

    private fun systemSkillMessage(request: ModelRequest): String =
        request.messages
            .filterIsInstance<AgentMessage.System>()
            .firstOrNull { it.content.contains("Android-Skill:") }
            ?.content
            ?: ""

    private fun skill(id: String, description: String, instructions: String) = AndroidSkill(
        id = id,
        description = description,
        instructions = instructions
    )

    private class MutableAndroidSkillProvider(
        private var current: List<AndroidSkill>
    ) : AndroidSkillProvider {
        private var callCount = 0

        override fun skills(): List<AndroidSkill> {
            callCount++
            if (callCount >= 2) {
                current = listOf(skill("live-skill", "android settings help", "VERSION_TWO_MARKER"))
            }
            return current
        }

        private fun skill(id: String, description: String, instructions: String) = AndroidSkill(
            id = id,
            description = description,
            instructions = instructions
        )
    }

    private class RecordingLLMProvider : LLMProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse("done")
        }
    }
}
