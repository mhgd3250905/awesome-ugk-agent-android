package com.ugk.pi.schedule.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `agent_task_update` treats every field it does not mention as "keep what is
 * there", and a Java/Pojo gateway serializes the fields it is not filling as JSON
 * null rather than omitting them. `call.input["schedule"] != null` is a Kotlin
 * null test, not a JSON one - `JsonNull` is a value - so a title-only edit that
 * carried `"schedule":null` was refused as an invalid schedule instead of
 * renaming the task.
 *
 * Refusing is the smaller failure here: the same tool happily replaced the
 * schedule of an armed task before, and round 9's fix exists to keep an overdue
 * one-shot alive across an edit. The rule being applied is the one already stated
 * in the module's own docs - absent means unchanged.
 */
class AgentTaskUpdateNullOptionalArgumentTest {

    private fun oneShotSchedule() = buildJsonObject {
        put("type", JsonPrimitive("ONE_SHOT"))
        put("startAfterSeconds", JsonPrimitive(60))
    }

    private fun notifyAction() = buildJsonObject {
        put("type", JsonPrimitive("NOTIFY_USER"))
        put("message", JsonPrimitive("该喝水了"))
    }

    /**
     * One test per key: assertions inside a shared loop let the first red hide the
     * later ones, which is the opposite of attributing a mutation to a landing
     * point.
     */
    @Test
    fun nullScheduleKeepsTheExistingTask() = runBlocking {
        assertNullOptionalKeepsTheTask("schedule")
    }

    @Test
    fun nullActionKeepsTheExistingTask() = runBlocking {
        assertNullOptionalKeepsTheTask("action")
    }

    private suspend fun assertNullOptionalKeepsTheTask(key: String) {
        val store = InMemoryAgentTaskStore()
        val created = AgentTaskCreateTool(
            store,
            NoopAgentTaskScheduler,
            FixedClock(1_000L),
            SequentialTaskIdGenerator("task")
        ).execute(
            call(
                "agent_task_create",
                "title" to JsonPrimitive("喝水提醒"),
                "schedule" to oneShotSchedule(),
                "action" to notifyAction()
            ),
            context()
        )
        assertFalse("the create itself must succeed: ${created.content}", created.isError)
        val existing = store.get("task_1") ?: error("task not stored")

        val updated = AgentTaskUpdateTool(store, NoopAgentTaskScheduler, FixedClock(2_000L)).execute(
            call(
                "agent_task_update",
                "taskId" to JsonPrimitive("task_1"),
                "title" to JsonPrimitive("补水提醒"),
                key to JsonNull
            ),
            context()
        )

        assertFalse(
            "a null $key is the model not changing it, not an invalid one: ${updated.content}",
            updated.isError
        )
        val stored = store.get("task_1")!!
        assertEquals("补水提醒", stored.title)
        assertEquals(existing.schedule, stored.schedule)
        assertEquals(existing.action, stored.action)
        assertEquals(existing.nextRunAtMillis, stored.nextRunAtMillis)
    }

    /**
     * The schema's `required` list and the refusals must be the same fact. Before
     * this round the tool advertised nothing as required while refusing a missing
     * `taskId`/`title`/`schedule`/`action`, so a call written from the schema came
     * back as an error the model could not have predicted.
     */
    @Test
    fun declaredRequiredArgumentsAreTheOnesTheToolsRefuse() = runBlocking {
        val store = InMemoryAgentTaskStore()
        assertEquals(
            setOf("title", "schedule", "action"),
            (AgentTaskCreateTool(store, NoopAgentTaskScheduler).inputSchema["required"] as JsonArray)
                .map { (it as JsonPrimitive).content }.toSet()
        )
        assertEquals(
            setOf("taskId"),
            (AgentTaskUpdateTool(store, NoopAgentTaskScheduler).inputSchema["required"] as JsonArray)
                .map { (it as JsonPrimitive).content }.toSet()
        )

        val withoutTitle = AgentTaskCreateTool(store, NoopAgentTaskScheduler, FixedClock(1_000L), SequentialTaskIdGenerator("task"))
            .execute(
                call(
                    "agent_task_create",
                    "schedule" to oneShotSchedule(),
                    "action" to notifyAction()
                ),
                context()
            )
        assertTrue(withoutTitle.isError)
        assertEquals("MISSING_TITLE", (withoutTitle.metadata["code"] as? JsonPrimitive)?.content)

        val withoutTaskId = AgentTaskUpdateTool(store, NoopAgentTaskScheduler).execute(
            call("agent_task_update", "title" to JsonPrimitive("改名")),
            context()
        )
        assertTrue(withoutTaskId.isError)
        assertEquals("MISSING_TASK_ID", (withoutTaskId.metadata["code"] as? JsonPrimitive)?.content)
    }

    /** The other direction: a declared replacement still replaces. */
    @Test
    fun declaredScheduleStillReplaces() = runBlocking {
        val store = InMemoryAgentTaskStore()
        AgentTaskCreateTool(store, NoopAgentTaskScheduler, FixedClock(1_000L), SequentialTaskIdGenerator("task"))
            .execute(
                call(
                    "agent_task_create",
                    "title" to JsonPrimitive("喝水提醒"),
                    "schedule" to oneShotSchedule(),
                    "action" to notifyAction()
                ),
                context()
            )
        val before = store.get("task_1")!!

        val updated = AgentTaskUpdateTool(store, NoopAgentTaskScheduler, FixedClock(2_000L)).execute(
            call(
                "agent_task_update",
                "taskId" to JsonPrimitive("task_1"),
                "schedule" to buildJsonObject {
                    put("type", JsonPrimitive("ONE_SHOT"))
                    put("startAfterSeconds", JsonPrimitive(300))
                }
            ),
            context()
        )
        assertFalse("a declared schedule must be applied: ${updated.content}", updated.isError)
        assertTrue(
            "the next run must move out with the new interval",
            (store.get("task_1")!!.nextRunAtMillis ?: 0L) > (before.nextRunAtMillis ?: 0L)
        )
    }

    /** Declared-but-unusable stays refused: the null rule must not become a wildcard. */
    @Test
    fun nonObjectScheduleIsStillRefused() = runBlocking {
        val store = InMemoryAgentTaskStore()
        AgentTaskCreateTool(store, NoopAgentTaskScheduler, FixedClock(1_000L), SequentialTaskIdGenerator("task"))
            .execute(
                call(
                    "agent_task_create",
                    "title" to JsonPrimitive("喝水提醒"),
                    "schedule" to oneShotSchedule(),
                    "action" to notifyAction()
                ),
                context()
            )

        val original = store.get("task_1")!!.schedule

        val updated = AgentTaskUpdateTool(store, NoopAgentTaskScheduler, FixedClock(2_000L)).execute(
            call(
                "agent_task_update",
                "taskId" to JsonPrimitive("task_1"),
                "schedule" to JsonPrimitive("tomorrow")
            ),
            context()
        )
        assertTrue("a string schedule must still be refused", updated.isError)
        assertEquals(
            "the refused update must leave the stored schedule untouched",
            original,
            store.get("task_1")!!.schedule
        )
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(sessionId = "session-1")

    private fun call(name: String, vararg values: Pair<String, JsonElement>): ToolCall = ToolCall(
        id = "call-1",
        name = name,
        input = buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        }
    )
}
