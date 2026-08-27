package com.ugk.pi.android

sealed class AgentMessage {
    data class System(val content: String) : AgentMessage()
    class User(
        val content: String,
        val timeContext: AgentTimeContext? = null,
        val images: List<AgentImageContent> = emptyList()
    ) : AgentMessage() {
        override fun equals(other: Any?): Boolean {
            return other is User && content == other.content && images == other.images
        }

        override fun hashCode(): Int = 31 * content.hashCode() + images.hashCode()

        override fun toString(): String {
            return if (images.isEmpty()) {
                "User(content=$content)"
            } else {
                "User(content=$content, images=${images.size})"
            }
        }
    }
    data class Assistant(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null
    ) : AgentMessage()

    data class Tool(val result: ToolResult) : AgentMessage()
}
