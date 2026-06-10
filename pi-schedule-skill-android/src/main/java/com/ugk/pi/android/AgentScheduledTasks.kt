package com.ugk.pi.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class AgentTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val schedule: AgentTaskSchedule,
    val action: AgentTaskAction,
    val status: AgentTaskStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val nextRunAtMillis: Long?,
    val lastRunAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
enum class AgentTaskStatus {
    SCHEDULED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    EXPIRED
}

@Serializable
sealed class AgentTaskSchedule {
    abstract val startAtMillis: Long

    @Serializable
    @SerialName("ONE_SHOT")
    data class OneShot(
        val runAtMillis: Long
    ) : AgentTaskSchedule() {
        override val startAtMillis: Long = runAtMillis
    }

    @Serializable
    @SerialName("REPEATING_UNTIL")
    data class RepeatingUntil(
        override val startAtMillis: Long,
        val intervalMillis: Long,
        val endAtMillis: Long
    ) : AgentTaskSchedule()
}

@Serializable
sealed class AgentTaskAction {
    @Serializable
    @SerialName("NOTIFY_USER")
    data class NotifyUser(
        val message: String
    ) : AgentTaskAction()

    @Serializable
    @SerialName("RUN_AGENT_PROMPT")
    data class RunAgentPrompt(
        val prompt: String,
        val notifyPolicy: AgentTaskNotifyPolicy = AgentTaskNotifyPolicy.ALWAYS_NOTIFY
    ) : AgentTaskAction()
}

@Serializable
enum class AgentTaskNotifyPolicy {
    ALWAYS_NOTIFY
}

interface AgentTaskClock {
    fun nowMillis(): Long
}

object SystemAgentTaskClock : AgentTaskClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class FixedClock(private val nowMillis: Long) : AgentTaskClock {
    override fun nowMillis(): Long = nowMillis
}

interface AgentTaskIdGenerator {
    fun newTaskId(): String
}

class UuidAgentTaskIdGenerator : AgentTaskIdGenerator {
    override fun newTaskId(): String = "task_${java.util.UUID.randomUUID()}"
}

class SequentialTaskIdGenerator(
    private val prefix: String = "task"
) : AgentTaskIdGenerator {
    private var next = 1

    override fun newTaskId(): String {
        return "${prefix}_${next++}"
    }
}

interface AgentTaskStore {
    suspend fun upsert(task: AgentTask)
    suspend fun get(taskId: String): AgentTask?
    suspend fun list(): List<AgentTask>
}

class InMemoryAgentTaskStore : AgentTaskStore {
    private val tasks = linkedMapOf<String, AgentTask>()

    override suspend fun upsert(task: AgentTask) {
        tasks[task.id] = task
    }

    override suspend fun get(taskId: String): AgentTask? = tasks[taskId]

    override suspend fun list(): List<AgentTask> = tasks.values.toList()
}

interface AgentTaskScheduler {
    suspend fun schedule(task: AgentTask)
    suspend fun cancel(taskId: String)
}

object NoopAgentTaskScheduler : AgentTaskScheduler {
    override suspend fun schedule(task: AgentTask) {
    }

    override suspend fun cancel(taskId: String) {
    }
}

fun agentScheduledTaskTools(
    store: AgentTaskStore,
    scheduler: AgentTaskScheduler,
    clock: AgentTaskClock = SystemAgentTaskClock,
    idGenerator: AgentTaskIdGenerator = UuidAgentTaskIdGenerator()
): List<AgentTool> {
    return listOf(
        AgentTaskCreateTool(store, scheduler, clock, idGenerator),
        AgentTaskListTool(store),
        AgentTaskGetTool(store),
        AgentTaskUpdateTool(store, scheduler, clock),
        AgentTaskCancelTool(store, scheduler, clock)
    )
}

class ScheduleTaskAgentPlugin(
    private val store: AgentTaskStore,
    private val scheduler: AgentTaskScheduler,
    private val clock: AgentTaskClock = SystemAgentTaskClock,
    private val idGenerator: AgentTaskIdGenerator = UuidAgentTaskIdGenerator()
) : AgentCapabilityPlugin {
    override val id: String = "agent-scheduled-tasks"

    override fun tools(): List<AgentTool> {
        return agentScheduledTaskTools(
            store = store,
            scheduler = scheduler,
            clock = clock,
            idGenerator = idGenerator
        )
    }

    override fun skills(): List<AndroidSkill> = listOf(agentScheduledTasksSkill())
}

fun agentScheduledTasksSkill(): AndroidSkill {
    return AndroidSkill(
        id = "agent-scheduled-tasks",
        description = "Use when the user asks for future execution, delayed reminders, repeated reminders, periodic checks, or continuing work after this chat turn.",
        triggers = listOf(
            "remind",
            "reminder",
            "schedule",
            "later",
            "every",
            "monitor",
            "watch",
            "check every",
            "提醒",
            "定时",
            "稍后",
            "每",
            "关注",
            "监测",
            "观察"
        ),
        instructions = """
            Use scheduled task tools when the user asks for future execution, delayed reminders, repeated reminders, periodic checks, watching something for a time window, or continuing work after this chat turn.

            Do not simulate waiting in chat.
            Do not use long delays inside the agent loop.
            Do not promise ongoing monitoring unless agent_task_create succeeds.

            Use ONE_SHOT for one future execution.
            Use REPEATING_UNTIL for repeated execution over a time window.
            Use NOTIFY_USER for pure reminders.
            Use RUN_AGENT_PROMPT for checks that require tool use or reasoning at trigger time.
            Use list/get/update/cancel for follow-up user commands about existing tasks.
        """.trimIndent(),
        methods = listOf(
            AndroidSkillMethod(
                toolName = "agent_task_create",
                purpose = "Declares a one-shot or repeating scheduled agent task.",
                whenToUse = "Use when the user asks for future execution, delayed reminders, repeated reminders, periodic checks, or monitoring over a time window.",
                resultSemantics = "Returns taskId, status, summary, and nextRunAtMillis. The host scheduler owns actual Android execution."
            ),
            AndroidSkillMethod(
                toolName = "agent_task_list",
                purpose = "Lists scheduled task records.",
                whenToUse = "Use when the user asks what tasks exist, what is being watched, or when a later reference is ambiguous.",
                resultSemantics = "Returns task summaries and statuses."
            ),
            AndroidSkillMethod(
                toolName = "agent_task_get",
                purpose = "Reads one scheduled task by id.",
                whenToUse = "Use when the user asks about a specific task or when task details are needed before update/cancel.",
                resultSemantics = "Returns full schedule/action/status metadata for the task."
            ),
            AndroidSkillMethod(
                toolName = "agent_task_update",
                purpose = "Updates title, schedule, or action for a scheduled or running task.",
                whenToUse = "Use when the user asks to change timing, wording, or behavior of an existing task.",
                resultSemantics = "Rejected for completed, cancelled, failed, or expired tasks."
            ),
            AndroidSkillMethod(
                toolName = "agent_task_cancel",
                purpose = "Cancels future executions while preserving the task record.",
                whenToUse = "Use when the user asks to stop or cancel an existing scheduled task.",
                resultSemantics = "Stops future scheduling and returns the cancelled task state."
            )
        )
    )
}

class AgentTaskCreateTool(
    private val store: AgentTaskStore,
    private val scheduler: AgentTaskScheduler,
    private val clock: AgentTaskClock = SystemAgentTaskClock,
    private val idGenerator: AgentTaskIdGenerator = UuidAgentTaskIdGenerator(),
    override val name: String = "agent_task_create"
) : AgentTool {
    override val description: String = "Creates a one-shot or repeating scheduled agent task."
    override val inputSchema: JsonObject = taskInputSchema(requireTaskId = false)

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val parsed = parseTaskCreate(call, context.sessionId, clock, idGenerator)
        if (parsed is TaskParseResult.Error) return errorResult(call, name, parsed.code, parsed.message)
        val task = (parsed as TaskParseResult.Success).task
        store.upsert(task)
        scheduler.schedule(task)
        return taskResult(call, name, task, "Created scheduled task ${task.id}.")
    }
}

class AgentTaskListTool(
    private val store: AgentTaskStore,
    override val name: String = "agent_task_list"
) : AgentTool {
    override val description: String = "Lists scheduled agent tasks."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("status") {
                put("type", "string")
                put("description", "Optional AgentTaskStatus name.")
            }
            putJsonObject("activeOnly") {
                put("type", "boolean")
                put("description", "When true, returns only SCHEDULED and RUNNING tasks.")
            }
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val statusFilter = call.input.string("status")?.let { value ->
            runCatching { AgentTaskStatus.valueOf(value) }.getOrNull()
        }
        val activeOnly = call.input.boolean("activeOnly") ?: false
        val tasks = store.list()
            .filter { statusFilter == null || it.status == statusFilter }
            .filter { !activeOnly || it.status == AgentTaskStatus.SCHEDULED || it.status == AgentTaskStatus.RUNNING }
            .sortedBy { it.nextRunAtMillis ?: Long.MAX_VALUE }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = if (tasks.isEmpty()) {
                "No scheduled tasks found."
            } else {
                tasks.joinToString("\n") { "${it.id}: ${it.title} (${it.status})" }
            },
            metadata = buildJsonObject {
                put("ok", true)
                put("count", tasks.size)
                putJsonArray("tasks") {
                    tasks.forEach { add(taskJson(it)) }
                }
            }
        )
    }
}

class AgentTaskGetTool(
    private val store: AgentTaskStore,
    override val name: String = "agent_task_get"
) : AgentTool {
    override val description: String = "Gets one scheduled agent task by id."
    override val inputSchema: JsonObject = taskIdSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val taskId = call.input.string("taskId") ?: return errorResult(call, name, "MISSING_TASK_ID", "taskId is required.")
        val task = store.get(taskId) ?: return errorResult(call, name, "TASK_NOT_FOUND", "Task not found: $taskId")
        return taskResult(call, name, task, "Found scheduled task ${task.id}.")
    }
}

class AgentTaskUpdateTool(
    private val store: AgentTaskStore,
    private val scheduler: AgentTaskScheduler,
    private val clock: AgentTaskClock = SystemAgentTaskClock,
    override val name: String = "agent_task_update"
) : AgentTool {
    override val description: String = "Updates title, schedule, or action for an active scheduled agent task."
    override val inputSchema: JsonObject = taskInputSchema(requireTaskId = true)

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val taskId = call.input.string("taskId") ?: return errorResult(call, name, "MISSING_TASK_ID", "taskId is required.")
        val existing = store.get(taskId) ?: return errorResult(call, name, "TASK_NOT_FOUND", "Task not found: $taskId")
        if (existing.status !in setOf(AgentTaskStatus.SCHEDULED, AgentTaskStatus.RUNNING)) {
            return errorResult(call, name, "TASK_ENDED", "Ended tasks cannot be updated.")
        }

        val now = clock.nowMillis()
        val schedule = if (call.input["schedule"] != null) {
            when (val parsed = parseSchedule(call.input["schedule"]?.jsonObject, now)) {
                is ScheduleParseResult.Error -> return errorResult(call, name, parsed.code, parsed.message)
                is ScheduleParseResult.Success -> parsed.schedule
            }
        } else {
            existing.schedule
        }
        val action = if (call.input["action"] != null) {
            when (val parsed = parseAction(call.input["action"]?.jsonObject)) {
                is ActionParseResult.Error -> return errorResult(call, name, parsed.code, parsed.message)
                is ActionParseResult.Success -> parsed.action
            }
        } else {
            existing.action
        }
        val updated = existing.copy(
            title = call.input.string("title") ?: existing.title,
            schedule = schedule,
            action = action,
            updatedAtMillis = now,
            nextRunAtMillis = schedule.nextRunAtMillis(now)
        )
        store.upsert(updated)
        scheduler.schedule(updated)
        return taskResult(call, name, updated, "Updated scheduled task ${updated.id}.")
    }
}

class AgentTaskCancelTool(
    private val store: AgentTaskStore,
    private val scheduler: AgentTaskScheduler,
    private val clock: AgentTaskClock = SystemAgentTaskClock,
    override val name: String = "agent_task_cancel"
) : AgentTool {
    override val description: String = "Cancels a scheduled agent task while preserving its record."
    override val inputSchema: JsonObject = taskIdSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val taskId = call.input.string("taskId") ?: return errorResult(call, name, "MISSING_TASK_ID", "taskId is required.")
        val existing = store.get(taskId) ?: return errorResult(call, name, "TASK_NOT_FOUND", "Task not found: $taskId")
        if (existing.status in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED, AgentTaskStatus.FAILED, AgentTaskStatus.EXPIRED)) {
            return errorResult(call, name, "TASK_ENDED", "Ended tasks cannot be cancelled.")
        }
        val cancelled = existing.copy(
            status = AgentTaskStatus.CANCELLED,
            updatedAtMillis = clock.nowMillis(),
            nextRunAtMillis = null
        )
        store.upsert(cancelled)
        scheduler.cancel(taskId)
        return taskResult(call, name, cancelled, "Cancelled scheduled task ${cancelled.id}.")
    }
}

private sealed class TaskParseResult {
    data class Success(val task: AgentTask) : TaskParseResult()
    data class Error(val code: String, val message: String) : TaskParseResult()
}

private sealed class ScheduleParseResult {
    data class Success(val schedule: AgentTaskSchedule) : ScheduleParseResult()
    data class Error(val code: String, val message: String) : ScheduleParseResult()
}

private sealed class ActionParseResult {
    data class Success(val action: AgentTaskAction) : ActionParseResult()
    data class Error(val code: String, val message: String) : ActionParseResult()
}

private fun parseTaskCreate(
    call: ToolCall,
    sessionId: String,
    clock: AgentTaskClock,
    idGenerator: AgentTaskIdGenerator
): TaskParseResult {
    val title = call.input.string("title")?.takeIf { it.isNotBlank() }
        ?: return TaskParseResult.Error("MISSING_TITLE", "title is required.")
    val now = clock.nowMillis()
    val schedule = when (val parsed = parseSchedule(call.input["schedule"]?.jsonObject, now)) {
        is ScheduleParseResult.Error -> return TaskParseResult.Error(parsed.code, parsed.message)
        is ScheduleParseResult.Success -> parsed.schedule
    }
    val action = when (val parsed = parseAction(call.input["action"]?.jsonObject)) {
        is ActionParseResult.Error -> return TaskParseResult.Error(parsed.code, parsed.message)
        is ActionParseResult.Success -> parsed.action
    }
    val task = AgentTask(
        id = idGenerator.newTaskId(),
        sessionId = sessionId,
        title = title,
        schedule = schedule,
        action = action,
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = now,
        updatedAtMillis = now,
        nextRunAtMillis = schedule.nextRunAtMillis(now)
    )
    return TaskParseResult.Success(task)
}

private fun parseSchedule(input: JsonObject?, nowMillis: Long): ScheduleParseResult {
    if (input == null) return ScheduleParseResult.Error("MISSING_SCHEDULE", "schedule is required.")
    return when (input.string("type")) {
        "ONE_SHOT" -> {
            val startAfterSeconds = input.long("startAfterSeconds") ?: return ScheduleParseResult.Error("INVALID_SCHEDULE", "startAfterSeconds is required.")
            if (startAfterSeconds < 0) return ScheduleParseResult.Error("INVALID_SCHEDULE", "startAfterSeconds must be >= 0.")
            ScheduleParseResult.Success(AgentTaskSchedule.OneShot(nowMillis + startAfterSeconds * 1_000L))
        }
        "REPEATING_UNTIL" -> {
            val startAfterSeconds = input.long("startAfterSeconds") ?: 0L
            val intervalSeconds = input.long("intervalSeconds") ?: return ScheduleParseResult.Error("INVALID_SCHEDULE", "intervalSeconds is required.")
            val endAfterSeconds = input.long("endAfterSeconds") ?: return ScheduleParseResult.Error("INVALID_SCHEDULE", "endAfterSeconds is required.")
            if (startAfterSeconds < 0 || intervalSeconds <= 0 || endAfterSeconds <= startAfterSeconds) {
                return ScheduleParseResult.Error("INVALID_SCHEDULE", "Repeating tasks require startAfterSeconds >= 0, intervalSeconds > 0, and endAfterSeconds > startAfterSeconds.")
            }
            ScheduleParseResult.Success(
                AgentTaskSchedule.RepeatingUntil(
                    startAtMillis = nowMillis + startAfterSeconds * 1_000L,
                    intervalMillis = intervalSeconds * 1_000L,
                    endAtMillis = nowMillis + endAfterSeconds * 1_000L
                )
            )
        }
        else -> ScheduleParseResult.Error("INVALID_SCHEDULE", "schedule.type must be ONE_SHOT or REPEATING_UNTIL.")
    }
}

private fun parseAction(input: JsonObject?): ActionParseResult {
    if (input == null) return ActionParseResult.Error("MISSING_ACTION", "action is required.")
    return when (input.string("type")) {
        "NOTIFY_USER" -> {
            val message = input.string("message")?.takeIf { it.isNotBlank() }
                ?: return ActionParseResult.Error("INVALID_ACTION", "NOTIFY_USER requires message.")
            ActionParseResult.Success(AgentTaskAction.NotifyUser(message))
        }
        "RUN_AGENT_PROMPT" -> {
            val prompt = input.string("prompt")?.takeIf { it.isNotBlank() }
                ?: return ActionParseResult.Error("INVALID_ACTION", "RUN_AGENT_PROMPT requires prompt.")
            val notifyPolicy = input.string("notifyPolicy")
                ?.let { runCatching { AgentTaskNotifyPolicy.valueOf(it) }.getOrNull() }
                ?: AgentTaskNotifyPolicy.ALWAYS_NOTIFY
            ActionParseResult.Success(AgentTaskAction.RunAgentPrompt(prompt, notifyPolicy))
        }
        else -> ActionParseResult.Error("INVALID_ACTION", "action.type must be NOTIFY_USER or RUN_AGENT_PROMPT.")
    }
}

fun AgentTaskSchedule.nextRunAtMillis(nowMillis: Long): Long? {
    return when (this) {
        is AgentTaskSchedule.OneShot -> runAtMillis.takeIf { it >= nowMillis }
        is AgentTaskSchedule.RepeatingUntil -> {
            if (nowMillis > endAtMillis) return null
            if (nowMillis <= startAtMillis) return startAtMillis
            val elapsed = nowMillis - startAtMillis
            val intervals = (elapsed + intervalMillis - 1) / intervalMillis
            (startAtMillis + intervals * intervalMillis).takeIf { it <= endAtMillis }
        }
    }
}

fun taskJson(task: AgentTask): JsonObject = buildJsonObject {
    put("id", task.id)
    put("sessionId", task.sessionId)
    put("title", task.title)
    put("status", task.status.name)
    put("createdAtMillis", task.createdAtMillis)
    put("updatedAtMillis", task.updatedAtMillis)
    task.nextRunAtMillis?.let { put("nextRunAtMillis", it) }
    task.lastRunAtMillis?.let { put("lastRunAtMillis", it) }
    task.completedAtMillis?.let { put("completedAtMillis", it) }
    put("schedule", scheduleJson(task.schedule))
    put("action", actionJson(task.action))
}

private fun taskResult(call: ToolCall, toolName: String, task: AgentTask, content: String): ToolResult {
    return ToolResult(
        toolCallId = call.id,
        name = toolName,
        content = content,
        metadata = buildJsonObject {
            put("ok", true)
            put("taskId", task.id)
            put("status", task.status.name)
            task.nextRunAtMillis?.let { put("nextRunAtMillis", it) }
            put("task", taskJson(task))
        }
    )
}

private fun errorResult(call: ToolCall, toolName: String, code: String, message: String): ToolResult {
    return ToolResult(
        toolCallId = call.id,
        name = toolName,
        content = message,
        isError = true,
        metadata = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", message)
        }
    )
}

private fun scheduleJson(schedule: AgentTaskSchedule): JsonObject = buildJsonObject {
    when (schedule) {
        is AgentTaskSchedule.OneShot -> {
            put("type", "ONE_SHOT")
            put("runAtMillis", schedule.runAtMillis)
        }
        is AgentTaskSchedule.RepeatingUntil -> {
            put("type", "REPEATING_UNTIL")
            put("startAtMillis", schedule.startAtMillis)
            put("intervalMillis", schedule.intervalMillis)
            put("endAtMillis", schedule.endAtMillis)
        }
    }
}

private fun actionJson(action: AgentTaskAction): JsonObject = buildJsonObject {
    when (action) {
        is AgentTaskAction.NotifyUser -> {
            put("type", "NOTIFY_USER")
            put("message", action.message)
        }
        is AgentTaskAction.RunAgentPrompt -> {
            put("type", "RUN_AGENT_PROMPT")
            put("prompt", action.prompt)
            put("notifyPolicy", action.notifyPolicy.name)
        }
    }
}

private fun taskInputSchema(requireTaskId: Boolean): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        if (requireTaskId) {
            putJsonObject("taskId") {
                put("type", "string")
                put("description", "Task id returned by agent_task_create.")
            }
        }
        putJsonObject("title") {
            put("type", "string")
        }
        putJsonObject("schedule") {
            put("type", "object")
        }
        putJsonObject("action") {
            put("type", "object")
        }
    }
}

private fun taskIdSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("taskId") {
            put("type", "string")
            put("description", "Task id returned by agent_task_create.")
        }
    }
    putJsonArray("required") {
        add(JsonPrimitive("taskId"))
    }
}

private fun JsonObject.string(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.long(name: String): Long? {
    return this[name]?.jsonPrimitive?.longOrNull ?: this[name]?.jsonPrimitive?.intOrNull?.toLong()
}

private fun JsonObject.boolean(name: String): Boolean? {
    return this[name]?.jsonPrimitive?.booleanOrNull
}
