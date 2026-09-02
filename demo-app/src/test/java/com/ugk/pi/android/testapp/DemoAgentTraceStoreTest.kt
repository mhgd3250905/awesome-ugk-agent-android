package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoAgentTraceStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendDoesNoFileIoOnCallingThreadAndDefersWriteToExecutor() {
        val executor = RecordingExecutor()
        val traceFile = temporaryFolder.root.resolve("trace-append.jsonl")
        val store = DemoAgentTraceStore(traceFile, executor)

        store.append(AgentEvent.ModelContentDelta("hello"))

        // The write task was handed to the executor but not run there.
        assertEquals(1, executor.submitted.size)
        // Deferred: no trace file was created by the calling thread.
        assertFalse(traceFile.exists())

        executor.runAll()

        val lines = traceFile.readLines()
        // First append attaches to a fresh store: attached marker + event.
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"event\":\"trace_attached\""))
        assertTrue(lines[1].contains("\"event\":\"model_content_delta\""))
        assertTrue(lines[1].contains("\"deltaChars\":5"))
    }

    @Test
    fun overflowDropsOldestLinesAndSequenceIsNeverReused() {
        val executor = RecordingExecutor()
        val traceFile = temporaryFolder.root.resolve("trace-overflow.jsonl")
        val store = DemoAgentTraceStore(traceFile, executor)

        // The first append enqueues the attached marker (seq 1) plus its own
        // event (seq 2); appends 2..301 enqueue events seq 3..302. That is
        // 302 queued lines, 46 above the 256 capacity.
        repeat(301) { store.append(AgentEvent.ModelContentDelta("x")) }
        assertEquals(46L, store.droppedLineCount())

        executor.runAll()

        val sequences = traceFile.readLines()
            .map { line ->
                val match = Regex("\"seq\":(\\d+)").find(line)
                match?.groupValues?.get(1)?.toInt()
                    ?: error("trace line without sequence: $line")
            }
        // The oldest queued lines were dropped, the survivors keep their
        // original strictly increasing sequence numbers.
        assertEquals((47..302).toList(), sequences)
    }

    @Test
    fun resetDropsUnwrittenLinesAndWritesOnlyTheNewRunMarker() {
        val executor = RecordingExecutor()
        val traceFile = temporaryFolder.root.resolve("trace-reset.jsonl")
        val store = DemoAgentTraceStore(traceFile, executor)

        store.append(AgentEvent.ModelContentDelta("stale-1"))
        store.append(AgentEvent.ModelContentDelta("stale-2"))
        store.reset(conversationId = "conversation-1", sessionId = "session-1")

        executor.runAll()

        val lines = traceFile.readLines()
        // The stale run's queued lines never reach the file; the reset
        // truncates whatever existed and only the new marker remains.
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\"event\":\"trace_started\""))
        assertTrue(lines.single().contains("\"seq\":1"))
        assertFalse(lines.single().contains("stale"))
    }

    @Test
    fun resetTruncatesLinesAlreadyWrittenByTheWriter() {
        val executor = RecordingExecutor()
        val traceFile = temporaryFolder.root.resolve("trace-reset-after-write.jsonl")
        val store = DemoAgentTraceStore(traceFile, executor)

        store.append(AgentEvent.ModelContentDelta("before-reset"))
        executor.runAll()
        assertTrue(traceFile.readLines().any { it.contains("model_content_delta") })

        store.reset(conversationId = null, sessionId = null)
        executor.runAll()

        val lines = traceFile.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\"event\":\"trace_started\""))
    }

    private class RecordingExecutor : Executor {
        val submitted = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            submitted += command
        }

        fun runAll() {
            val tasks = submitted.toList()
            submitted.clear()
            tasks.forEach { it.run() }
        }
    }
}
