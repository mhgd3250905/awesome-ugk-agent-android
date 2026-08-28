package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentSessionTranscriptOwnershipTest {

    @Test
    fun `initial message list is copied into the session`() {
        val initialMessages = mutableListOf<AgentMessage>(AgentMessage.User("hello"))
        val session = AgentSession("owned-transcript", initialMessages)

        initialMessages += AgentMessage.Assistant("outside mutation")

        assertEquals(listOf(AgentMessage.User("hello")), session.messages)
    }

    @Test
    fun `messages is a read-only snapshot rather than a mutable list`() {
        val session = AgentSession(
            "read-only-transcript",
            mutableListOf(AgentMessage.User("hello"))
        )
        val exposed = session.messages

        val failure = runCatching {
            java.util.List::class.java
                .getMethod("add", Any::class.java)
                .invoke(exposed, AgentMessage.Assistant("must fail"))
        }.exceptionOrNull()

        assertTrue(
            ((failure as? InvocationTargetException)?.cause ?: failure) is UnsupportedOperationException
        )
        assertEquals(listOf(AgentMessage.User("hello")), session.messages)
    }

    @Test
    fun `returned messages snapshot does not track later session appends`() {
        val session = AgentSession("stable-transcript", listOf(AgentMessage.User("hello")))
        val firstSnapshot = session.messages

        session.append(AgentMessage.Assistant("later"))

        assertEquals(listOf(AgentMessage.User("hello")), firstSnapshot)
        assertEquals(
            listOf(AgentMessage.User("hello"), AgentMessage.Assistant("later")),
            session.messages
        )
    }

    @Test
    fun `concurrent replace exposes only old or complete replacement under forced overlap`() {
        val initial = listOf(
            AgentMessage.User("old request"),
            AgentMessage.Assistant("old answer")
        )
        val replacement = buildList {
            repeat(4_000) { index ->
                add(AgentMessage.User("new request $index"))
                add(AgentMessage.Assistant("new answer $index"))
            }
        }
        val session = AgentSession("atomic-replace", initial)
        val before = session.messages
        val executor = Executors.newFixedThreadPool(2)
        val snapshots = CopyOnWriteArrayList<List<AgentMessage>>()
        val readerEntered = CountDownLatch(1)
        val writerReady = CountDownLatch(1)
        val allowWriter = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val startTogether = CyclicBarrier(2)

        try {
            val reader = executor.submit {
                readerEntered.countDown()
                startTogether.await()
                writerReady.await()
                snapshots += session.messages
                allowWriter.countDown()
                while (!writerFinished.await(1, TimeUnit.MILLISECONDS)) {
                    snapshots += session.messages
                }
                snapshots += session.messages
            }
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS))
            val writer = executor.submit {
                startTogether.await()
                writerReady.countDown()
                allowWriter.await()
                try {
                    session.replaceTranscript(replacement)
                } finally {
                    writerFinished.countDown()
                }
            }

            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(snapshots.isNotEmpty())
        val complete = session.messages
        assertTrue(
            "a concurrent reader may see old or complete state, never clear/addAll intermediate state",
            snapshots.all { snapshot -> snapshot == before || snapshot == complete }
        )
        assertEquals(replacement, complete)
    }

    @Test
    fun `session completes an interrupted tool batch without duplicating real results`() {
        val firstCall = ToolCall("first", "tool", JsonObject(emptyMap()))
        val secondCall = ToolCall("second", "tool", JsonObject(emptyMap()))
        val thirdCall = ToolCall("third", "tool", JsonObject(emptyMap()))
        val session = AgentSession("tool-batch")

        session.append(AgentMessage.User("run both tools"))
        session.append(
            AgentMessage.Assistant(
                "working",
                toolCalls = listOf(firstCall, secondCall, thirdCall)
            )
        )
        session.append(
            AgentMessage.Tool(ToolResult(firstCall.id, firstCall.name, "real result"))
        )

        session.completeToolBatch(listOf(firstCall, secondCall, thirdCall))

        val results = session.messages.filterIsInstance<AgentMessage.Tool>()
        assertEquals(3, results.size)
        assertEquals("real result", results[0].result.content)
        assertEquals(secondCall.id, results[1].result.toolCallId)
        assertTrue(results[1].result.isError)
        assertEquals(thirdCall.id, results[2].result.toolCallId)
        assertTrue(results[2].result.isError)
    }

    @Test
    fun `concurrent complete tool batch exposes only old or complete batch under forced overlap`() {
        val firstCall = ToolCall("first", "tool", JsonObject(emptyMap()))
        val secondCall = ToolCall("second", "tool", JsonObject(emptyMap()))
        val thirdCall = ToolCall("third", "tool", JsonObject(emptyMap()))
        val initial = buildList {
            repeat(4_000) { index ->
                add(AgentMessage.User("previous request $index"))
                add(AgentMessage.Assistant("previous answer $index"))
            }
            add(AgentMessage.User("run both tools"))
        }
        val session = AgentSession("atomic-tool-batch", initial)
        session.append(
            AgentMessage.Assistant(
                "working",
                toolCalls = listOf(firstCall, secondCall, thirdCall)
            )
        )
        session.append(AgentMessage.Tool(ToolResult(firstCall.id, firstCall.name, "real result")))

        val before = session.messages
        val executor = Executors.newFixedThreadPool(2)
        val snapshots = CopyOnWriteArrayList<List<AgentMessage>>()
        val readerEntered = CountDownLatch(1)
        val writerReady = CountDownLatch(1)
        val allowWriter = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val startTogether = CyclicBarrier(2)
        try {
            val reader = executor.submit {
                readerEntered.countDown()
                startTogether.await()
                writerReady.await()
                snapshots += session.messages
                allowWriter.countDown()
                while (!writerFinished.await(1, TimeUnit.MILLISECONDS)) {
                    snapshots += session.messages
                }
                snapshots += session.messages
            }
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS))
            val completer = executor.submit {
                startTogether.await()
                writerReady.countDown()
                allowWriter.await()
                try {
                    session.completeToolBatch(listOf(firstCall, secondCall, thirdCall))
                } finally {
                    writerFinished.countDown()
                }
            }

            completer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        val complete = session.messages
        assertTrue(snapshots.isNotEmpty())
        assertTrue(
            "a concurrent reader must not observe a half-completed tool batch",
            snapshots.all { snapshot -> snapshot == before || snapshot == complete }
        )
        val completeResults = complete.filterIsInstance<AgentMessage.Tool>()
        assertEquals(3, completeResults.size)
        assertEquals(
            listOf(firstCall.id, secondCall.id, thirdCall.id),
            completeResults.map { it.result.toolCallId }
        )
        assertTrue(completeResults[1].result.isError)
        assertTrue(completeResults[2].result.isError)
    }
}
