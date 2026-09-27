package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducers for the round-7 review: skill assembly treated a duplicate skill
 * id as a fatal error. Round 5 closed the one route it knew about - `skill_save`
 * refusing reserved ids - and recorded why the throw is unacceptable: "such a
 * save bricks every later run with 'Duplicate skill id' at skill-assembly time
 * and cannot be undone by the agent (skill tools never run again)".
 *
 * The throw itself stayed, so any other writer to the skill directory reaches the
 * same brick: a file dropped by `terminal_bash_execute`, the private file tools,
 * an imported skill pack, or a restored backup. Assembly runs before the tool
 * loop, so the model never gets a chance to delete the colliding file.
 */
class SkillIdCollisionBrickTest {
    @Test
    fun fileBackedSkillCollidingWithAHostSkillDoesNotBrickEveryRun() = runBlocking {
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(FileBackedCollisionPlugin())
            .register(HostSkillPlugin())
            .build()

        val events = runtime.run(AgentSession("collision-1"), "hello").toList()

        assertTrue(
            "a model-authored skill collision must not end the run: " +
                events.map { it::class.simpleName },
            events.none { it is AgentEvent.Failed }
        )
        val assembled = resolver.receivedSkills.single()
        assertEquals(
            "the colliding file-backed skill must lose, not kill the run",
            listOf("agent-scheduled-tasks"),
            assembled.map { it.id }
        )
        assertEquals(
            "the host-provided skill keeps its instructions",
            "HOST_INSTRUCTIONS",
            assembled.single().instructions
        )
    }

    @Test
    fun twoFileBackedSkillsWithTheSameIdDegradeToTheFirst() = runBlocking {
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(FileBackedPairPlugin())
            .build()

        runtime.run(AgentSession("collision-2"), "hello").toList()

        val assembled = resolver.receivedSkills.single()
        assertEquals(listOf("shared-file-id"), assembled.map { it.id })
        assertEquals("FILE_FIRST", assembled.single().instructions)
    }

    @Test
    fun twoHostSkillsWithTheSameIdStillFailTheRunLoudly() = runBlocking {
        // A collision between two host contributions is a wiring bug in the
        // consuming app, so it must stay visible instead of silently picking one.
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(RecordingSkillResolver())
            .register(HostSkillPlugin("duplicate-host-id", pluginId = "host-a"))
            .register(HostSkillPlugin("duplicate-host-id", pluginId = "host-b"))
            .build()

        val events = runtime.run(AgentSession("collision-3"), "hello").toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(
            "the host-vs-host collision must still be reported, got: ${failed.message}",
            failed.message.contains("Duplicate skill id")
        )
    }

    @Test
    fun hostSkillTakesThePositionOfTheFileSkillItReplaces() = runBlocking {
        // The replacement must be in-place: skill injection order is part of the
        // context the model sees, and must not depend on which contribution
        // happened to be file-backed.
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(FileBackedNeighbourPlugin())
            .register(HostSkillInTheMiddlePlugin())
            .build()

        runtime.run(AgentSession("collision-order"), "hello").toList()

        assertEquals(
            listOf("file-first", "shared-file-id", "host-last"),
            resolver.receivedSkills.single().map { it.id }
        )
        assertEquals(
            "HOST_INSTRUCTIONS",
            resolver.receivedSkills.single()[1].instructions
        )
    }

    private class FileBackedCollisionPlugin : AgentCapabilityPlugin {
        override val id: String = "file-backed-collision"
        override fun tools(): List<AgentTool> = emptyList()
        override fun skills(): List<AndroidSkill> = emptyList()

        override fun skillProviders(): List<AndroidSkillProvider> = listOf(
            object : AndroidSkillProvider {
                override val source: AndroidSkillProviderSource =
                    AndroidSkillProviderSource.FILE_BACKED

                override fun skills(): List<AndroidSkill> = listOf(
                    AndroidSkill(
                        id = "agent-scheduled-tasks",
                        description = "A file that landed in the skill directory.",
                        instructions = "FILE_IMPOSTOR"
                    )
                )
            }
        )
    }

    private class FileBackedPairPlugin : AgentCapabilityPlugin {
        override val id: String = "file-backed-pair"
        override fun tools(): List<AgentTool> = emptyList()
        override fun skills(): List<AndroidSkill> = emptyList()

        override fun skillProviders(): List<AndroidSkillProvider> = listOf(
            object : AndroidSkillProvider {
                override val source: AndroidSkillProviderSource =
                    AndroidSkillProviderSource.FILE_BACKED

                override fun skills(): List<AndroidSkill> = listOf(
                    AndroidSkill("shared-file-id", "first", "FILE_FIRST"),
                    AndroidSkill("shared-file-id", "second", "FILE_SECOND")
                )
            }
        )
    }

    private class HostSkillPlugin(
        private val skillId: String = "agent-scheduled-tasks",
        pluginId: String? = null
    ) : AgentCapabilityPlugin {
        override val id: String = pluginId ?: "host-$skillId"
        override fun tools(): List<AgentTool> = emptyList()

        override fun skills(): List<AndroidSkill> = listOf(
            AndroidSkill(id = skillId, description = "Host skill.", instructions = "HOST_INSTRUCTIONS")
        )
    }

    private class FileBackedNeighbourPlugin : AgentCapabilityPlugin {
        override val id: String = "file-backed-neighbours"
        override fun tools(): List<AgentTool> = emptyList()
        override fun skills(): List<AndroidSkill> = emptyList()

        override fun skillProviders(): List<AndroidSkillProvider> = listOf(
            object : AndroidSkillProvider {
                override val source: AndroidSkillProviderSource =
                    AndroidSkillProviderSource.FILE_BACKED

                override fun skills(): List<AndroidSkill> = listOf(
                    AndroidSkill("file-first", "before", "FILE_BEFORE"),
                    AndroidSkill("shared-file-id", "contested", "FILE_IMPOSTOR")
                )
            }
        )
    }

    private class HostSkillInTheMiddlePlugin : AgentCapabilityPlugin {
        override val id: String = "host-neighbours"
        override fun tools(): List<AgentTool> = emptyList()

        override fun skills(): List<AndroidSkill> = listOf(
            AndroidSkill("shared-file-id", "contested", "HOST_INSTRUCTIONS"),
            AndroidSkill("host-last", "after", "HOST_AFTER")
        )
    }

    private class RecordingSkillResolver : AndroidSkillResolver {
        val receivedSkills = mutableListOf<List<AndroidSkill>>()

        override fun resolve(
            userMessage: String,
            skills: List<AndroidSkill>,
            availableToolNames: Set<String>
        ): List<AndroidSkill> {
            receivedSkills += skills
            return skills
        }
    }

    private class RecordingLLMProvider : LLMProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse("done")
    }
}
