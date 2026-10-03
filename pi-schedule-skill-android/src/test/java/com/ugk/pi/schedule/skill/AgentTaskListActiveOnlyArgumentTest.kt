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
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What `agent_task_list.activeOnly` accepts, pinned at the landing point.
 *
 * The shared tool-input accessor replaced this module's own copy during the
 * cross-cutting consolidation, and the two are not identical: the deleted copy
 * read the flag with kotlinx `booleanOrNull`, which accepts the case variants
 * "True"/"FALSE"; the shared one is the strict content parse and returns null
 * for them, and `AgentTaskListTool` maps null to `false`. So `{"activeOnly":
 * "True"}` used to narrow the listing to active tasks and now silently widens it
 * back to every row, including cancelled and completed ones. Nothing observed
 * this landing point: on main at c5a13f5, searching every unit and instrumented
 * test source set for the identifier `activeOnly` returned no test (the shared
 * accessor's own case-variant behaviour is pinned in `ToolJsonTest`, but nothing
 * followed it through to a tool).
 *
 * The rows are pinned rather than "fixed" on purpose. The root-cause shape for
 * this family is a three-state optional read (absent and null default, a
 * declared value of the wrong type is refused by name); that mechanism is what
 * round 11's open PR #13 introduces as `OptionalArgumentReading`, and it does not
 * reach `AgentTaskListTool`. Inventing a second one-off reader here would leave
 * the module with two competing rules for the same fact, so the boundary is
 * recorded instead, and the widening is registered as a residual risk.
 */
class AgentTaskListActiveOnlyArgumentTest {
    @Test
    fun activeOnlyArgumentDomainIsPinned() = runBlocking {
        val failures = mutableListOf<String>()
        val cases = listOf(
            ArgumentCase("key absent", null, expectedRows = 2),
            ArgumentCase("boolean true", JsonPrimitive(true), expectedRows = 1),
            ArgumentCase("boolean false", JsonPrimitive(false), expectedRows = 2),
            ArgumentCase("quoted lowercase true", JsonPrimitive("true"), expectedRows = 1),
            ArgumentCase("quoted lowercase false", JsonPrimitive("false"), expectedRows = 2),
            ArgumentCase(
                "case variant True",
                JsonPrimitive("True"),
                expectedRows = 2,
                note = "declared but unusable: reads as absent, so the listing widens"
            ),
            ArgumentCase("case variant FALSE", JsonPrimitive("FALSE"), expectedRows = 2),
            ArgumentCase("explicit null", JsonNull, expectedRows = 2),
            ArgumentCase("a number", JsonPrimitive(1), expectedRows = 2),
            ArgumentCase("an array", JsonArray(emptyList()), throwsOnNonScalar = true)
        )

        cases.forEach { case ->
            val input = if (case.value == null) {
                buildJsonObject { }
            } else {
                JsonObject(mapOf("activeOnly" to case.value))
            }
            try {
                val rows = listRows(input)
                if (case.throwsOnNonScalar) {
                    failures += "${case.name}: expected the non-scalar value to be refused loudly, " +
                        "got $rows rows"
                } else if (rows != case.expectedRows) {
                    failures += "${case.name}: expected ${case.expectedRows} rows, got $rows" +
                        (case.note?.let { " ($it)" } ?: "")
                }
            } catch (error: IllegalArgumentException) {
                if (!case.throwsOnNonScalar) {
                    failures += "${case.name}: refused with ${error.message} " +
                        "but the row count was ${case.expectedRows}"
                }
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    /** One scheduled row and one cancelled row: `activeOnly` is the only thing under test. */
    private suspend fun listRows(input: JsonObject): Int {
        val store = InMemoryAgentTaskStore()
        store.upsert(task(id = "scheduled-one", status = AgentTaskStatus.SCHEDULED))
        store.upsert(task(id = "cancelled-two", status = AgentTaskStatus.CANCELLED))
        val result = AgentTaskListTool(store).execute(
            ToolCall(id = "call-1", name = "agent_task_list", input = input),
            ToolExecutionContext(sessionId = "session-1")
        )
        assertEquals(
            "${result.content}: the list tool returned an error instead of rows",
            false,
            result.isError
        )
        return result.content.lines().filter { it.isNotBlank() }.size
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

    private class ArgumentCase(
        val name: String,
        val value: JsonElement?,
        val expectedRows: Int = 0,
        val throwsOnNonScalar: Boolean = false,
        val note: String? = null
    )
}
