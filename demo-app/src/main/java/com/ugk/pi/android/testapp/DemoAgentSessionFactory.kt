package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession

private const val DEMO_AGENT_SYSTEM_PROMPT =
    "你是一个有用的 AI 助手。直接回答用户问题，简洁明了；如果需要调用工具，先说明下一步并在工具完成后给出清晰结果。"

/** Rebuilds a Runtime session from the durable demo conversation. */
internal fun createDemoAgentSession(conversation: DemoConversation): AgentSession {
    val messages = mutableListOf<AgentMessage>(
        AgentMessage.System(DEMO_AGENT_SYSTEM_PROMPT)
    )
    conversation.messages.forEach { stored ->
        when (stored.role) {
            "user" -> messages += AgentMessage.User(stored.content)
            "assistant" -> messages += AgentMessage.Assistant(stored.content)
        }
    }
    return AgentSession(conversation.id, messages)
}
