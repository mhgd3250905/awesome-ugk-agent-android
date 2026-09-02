package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Small, app-private JSONL trace used to diagnose real Agent runs.
 *
 * The trace deliberately stores no prompt, response, command, or text value.
 * It keeps lengths, structural flags, selected safe enum-like values, and
 * short fingerprints so repeated calls can be compared without exposing the
 * original payload in logcat or the floating window.
 *
 * append()/reset() run on the Agent event thread (the UI thread during
 * streaming) and therefore never touch the file system: they only assign a
 * monotonically increasing sequence, format the line, and enqueue it into a
 * bounded command queue (overflow drops the oldest line and is counted — the
 * trace is best-effort diagnostics and must never block or wait). A single
 * background daemon writer consumes the queue in order and performs all file
 * IO, including the size compaction rewrite.
 */
internal class DemoAgentTraceStore internal constructor(
    private val traceFile: File,
    private val writerExecutor: Executor
) {
    /** Production constructor: durable trace rooted at application filesDir. */
    constructor(context: Context) : this(
        traceFile = File(context.applicationContext.filesDir, FILE_NAME),
        writerExecutor = SHARED_WRITER_EXECUTOR
    )

    private val lock = Any()
    private var sequence = 0L
    private var runId: String? = null
    private val pending = ArrayDeque<TraceCommand>(QUEUE_CAPACITY)
    private var droppedCount = 0L
    private var drainScheduled = false

    fun reset(conversationId: String?, sessionId: String?) {
        synchronized(lock) {
            runId = "run-${System.currentTimeMillis()}"
            sequence = 0L
            // Drop every not-yet-written line of the previous run, then queue
            // a truncate command so the on-disk reset stays ordered behind
            // batches the writer may already be processing.
            pending.clear()
            runCatching {
                enqueueCommandLocked(
                    TraceCommand.ResetTruncate(
                        DemoAgentTraceFormatter.marker(
                            event = "trace_started",
                            sequence = nextSequenceLocked(),
                            runId = requireNotNull(runId),
                            conversationId = conversationId,
                            sessionId = sessionId
                        )
                    )
                )
                scheduleDrainLocked()
            }
        }
    }

    fun append(event: AgentEvent) {
        synchronized(lock) {
            val activeRunId = runId ?: "attached-${System.currentTimeMillis()}".also {
                runId = it
                runCatching {
                    enqueueCommandLocked(
                        TraceCommand.Write(
                            DemoAgentTraceFormatter.marker(
                                event = "trace_attached",
                                sequence = nextSequenceLocked(),
                                runId = it,
                                conversationId = null,
                                sessionId = null
                            )
                        )
                    )
                }
            }
            runCatching {
                enqueueCommandLocked(
                    TraceCommand.Write(
                        DemoAgentTraceFormatter.event(
                            event = event,
                            sequence = nextSequenceLocked(),
                            runId = activeRunId,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }
            scheduleDrainLocked()
        }
    }

    /** Diagnostics for the best-effort overflow policy; counts dropped lines. */
    internal fun droppedLineCount(): Long = synchronized(lock) { droppedCount }

    private fun nextSequenceLocked(): Long {
        sequence += 1
        return sequence
    }

    private fun enqueueCommandLocked(command: TraceCommand) {
        pending.addLast(command)
        while (pending.size > QUEUE_CAPACITY) {
            // Never drop a queued reset marker; drop the oldest writable line.
            val dropIndex = if (pending.size > 1 && pending.first() is TraceCommand.ResetTruncate) 1 else 0
            pending.removeAt(dropIndex)
            droppedCount += 1
        }
    }

    private fun scheduleDrainLocked() {
        if (drainScheduled) return
        drainScheduled = true
        try {
            writerExecutor.execute { drain() }
        } catch (rejected: Throwable) {
            drainScheduled = false
        }
    }

    private fun drain() {
        val batch: List<TraceCommand>
        synchronized(lock) {
            batch = pending.toList()
            pending.clear()
            drainScheduled = false
        }
        runCatching { writeCommands(batch) }
    }

    private fun writeCommands(commands: List<TraceCommand>) {
        for (command in commands) {
            when (command) {
                is TraceCommand.ResetTruncate -> {
                    traceFile.parentFile?.mkdirs()
                    traceFile.writeText("", StandardCharsets.UTF_8)
                    appendLine(command.markerLine)
                }

                is TraceCommand.Write -> appendLine(command.line)
            }
        }
    }

    private fun appendLine(line: String) {
        traceFile.parentFile?.mkdirs()
        traceFile.appendText(line + "\n", StandardCharsets.UTF_8)
        if (traceFile.length() > MAX_BYTES) compact()
    }

    private fun compact() {
        val retained = traceFile.readLines(StandardCharsets.UTF_8)
            .takeLast(MAX_RETAINED_LINES)
        traceFile.writeText(
            if (retained.isEmpty()) "" else retained.joinToString("\n") + "\n",
            StandardCharsets.UTF_8
        )
    }

    private sealed interface TraceCommand {
        data class Write(val line: String) : TraceCommand
        data class ResetTruncate(val markerLine: String) : TraceCommand
    }

    companion object {
        const val FILE_NAME = "agent-runtime-trace.jsonl"

        private const val MAX_BYTES = 512 * 1024L
        private const val MAX_RETAINED_LINES = 1_500
        private const val QUEUE_CAPACITY = 256

        /** One daemon writer per process; drain batches execute in order. */
        private val SHARED_WRITER_EXECUTOR: Executor =
            Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
                Thread(runnable, "demo-agent-trace-writer").apply { isDaemon = true }
            })
    }
}

internal object DemoAgentTraceFormatter {
    fun marker(
        event: String,
        sequence: Long,
        runId: String,
        conversationId: String?,
        sessionId: String?
    ): String = buildJsonObject {
        put("seq", sequence)
        put("timestamp", System.currentTimeMillis())
        put("runId", runId)
        put("event", event)
        conversationId?.let { put("conversationIdHash", fingerprint(it)) }
        sessionId?.let { put("sessionIdHash", fingerprint(it)) }
    }.toString()

    fun event(
        event: AgentEvent,
        sequence: Long,
        runId: String,
        timestamp: Long
    ): String = buildJsonObject {
        put("seq", sequence)
        put("timestamp", timestamp)
        put("runId", runId)
        when (event) {
            is AgentEvent.Started -> {
                put("event", "started")
                put("sessionIdHash", fingerprint(event.sessionId))
                put("source", event.source.name)
                put("taskIdPresent", event.taskId != null)
                put("visibleInConversation", event.visibleInConversation)
            }

            is AgentEvent.ModelRequestStarted -> {
                put("event", "model_request_started")
                put("iteration", event.iteration)
                put("messageCount", event.messageCount)
                put("toolDefinitionCount", event.toolCount)
            }

            is AgentEvent.ModelResponded -> {
                put("event", "model_responded")
                put("toolCallCount", event.toolCalls.size)
                put("toolNames", event.toolCalls.joinToString(",") { it.name })
                put("contentChars", event.content.length)
                put("contentHash", fingerprint(event.content))
                put("reasoningChars", event.reasoningContent?.length ?: 0)
                event.elapsedMillis?.let { put("elapsedMillis", it) }
                event.stopReason?.let { put("stopReason", it) }
            }

            is AgentEvent.ModelThinkingDelta -> {
                put("event", "model_thinking_delta")
                put("deltaChars", event.delta.length)
            }

            is AgentEvent.ModelContentDelta -> {
                put("event", "model_content_delta")
                put("deltaChars", event.delta.length)
            }

            is AgentEvent.ToolStarted -> {
                put("event", "tool_started")
                putToolCall(event.call)
            }

            is AgentEvent.ToolProgress -> {
                put("event", "tool_progress")
                putToolCall(event.call)
                put("progressTitleHash", fingerprint(event.progress.title))
                event.progress.current?.let { put("current", it) }
                event.progress.total?.let { put("total", it) }
            }

            is AgentEvent.ToolFinished -> {
                put("event", "tool_finished")
                putToolResult(event.result)
            }

            is AgentEvent.UserMessageAppended -> {
                put("event", "user_message_appended")
                put("contentChars", event.content.length)
            }

            is AgentEvent.Completed -> {
                put("event", "completed")
                put("contentChars", event.content.length)
                put("contentHash", fingerprint(event.content))
            }

            is AgentEvent.Failed -> {
                put("event", "failed")
                put("messageChars", event.message.length)
                put("messageHash", fingerprint(event.message))
            }
        }
    }.toString()

    private fun JsonObjectBuilder.putToolCall(call: ToolCall) {
        put("tool", call.name)
        put("callId", call.id)
        put("inputKeys", call.input.keys.sorted().joinToString(","))
        put("inputHash", fingerprint(call.input.toString()))
        val normalizedName = call.name.lowercase()
        when (normalizedName) {
            "screen_perform_action" -> {
                call.input.stringValue("action")?.take(32)?.let { put("action", it) }
                put("snapshotIdPresent", call.input.containsKey("snapshotId"))
                put("nodeIdPresent", call.input.containsKey("nodeId"))
                put("textPresent", call.input.containsKey("text"))
            }

            "screen_find_ui_element" -> {
                put("selectorKeys", call.input.keys.sorted().joinToString(","))
            }

            "screen_gesture" -> {
                call.input.stringValue("gesture")?.take(32)?.let { put("gesture", it) }
            }

            "screen_press_key", "screen_global_action" -> {
                call.input.stringValue("key")?.take(32)?.let { put("key", it) }
                call.input.stringValue("action")?.take(32)?.let { put("action", it) }
            }

            "screen_launch_app" -> {
                put("packageNamePresent", call.input.containsKey("packageName"))
            }
        }
    }

    private fun JsonObjectBuilder.putToolResult(result: ToolResult) {
        put("tool", result.name)
        put("callId", result.toolCallId)
        put("isError", result.isError)
        put("contentChars", result.content.length)
        put("contentHash", fingerprint(result.content))
        put("metadataKeys", result.metadata.keys.sorted().joinToString(","))
        result.metadata.stringValue("code")?.let { put("code", it) }
        result.metadata.stringValue("recovery")?.let { put("recovery", it) }
        result.metadata.stringValue("recoveryTool")?.let { put("recoveryTool", it) }
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(16) {
            digest.take(8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
    }
}
