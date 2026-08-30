package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Append-merge semantics for DemoConversationStore.
 *
 * The store's save() replaces a conversation wholesale, so a foreground
 * Activity holding a stale in-memory snapshot can erase messages a
 * background scheduled run appended in the meantime. appendMessages()
 * (built on the pure appendStoredMessages below) is the fix; these tests
 * pin both the pure merge and the defect evidence for the legacy path.
 */
class DemoConversationAppendTest {

    @Test
    fun appendWithEmptyListKeepsMessagesAndStampsNow() {
        val existing = conversation(
            updatedAt = 1_000L,
            messages = listOf(message("user", "u0"), message("assistant", "a0"))
        )

        val appended = appendStoredMessages(existing, incoming = emptyList(), now = 2_000L)

        assertEquals(listOf("u0", "a0"), appended.messages.map { it.content })
        assertEquals(2_000L, appended.updatedAt)
    }

    @Test
    fun appendBeyondMaxMessagesTruncatesOldestLikeTheSavePath() {
        val existingMessages = (0 until MAX_MESSAGES).map { index ->
            message("user", "existing-$index")
        }
        val existing = conversation(messages = existingMessages)

        val appended = appendStoredMessages(
            existing = existing,
            incoming = listOf(message("user", "incoming-0"), message("assistant", "incoming-1")),
            now = 2_000L
        )

        assertEquals(MAX_MESSAGES, appended.messages.size)
        // The oldest two stored messages are evicted, mirroring save()'s
        // takeLast(MAX_MESSAGES) truncation after the append.
        assertEquals(listOf("existing-0", "existing-1"), existingMessages.take(2).map { it.content })
        assertFalse(appended.messages.any { it.content == "existing-0" })
        assertFalse(appended.messages.any { it.content == "existing-1" })
        assertEquals("incoming-1", appended.messages.last().content)
    }

    @Test
    fun appendAppliesNormalizedTitleUpdate() {
        val existing = conversation(messages = listOf(message("user", "u0")))

        val appended = appendStoredMessages(
            existing = existing,
            incoming = listOf(message("assistant", "a0")),
            now = 2_000L,
            titleUpdate = "  定时   任务总结  "
        )

        assertEquals("定时 任务总结", appended.title)
    }

    @Test
    fun appendWithoutTitleUpdateKeepsExistingTitle() {
        val existing = conversation(messages = listOf(message("user", "u0")))

        val appended = appendStoredMessages(
            existing = existing,
            incoming = listOf(message("assistant", "a0")),
            now = 2_000L
        )

        assertEquals(existing.title, appended.title)
    }

    @Test
    fun appendPropagatesProvidedNowAsUpdatedAt() {
        val existing = conversation(updatedAt = 9_000L, messages = listOf(message("user", "u0")))

        val appended = appendStoredMessages(
            existing = existing,
            incoming = listOf(message("assistant", "a0")),
            now = 42L
        )

        assertEquals(42L, appended.updatedAt)
        assertEquals(0L, appended.createdAt)
    }

    /**
     * Defect evidence (red -> green). Timeline: the durable conversation
     * already contains the background turn [u1(bg), a1(bg)] while the
     * foreground Activity still holds a stale snapshot ending at [u0] and
     * now persists its own new turn.
     *
     * The legacy save() path (readAll().filterNot { id } + staleSnapshot)
     * loses the background messages; the append path keeps every turn.
     */
    @Test
    fun legacyWholeConversationReplaceLosesBackgroundMessagesWhileAppendKeepsThem() {
        val conversationId = "conversation-1"
        val now = 3_000L
        val durable = conversation(
            id = conversationId,
            updatedAt = 1_500L,
            messages = listOf(
                message("user", "u0"),
                message("user", "u1(bg)"),
                message("assistant", "a1(bg)")
            )
        )
        val staleForeground = conversation(
            id = conversationId,
            updatedAt = 1_000L,
            messages = listOf(message("user", "u0"))
        )
        val foregroundTurn = message("user", "u2")

        // Legacy save() semantics: normalize the stale snapshot, then replace
        // the stored conversation with it.
        val legacySaved = normalizeStoredConversation(
            staleForeground.copy(
                updatedAt = now,
                messages = (staleForeground.messages + foregroundTurn).toMutableList()
            )
        )
        val storeAfterLegacySave = listOf(durable)
            .filterNot { it.id == legacySaved.id } + legacySaved
        val legacyConversation = storeAfterLegacySave.first { it.id == conversationId }

        assertEquals(listOf("u0", "u2"), legacyConversation.messages.map { it.content })
        assertFalse(legacyConversation.messages.any { it.content == "a1(bg)" })

        // Fixed path: appendMessages() merges into the durable copy, so the
        // background turn survives the foreground persist.
        val appended = appendStoredMessages(
            existing = durable,
            incoming = listOf(foregroundTurn),
            now = now
        )

        assertEquals(
            listOf("u0", "u1(bg)", "a1(bg)", "u2"),
            appended.messages.map { it.content }
        )
        assertTrue(appended.messages.any { it.content == "a1(bg)" })
        assertEquals(now, appended.updatedAt)
    }

    private fun message(role: String, content: String) =
        DemoStoredMessage(role = role, content = content)

    private fun conversation(
        id: String = "conversation-1",
        updatedAt: Long = 0L,
        messages: List<DemoStoredMessage>
    ) = DemoConversation(
        id = id,
        title = "对话",
        createdAt = 0L,
        updatedAt = updatedAt,
        messages = messages.toMutableList()
    )
}
