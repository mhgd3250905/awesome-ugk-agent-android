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
 * What `agent_task_list.activeOnly` accepts, pinned at the landing point.
 *
 * The shared tool-input accessor replaced this module's own copy during the
 * cross-cutting consolidation, and the two were not identical: the deleted copy
 * read the flag with kotlinx `booleanOrNull`, which accepts the case variants
 * "True"/"FALSE"; the shared one is the strict content parse and returns null for
 * them, and `AgentTaskListTool` mapped null to `false`. So `{"activeOnly":"True"}`
 * narrowed the listing to active tasks before the consolidation and widened it
 * back to every row - including cancelled and completed ones - after it.
 *
 * Round 12 recorded that widening instead of fixing it, on the reasoning that the
 * three-state mechanism was arriving in PR #13 and a second one-off reader would
 * leave the module with two competing rules. That premise expired when PR #13
 * merged: the module gained `optionalElement`, which `agent_task_update` uses
 * further down the same file. `AgentTaskListTool` was the landing point where an
 * unusable declaration was still read as absence instead of refused, so the fix
 * routes it through the rule the module already has rather than inventing one.
 * `agent_task_create` needs no routing: its nested `schedule`/`action` readers
 * already refuse a declared non-object by name (`parseSchedule`).
 *
 * Note the direction relative to `main`: this is a tightening. A declared value
 * this tool cannot honor is now refused by name instead of being read as absence,
 * so a mistyped filter fails loudly rather than returning a wider listing than the
 * caller asked for.
 */
class AgentTaskListActiveOnlyArgumentTest {

    @Test
    fun activeOnlyArgumentDomainIsPinned() {
        val failures = mutableListOf<String>()

        acceptedCases.forEach { case ->
            val outcome = runCatching { listRows(case.value) }
            when {
                outcome.isFailure -> failures += "${case.name}: threw ${outcome.exceptionOrNull()?.message} instead of rows"
                outcome.getOrNull() != case.expectedRows ->
                    failures += "${case.name}: expected ${case.expectedRows} rows, got ${outcome.getOrNull()}"
            }
        }

        refusedCases.forEach { case ->
            val result = runCatching { executeWithActiveOnly(case.value) }
                .getOrElse { failure ->
                    failures += "${case.name}: threw ${failure::class.simpleName} ${failure.message} instead of refusing by name"
                    return@forEach
                }
            if (!result.isError) {
                failures += "${case.name}: expected a refusal naming activeOnly, got rows=${result.content.lines().count { it.isNotBlank() }}"
                return@forEach
            }
            val message = result.metadata?.get("message")?.jsonPrimitiveOrNull()?.contentOrNull
                ?: result.content
            if (!message.contains("activeOnly")) {
                failures += "${case.name}: refused without naming the argument: $message"
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    /** A refusal must not be satisfiable by the error code alone: the message carries it. */
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private data class AcceptedCase(val name: String, val value: JsonElement, val expectedRows: Int)

    private data class RefusedCase(val name: String, val value: JsonElement)

    private val acceptedCases = listOf(
        AcceptedCase("boolean true", JsonPrimitive(true), expectedRows = 1),
        AcceptedCase("boolean false", JsonPrimitive(false), expectedRows = 2),
        AcceptedCase("quoted lowercase true", JsonPrimitive("true"), expectedRows = 1),
        AcceptedCase("quoted lowercase false", JsonPrimitive("false"), expectedRows = 2),
        AcceptedCase("explicit null reads as absent", JsonNull, expectedRows = 2)
    )

    private val refusedCases = listOf(
        RefusedCase("case variant True", JsonPrimitive("True")),
        RefusedCase("case variant FALSE", JsonPrimitive("FALSE")),
        RefusedCase("a number", JsonPrimitive(1)),
        RefusedCase("an empty string", JsonPrimitive("")),
        RefusedCase("an array", JsonArray(emptyList())),
        RefusedCase("an object", JsonObject(emptyMap()))
    )

    private fun listRows(value: JsonElement): Int {
        val result = executeWithActiveOnly(value)
        assertEquals(
            "${result.content}: the list tool returned an error instead of rows",
            false,
            result.isError
        )
        return result.content.lines().filter { it.isNotBlank() }.size
    }

    private fun executeWithActiveOnly(value: JsonElement) = runBlocking {
        val store = InMemoryAgentTaskStore()
        store.upsert(task(id = "scheduled-one", status = AgentTaskStatus.SCHEDULED))
        store.upsert(task(id = "cancelled-two", status = AgentTaskStatus.CANCELLED))
        AgentTaskListTool(store).execute(
            ToolCall(
                id = "call-1",
                name = "agent_task_list",
                input = JsonObject(mapOf("activeOnly" to value))
            ),
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

    /** Control: with the key absent the listing stays unfiltered, as documented. */
    @Test
    fun omittedActiveOnlyListsEveryRow() = runBlocking {
        val store = InMemoryAgentTaskStore()
        store.upsert(task(id = "scheduled-one", status = AgentTaskStatus.SCHEDULED))
        store.upsert(task(id = "cancelled-two", status = AgentTaskStatus.CANCELLED))

        val result = AgentTaskListTool(store).execute(
            ToolCall(id = "call-1", name = "agent_task_list", input = buildJsonObject { }),
            ToolExecutionContext(sessionId = "session-1")
        )

        assertEquals(false, result.isError)
        assertEquals(2, result.content.lines().filter { it.isNotBlank() }.size)
        assertTrue("listing should keep the scheduled row, got: ${result.content}", result.content.contains("scheduled-one"))
    }
}
