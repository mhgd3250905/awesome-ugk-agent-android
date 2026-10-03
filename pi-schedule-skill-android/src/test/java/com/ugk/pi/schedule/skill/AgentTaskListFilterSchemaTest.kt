package com.ugk.pi.schedule.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared filter domain and the enforced one are the same list.
 *
 * `agent_task_list`'s schema used to advertise `status` as "Optional
 * AgentTaskStatus name" with no enum, while the tool required an exact
 * `AgentTaskStatus` name - and until this round a name outside that set was not
 * refused at all, it just removed the filter. A gateway or host that validates
 * against the schema would therefore accept `{"status":"scheduled"}` and the tool
 * would answer with every row. The enum is written as literals in the schema so
 * this comparison has an independent oracle: deriving it from `AgentTaskStatus`
 * would make the schema its own test and it could never fail.
 */
class AgentTaskListFilterSchemaTest {

    private val declaredEnum: List<String> = run {
        val properties = (AgentTaskListTool(InMemoryAgentTaskStore()).inputSchema["properties"] as? JsonObject)
            ?: error("agent_task_list declares no properties object")
        val status = (properties["status"] as? JsonObject)
            ?: error("agent_task_list declares no status property")
        val enum = (status["enum"] as? JsonArray)
            ?: error("the status property declares no enum, so nothing checks the accepted names")
        enum.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    @Test
    fun schemaEnumListsExactlyTheStatusesTheCodeAccepts() {
        val enforced = AgentTaskStatus.entries.map { it.name }
        // Set comparison, not index comparison: the schema order is presentation,
        // the code's order is declaration order, and neither is a behavior.
        assertEquals(enforced.toSet(), declaredEnum.toSet())
        assertEquals(
            "the enum must list each name once, or a duplicated entry hides a missing one",
            declaredEnum.size,
            declaredEnum.toSet().size
        )
    }

    @Test
    fun everySchemaEnumNameIsAcceptedByTheTool() {
        val failures = declaredEnum.mapNotNull { name ->
            val result = runBlocking { listWithStatus(name) }
            if (result.isError) "schema advertises $name but the tool refused it: ${result.content}" else null
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun aNameOutsideTheSchemaEnumIsRefused() {
        val control = "SCHEDUELD"
        if (control in declaredEnum) {
            throw AssertionError("$control must not be an advertised status name, so the case proves nothing")
        }
        val result = runBlocking { listWithStatus(control) }
        assertEquals("an unadvertised status name must not silently drop the filter", true, result.isError)
        val message = (result.metadata?.get("message") as? JsonPrimitive)?.contentOrNull ?: result.content
        assertTrue("the refusal must name the argument it refused: $message", message.contains("status"))
    }

    private suspend fun listWithStatus(status: String): ToolResult {
        val store = InMemoryAgentTaskStore()
        store.upsert(
            AgentTask(
                id = "scheduled-one",
                sessionId = "session-1",
                title = "scheduled-one",
                schedule = AgentTaskSchedule.OneShot(runAtMillis = 2_000L),
                action = AgentTaskAction.NotifyUser(message = "reminder"),
                status = AgentTaskStatus.SCHEDULED,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
                nextRunAtMillis = 2_000L
            )
        )
        return AgentTaskListTool(store).execute(
            ToolCall(
                id = "call-1",
                name = "agent_task_list",
                input = buildJsonObject { put("status", JsonPrimitive(status)) }
            ),
            ToolExecutionContext(sessionId = "session-1")
        )
    }
}
