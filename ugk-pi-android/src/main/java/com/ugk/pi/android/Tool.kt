package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject

data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject
)

data class ToolResult(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
    val metadata: JsonObject = JsonObject(emptyMap())
)

data class ToolProgress(
    val title: String,
    val detail: String,
    val current: Int? = null,
    val total: Int? = null
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

enum class AgentRunSource {
    USER,
    SCHEDULED_TASK,
    APP_LIFECYCLE,
    SDK_EVENT
}

data class AgentRunInput(
    val content: String,
    val source: AgentRunSource = AgentRunSource.USER,
    val taskId: String? = null,
    val visibleInConversation: Boolean = true
)

data class ToolExecutionContext(
    val sessionId: String,
    val priorMessages: List<AgentMessage> = emptyList(),
    val runSource: AgentRunSource = AgentRunSource.USER,
    val taskId: String? = null,
    val visibleInConversation: Boolean = true,
    val reportProgress: suspend (ToolProgress) -> Unit = {}
)

interface AgentTool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult

    fun definition(): AgentToolDefinition {
        return AgentToolDefinition(
            name = name,
            description = description,
            inputSchema = inputSchema
        )
    }
}
