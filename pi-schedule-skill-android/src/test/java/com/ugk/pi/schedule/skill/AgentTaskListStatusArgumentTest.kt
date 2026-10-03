package com.ugk.pi.schedule.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `agent_task_list.status` accepts, pinned at the landing point.
 *
 * The filter was resolved with `runCatching { AgentTaskStatus.valueOf(it) }` and
 * a null result meant "no filter", so `{"status":"SHEDULED"}` - or a lowercase
 * name, or a number - silently returned every row, cancelled and failed ones
 * included, and reported `ok=true` with a count. The caller asked for one status
 * and got the whole table with no signal that its filter had been dropped.
 *
 * The same module reads a model-declared optional through `optionalElement` in
 * `agent_task_update` further down, and the sibling memory tool in
 * `pi-agent-skill-runtime-android` refuses a `category` outside its enum by name
 * (`AgentSkillTools.execute` -> `categoryError`). This tool was the landing point
 * that did neither: unusable values were the only way to get an unfiltered
 * listing out of it.
 *
 * Direction relative to `main`: a tightening. Valid uppercase names behave exactly
 * as before (those cases are the controls), and `status: null` still means "no
 * filter" because a gateway serializes an unfilled optional as JSON null.
 */
class AgentTaskListStatusArgumentTest {

    @Test
    fun statusArgumentDomainIsPinned() {
        val failures = mutableListOf<String>()

        listOf(
            AcceptedCase("SCHEDULED", JsonPrimitive("SCHEDULED"), 1),
            AcceptedCase("CANCELLED", JsonPrimitive("CANCELLED"), 1),
            AcceptedCase("EXPIRED matches nothing here", JsonPrimitive("EXPIRED"), 1, "No scheduled tasks found."),
            AcceptedCase("explicit null reads as absent", JsonNull, 3)
        ).forEach { case ->
            val result = runCatching { execute(JsonObject(mapOf("status" to case.value))) }
                .getOrElse { failure ->
                    failures += "${case.name}: threw ${failure::class.simpleName} ${failure.message} instead of rows"
                    return@forEach
                }
            if (result.isError) {
                failures += "${case.name}: expected rows, got error ${result.content}"
            } else if (case.expectedMarker != null) {
                if (!result.content.contains(case.expectedMarker)) {
                    failures += "${case.name}: expected a listing containing \"${case.expectedMarker}\", got ${result.content}"
                }
            } else {
                val rows = result.content.lines().filter { it.isNotBlank() }.size
                if (rows != case.expectedRows) {
                    failures += "${case.name}: expected ${case.expectedRows} rows, got $rows (${result.content})"
                }
            }
        }

        listOf(
            "lowercase name" to JsonPrimitive("scheduled"),
            "misspelled name" to JsonPrimitive("SHEDULED"),
            "a number" to JsonPrimitive(3),
            "an empty string" to JsonPrimitive(""),
            "an array" to JsonArray(emptyList()),
            "an object" to JsonObject(emptyMap())
        ).forEach { (name, value) ->
            val result = runCatching { execute(JsonObject(mapOf("status" to value))) }
                .getOrElse { failure ->
                    failures += "$name: threw ${failure::class.simpleName} ${failure.message} instead of refusing by name"
                    return@forEach
                }
            if (!result.isError) {
                failures += "$name: expected a refusal naming status, got ${result.content.lines().count { it.isNotBlank() }} rows"
                return@forEach
            }
            val message = result.metadata?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: result.content
            if (!message.contains("status")) {
                failures += "$name: refused without naming the argument: $message"
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    /** Control: the key absent keeps every row, which is what the schema documents. */
    @Test
    fun omittedStatusListsEveryRow() = runBlocking {
        val result = execute(buildJsonObject { })

        assertEquals(false, result.isError)
        assertEquals(3, result.content.lines().filter { it.isNotBlank() }.size)
        assertTrue("scheduled row missing: ${result.content}", result.content.contains("scheduled-one"))
        assertTrue("cancelled row missing: ${result.content}", result.content.contains("cancelled-two"))
    }

    private data class AcceptedCase(
        val name: String,
        val value: JsonElement,
        val expectedRows: Int,
        val expectedMarker: String? = null
    )

    private fun execute(input: JsonObject) = runBlocking {
        val store = InMemoryAgentTaskStore()
        store.upsert(task(id = "scheduled-one", status = AgentTaskStatus.SCHEDULED))
        store.upsert(task(id = "cancelled-two", status = AgentTaskStatus.CANCELLED))
        store.upsert(task(id = "failed-three", status = AgentTaskStatus.FAILED))
        AgentTaskListTool(store).execute(
            ToolCall(id = "call-1", name = "agent_task_list", input = input),
            ToolExecutionContext(sessionId = "session-1")
        )
    }

    private fun task(id: String, status: AgentTaskStatus): AgentTask = AgentTask(
        id = id,
        sessionId = "session-1",
        title = id,
        schedule = AgentTaskSchedule.OneShot(runAtMillis = 2_000L),
        action = AgentTaskAction.NotifyUser(message = "reminder"),
        status = status,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        nextRunAtMillis = 2_000L
    )
}
