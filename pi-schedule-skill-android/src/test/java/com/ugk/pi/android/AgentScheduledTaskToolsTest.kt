package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentScheduledTaskToolsTest {
    @Test
    fun createOneShotReminderPersistsAndSchedulesTask() = runBlocking {
        val store = InMemoryAgentTaskStore()
        val scheduler = RecordingAgentTaskScheduler()
        val tools = agentScheduledTaskTools(
            store = store,
            scheduler = scheduler,
            clock = FixedClock(1_000L),
            idGenerator = SequentialTaskIdGenerator("task")
        ).associateBy { it.name }

        val result = tools["agent_task_create"]!!.execute(
            call(
                "agent_task_create",
                "title" to JsonPrimitive("校准提醒"),
                "schedule" to buildJsonObject {
                    put("type", JsonPrimitive("ONE_SHOT"))
                    put("startAfterSeconds", JsonPrimitive(1800))
                },
                "action" to buildJsonObject {
                    put("type", JsonPrimitive("NOTIFY_USER"))
                    put("message", JsonPrimitive("该校准了"))
                }
            ),
            context()
        )

        assertFalse(result.isError)
        assertEquals("task_1", result.metadata["taskId"]!!.jsonPrimitive.content)
        assertEquals("SCHEDULED", result.metadata["status"]!!.jsonPrimitive.content)
        assertEquals(1_801_000L, result.metadata["nextRunAtMillis"]!!.jsonPrimitive.content.toLong())
        assertEquals("task_1", scheduler.scheduled.single().id)
    }

    @Test
    fun createRepeatingAgentPromptValidatesIntervalAndEnd() = runBlocking {
        val tools = tools().associateBy { it.name }

        val invalid = tools["agent_task_create"]!!.execute(
            call(
                "agent_task_create",
                "title" to JsonPrimitive("bad"),
                "schedule" to buildJsonObject {
                    put("type", JsonPrimitive("REPEATING_UNTIL"))
                    put("startAfterSeconds", JsonPrimitive(0))
                    put("intervalSeconds", JsonPrimitive(0))
                    put("endAfterSeconds", JsonPrimitive(10))
                },
                "action" to buildJsonObject {
                    put("type", JsonPrimitive("RUN_AGENT_PROMPT"))
                    put("prompt", JsonPrimitive("check"))
                }
            ),
            context()
        )

        assertTrue(invalid.isError)
        assertEquals("INVALID_SCHEDULE", invalid.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun listGetUpdateAndCancelUsePersistedTaskState() = runBlocking {
        val store = InMemoryAgentTaskStore()
        val scheduler = RecordingAgentTaskScheduler()
        val tools = agentScheduledTaskTools(
            store = store,
            scheduler = scheduler,
            clock = FixedClock(10_000L),
            idGenerator = SequentialTaskIdGenerator("task")
        ).associateBy { it.name }
        val created = tools["agent_task_create"]!!.execute(
            call(
                "agent_task_create",
                "title" to JsonPrimitive("喝水提醒"),
                "schedule" to buildJsonObject {
                    put("type", JsonPrimitive("REPEATING_UNTIL"))
                    put("startAfterSeconds", JsonPrimitive(0))
                    put("intervalSeconds", JsonPrimitive(600))
                    put("endAfterSeconds", JsonPrimitive(7200))
                },
                "action" to buildJsonObject {
                    put("type", JsonPrimitive("NOTIFY_USER"))
                    put("message", JsonPrimitive("该喝水了"))
                }
            ),
            context()
        )
        val taskId = created.metadata["taskId"]!!.jsonPrimitive.content

        val list = tools["agent_task_list"]!!.execute(call("agent_task_list"), context())
        val get = tools["agent_task_get"]!!.execute(call("agent_task_get", "taskId" to JsonPrimitive(taskId)), context())
        val updated = tools["agent_task_update"]!!.execute(
            call(
                "agent_task_update",
                "taskId" to JsonPrimitive(taskId),
                "title" to JsonPrimitive("喝水提醒更新"),
                "action" to buildJsonObject {
                    put("type", JsonPrimitive("NOTIFY_USER"))
                    put("message", JsonPrimitive("喝水"))
                }
            ),
            context()
        )
        val cancelled = tools["agent_task_cancel"]!!.execute(call("agent_task_cancel", "taskId" to JsonPrimitive(taskId)), context())

        assertFalse(list.isError)
        assertEquals(1, list.metadata["tasks"]!!.jsonArray.size)
        assertFalse(get.isError)
        assertEquals("喝水提醒", get.metadata["task"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertFalse(updated.isError)
        assertEquals("喝水提醒更新", updated.metadata["task"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertFalse(cancelled.isError)
        assertEquals("CANCELLED", cancelled.metadata["task"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(taskId, scheduler.cancelled.single())
    }

    @Test
    fun updateRejectsEndedTasks() = runBlocking {
        val store = InMemoryAgentTaskStore()
        val ended = sampleTask("task_ended", AgentTaskStatus.COMPLETED)
        store.upsert(ended)
        val update = AgentTaskUpdateTool(store, RecordingAgentTaskScheduler(), FixedClock(1_000L))

        val result = update.execute(
            call("agent_task_update", "taskId" to JsonPrimitive("task_ended"), "title" to JsonPrimitive("new")),
            context()
        )

        assertTrue(result.isError)
        assertEquals("TASK_ENDED", result.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun scheduledTasksSkillAdvertisesManagementTools() {
        val skill = agentScheduledTasksSkill()
        val toolNames = skill.methods.map { it.toolName }.toSet()

        assertTrue(toolNames.containsAll(setOf(
            "agent_task_create",
            "agent_task_list",
            "agent_task_get",
            "agent_task_update",
            "agent_task_cancel"
        )))
        assertTrue(skill.instructions.contains("Do not simulate waiting"))
    }

    @Test
    fun scheduleTaskAgentPluginExposesTaskToolsAndSkill() {
        val plugin = ScheduleTaskAgentPlugin(
            store = InMemoryAgentTaskStore(),
            scheduler = NoopAgentTaskScheduler
        )

        assertEquals("agent-scheduled-tasks", plugin.id)
        assertEquals(
            listOf(
                "agent_task_create",
                "agent_task_list",
                "agent_task_get",
                "agent_task_update",
                "agent_task_cancel"
            ),
            plugin.tools().map { it.name }
        )
        assertEquals(listOf("agent-scheduled-tasks"), plugin.skills().map { it.id })
    }

    private fun tools(): List<AgentTool> {
        return agentScheduledTaskTools(
            store = InMemoryAgentTaskStore(),
            scheduler = RecordingAgentTaskScheduler(),
            clock = FixedClock(1_000L),
            idGenerator = SequentialTaskIdGenerator("task")
        )
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(sessionId = "session-1")

    private fun call(name: String, vararg values: Pair<String, Any>): ToolCall {
        return ToolCall(
            id = "call-1",
            name = name,
            input = buildJsonObject {
                values.forEach { (key, value) ->
                    when (value) {
                        is JsonObject -> put(key, value)
                        is JsonPrimitive -> put(key, value)
                    }
                }
            }
        )
    }

    private fun sampleTask(id: String, status: AgentTaskStatus): AgentTask {
        return AgentTask(
            id = id,
            sessionId = "session-1",
            title = "done",
            schedule = AgentTaskSchedule.OneShot(runAtMillis = 2_000L),
            action = AgentTaskAction.NotifyUser(message = "done"),
            status = status,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
            nextRunAtMillis = null
        )
    }

    private class RecordingAgentTaskScheduler : AgentTaskScheduler {
        val scheduled = mutableListOf<AgentTask>()
        val cancelled = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            scheduled += task
        }

        override suspend fun cancel(taskId: String) {
            cancelled += taskId
        }
    }
}
