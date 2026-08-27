package com.ugk.pi.android

sealed class AgentEvent {
    data class Started(
        val sessionId: String,
        val source: AgentRunSource = AgentRunSource.USER,
        val taskId: String? = null,
        val visibleInConversation: Boolean = true
    ) : AgentEvent()
    data class ModelRequestStarted(
        val iteration: Int,
        val messageCount: Int,
        val toolCount: Int
    ) : AgentEvent()

    data class ModelResponded(
        val content: String,
        val toolCalls: List<ToolCall>,
        val elapsedMillis: Long? = null,
        val stopReason: String? = null,
        val reasoningContent: String? = null
    ) : AgentEvent()

    /** 模型思考链（Reasoning / CoT）流式字符增量 */
    data class ModelThinkingDelta(val delta: String) : AgentEvent()

    /** 模型正文内容流式字符增量 */
    data class ModelContentDelta(val delta: String) : AgentEvent()

    data class ToolStarted(val call: ToolCall) : AgentEvent()
    data class ToolProgress(val call: ToolCall, val progress: com.ugk.pi.android.ToolProgress) : AgentEvent()
    data class ToolFinished(val result: ToolResult) : AgentEvent()
    data class UserMessageAppended(val content: String) : AgentEvent()
    data class Completed(val content: String) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
}
