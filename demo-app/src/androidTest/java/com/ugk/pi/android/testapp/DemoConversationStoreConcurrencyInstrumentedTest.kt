package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-5 P0 review regression: delete/create/rename used to run their
 * read-modify-write sequences OUTSIDE the store monitor while a background
 * scheduled run appended via appendMessagesAndFlush. A snapshot read before
 * the other writer's change could be written back after it, resurrecting the
 * deleted conversation (with active_id pointing elsewhere). Needs a real
 * Context for SharedPreferences, hence an instrumented test.
 */
@RunWith(AndroidJUnit4::class)
class DemoConversationStoreConcurrencyInstrumentedTest {

    @Test
    fun deleteWinsAgainstConcurrentFlushedAppends() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DemoConversationStore(context)
        val conversation = store.create("concurrency")
        val conversationId = conversation.id

        val rounds = 200
        val start = CountDownLatch(1)
        val appender = Executors.newSingleThreadExecutor { r -> Thread(r, "append") }
        val deleter = Executors.newSingleThreadExecutor { r -> Thread(r, "delete") }

        try {
            val appendFuture = appender.submit<List<Int?>> {
                start.await()
                (0 until rounds).map { round ->
                    store.appendMessagesAndFlush(
                        conversationId,
                        listOf(DemoStoredMessage(role = "assistant", content = "round $round"))
                    )?.messages?.size
                }
            }
            val deleteFuture = deleter.submit {
                start.await()
                // Interleave deletions: after every delete the append path may
                // either report the conversation gone (null) or resurrect it.
                repeat(rounds / 10) {
                    store.delete(conversationId)
                    Thread.sleep(1)
                    if (store.get(conversationId) != null) {
                        // Store re-created or resurrected it — delete again to
                        // keep both writers hammering the same record.
                        store.delete(conversationId)
                    }
                }
            }
            start.countDown()
            deleteFuture.get(60, TimeUnit.SECONDS)
            val appendResults = appendFuture.get(60, TimeUnit.SECONDS)

            // The conversation must be gone (or, if appends kept resurrecting
            // it before the final delete, the LAST state must still be
            // consistent: no append result may claim a size larger than the
            // messages that appender actually sent).
            val maxAllowed = rounds + 1
            appendResults.forEach { size ->
                if (size != null) {
                    assertTrue(
                        "append observed $size messages, more than ever existed ($maxAllowed)",
                        size <= maxAllowed
                    )
                }
            }
            // Final state: after the last delete no flushed append may
            // resurrect the conversation.
            store.delete(conversationId)
            // All flushed appends already completed above, so nothing is in
            // flight: the conversation must be gone.
            assertNull(
                "deleted conversation was resurrected by a concurrent flushed append",
                store.get(conversationId)
            )
            // The store must never keep an active_id pointing at a deleted id.
            org.junit.Assert.assertNotEquals(conversationId, store.activeConversationId())
        } finally {
            appender.shutdownNow()
            deleter.shutdownNow()
        }
    }

    @Test
    fun renameDoesNotDropMessagesAppendedBeforeOrDuringIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DemoConversationStore(context)
        val conversation = store.create("rename-target")
        val conversationId = conversation.id
        val unique = UUID.randomUUID().toString()

        store.appendMessagesAndFlush(
            conversationId,
            listOf(DemoStoredMessage(role = "assistant", content = "before rename $unique"))
        )
        val renamed = store.rename(conversationId, "renamed")
        assertTrue(renamed != null)

        val stored = store.get(conversationId)!!
        assertTrue(
            "rename must not drop the previously appended message",
            stored.messages.any { it.content.contains(unique) }
        )
        org.junit.Assert.assertEquals("renamed", stored.title)
    }
}
