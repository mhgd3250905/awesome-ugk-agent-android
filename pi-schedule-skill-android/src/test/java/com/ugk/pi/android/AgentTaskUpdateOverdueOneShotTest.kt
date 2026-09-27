package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducers for the round-7 review: `agent_task_update` recomputed
 * `nextRunAtMillis` from the schedule without checking that a next occurrence
 * still exists. A one-shot whose platform trigger was delayed past its time
 * (Doze/idle alignment is documented behaviour) therefore became
 * `SCHEDULED` with a null next run, which `AndroidAgentTaskRuntime.schedule`
 * answers by cancelling the trigger - so editing the title of a reminder that
 * is due destroyed it, while the tool reported success and every later
 * convergence pass skipped it (`nextRunAtMillis != null` filter).
 */
class AgentTaskUpdateOverdueOneShotTest {
    @Test
    fun titleOnlyUpdateDoesNotDestroyAnArmedOneShotWhoseTimeHasPassed() = runBlocking {
        val store = InMemoryAgentTaskStore()
        val scheduler = RecordingAgentTaskScheduler()
        AgentTaskCreateTool(store, scheduler, FixedClock(1_000L), SequentialTaskIdGenerator("task")).execute(
            call(
                "agent_task_create",
                "title" to JsonPrimitive("喝水提醒"),
                "schedule" to buildJsonObject {
                    put("type", JsonPrimitive("ONE_SHOT"))
                    put("startAfterSeconds", JsonPrimitive(60))
                },
                "action" to buildJsonObject {
                    put("type", JsonPrimitive("NOTIFY_USER"))
                    put("message", JsonPrimitive("该喝水了"))
                }
            ),
            context()
        )
        val armedAt = store.get("task_1")!!.nextRunAtMillis

        // The alarm was deferred past 61 000 ms; the user rewords the reminder.
        val result = AgentTaskUpdateTool(store, scheduler, FixedClock(200_000L)).execute(
            call("agent_task_update", "taskId" to JsonPrimitive("task_1"), "title" to JsonPrimitive("喝水")),
            context()
        )

        val stored = store.get("task_1")!!
        assertTrue(
            "an edit that leaves the task unarmable must not be reported as success: " +
                "${stored.status} / ${stored.nextRunAtMillis} / result=${result.content}",
            result.isError
        )
        assertNull(
            "the tool must not hand the platform a SCHEDULED record with no next run",
            scheduler.scheduled.firstOrNull { it.nextRunAtMillis == null }
        )
        assertEquals(
            "the pending trigger and its occurrence must survive a refused edit",
            armedAt,
            stored.nextRunAtMillis
        )
        assertEquals("SCHEDULED", stored.status.name)
        assertEquals(
            "a refused edit must not be applied to the persisted record either",
            "喝水提醒",
            stored.title
        )
    }

    @Test
    fun titleOnlyUpdateStillWorksWhileTheOccurrenceIsInTheFuture() = runBlocking {
        val store = InMemoryAgentTaskStore()
        val scheduler = RecordingAgentTaskScheduler()
        AgentTaskCreateTool(store, scheduler, FixedClock(1_000L), SequentialTaskIdGenerator("task")).execute(
            call(
                "agent_task_create",
                "title" to JsonPrimitive("喝水提醒"),
                "schedule" to buildJsonObject {
                    put("type", JsonPrimitive("ONE_SHOT"))
                    put("startAfterSeconds", JsonPrimitive(600))
                },
                "action" to buildJsonObject {
                    put("type", JsonPrimitive("NOTIFY_USER"))
                    put("message", JsonPrimitive("该喝水了"))
                }
            ),
            context()
        )

        val result = AgentTaskUpdateTool(store, scheduler, FixedClock(2_000L)).execute(
            call("agent_task_update", "taskId" to JsonPrimitive("task_1"), "title" to JsonPrimitive("喝水")),
            context()
        )

        assertFalse(result.isError)
        assertEquals("喝水", store.get("task_1")!!.title)
        assertEquals(601_000L, store.get("task_1")!!.nextRunAtMillis)
    }

    @Test
    fun createRefusesAClockThatLeavesNoArmableOccurrence() = runBlocking {
        // A user-settable pre-1970 RTC makes runAt negative, and the platform
        // treats a negative trigger time as immediately due.
        val store = InMemoryAgentTaskStore()
        val scheduler = RecordingAgentTaskScheduler()
        val result = AgentTaskCreateTool(store, scheduler, FixedClock(-5_000L), SequentialTaskIdGenerator("task"))
            .execute(
                call(
                    "agent_task_create",
                    "title" to JsonPrimitive("喝水提醒"),
                    "schedule" to buildJsonObject {
                        put("type", JsonPrimitive("ONE_SHOT"))
                        put("startAfterSeconds", JsonPrimitive(1))
                    },
                    "action" to buildJsonObject {
                        put("type", JsonPrimitive("NOTIFY_USER"))
                        put("message", JsonPrimitive("该喝水了"))
                    }
                ),
                context()
            )

        assertTrue(result.isError)
        assertTrue(
            "the refusal must say why: ${result.content}",
            result.content.contains("no future occurrence")
        )
        assertEquals(emptyList<String>(), scheduler.scheduled.map { it.id })
        assertEquals(null, store.get("task_1"))
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(sessionId = "session-1")

    private fun call(name: String, vararg values: Pair<String, JsonElement>): ToolCall = ToolCall(
        id = "call-1",
        name = name,
        input = buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        }
    )

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
