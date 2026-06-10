package com.ugk.pi.android

sealed class AgentMessage {
    data class System(val content: String) : AgentMessage()
    class User(
        val content: String,
        val timeContext: AgentTimeContext? = null
    ) : AgentMessage() {
        override fun equals(other: Any?): Boolean {
            return other is User && content == other.content
        }

        override fun hashCode(): Int = content.hashCode()

        override fun toString(): String {
            return "User(content=$content)"
        }
    }
    data class Assistant(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null
    ) : AgentMessage()

    data class Tool(val result: ToolResult) : AgentMessage()
}
