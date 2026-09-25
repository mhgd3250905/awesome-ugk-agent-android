package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoAgentSessionFactoryTest {
    @Test
    fun scheduledRunsCanRebuildTheSameConversationContext() {
        val conversation = DemoConversation(
            id = "conversation-1",
            title = "测试",
            createdAt = 1L,
            updatedAt = 2L,
            messages = mutableListOf(
                DemoStoredMessage("user", "请记住这个条件"),
                DemoStoredMessage("assistant", "好的")
            )
        )

        val session = createDemoAgentSession(conversation)

        assertEquals(conversation.id, session.id)
        assertEquals(3, session.messages.size)
        assertEquals("请记住这个条件", (session.messages[1] as AgentMessage.User).content)
        assertEquals("好的", (session.messages[2] as AgentMessage.Assistant).content)
    }

    @Test
    fun boundedHistoryWithLeadingAssistantStillBuildsValidSession() {
        val conversation = DemoConversation(
            id = "bounded-conversation",
            title = "长对话",
            createdAt = 1L,
            updatedAt = 2L,
            messages = buildList {
                repeat(50) { turn ->
                    add(DemoStoredMessage("user", "问题 $turn"))
                    add(DemoStoredMessage("assistant", "回答 $turn"))
                }
                add(DemoStoredMessage("user", "最新问题"))
            }.toMutableList()
        )
        val stored = normalizeStoredConversation(conversation)
        assertEquals(MAX_MESSAGES, stored.messages.size)
        assertEquals("assistant", stored.messages.first().role)

        val session = createDemoAgentSession(stored)

        assertEquals("回答 0", stored.messages.first().content)
        assertEquals("问题 1", (session.messages[1] as AgentMessage.User).content)
        assertEquals("最新问题", (session.messages.last() as AgentMessage.User).content)
    }

    @Test
    fun assistantOnlyHistoryRemainsVisibleButCreatesEmptyRuntimeTranscript() {
        val conversation = DemoConversation(
            id = "assistant-only",
            title = "通知",
            createdAt = 1L,
            updatedAt = 2L,
            messages = mutableListOf(DemoStoredMessage("assistant", "原有提示"))
        )

        val session = createDemoAgentSession(conversation)

        assertEquals(1, conversation.messages.size)
        assertEquals(1, session.messages.size)
        assertEquals(AgentMessage.System::class.java, session.messages.single()::class.java)
    }
}
