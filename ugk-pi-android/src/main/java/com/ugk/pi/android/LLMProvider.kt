package com.ugk.pi.android

data class ModelRequest(
    val sessionId: String,
    val messages: List<AgentMessage>,
    val tools: List<AgentToolDefinition>
)

data class ModelResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val stopReason: String? = null,
    val reasoningContent: String? = null
)

interface LLMProvider {
    suspend fun generate(request: ModelRequest): ModelResponse
}
