package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoConversationOrderingTest {

    @Test
    fun newestConversationIsKeptWhenLimitIsReached() {
        val conversations = (0 until 30).map { index ->
            DemoConversation(
                id = "conversation-$index",
                title = "对话 $index",
                createdAt = index.toLong(),
                updatedAt = index.toLong()
            )
        }
        val recent = conversations.last().copy(updatedAt = 1000L)
        val created = DemoConversation(
            id = "conversation-new",
            title = "新对话",
            createdAt = 1001L,
            updatedAt = 1001L
        )

        val kept = keepNewestDemoConversations(conversations.dropLast(1) + recent + created, 30)

        assertEquals(30, kept.size)
        assertTrue(kept.any { it.id == recent.id })
        assertTrue(kept.any { it.id == created.id })
        assertTrue(kept.none { it.id == "conversation-0" })
    }
}
