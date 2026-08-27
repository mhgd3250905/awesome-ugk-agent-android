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
}
