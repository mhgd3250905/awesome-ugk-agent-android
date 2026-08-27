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

/**
 * Small, app-private JSONL trace used to diagnose real Agent runs.
 *
 * The trace deliberately stores no prompt, response, command, or text value.
 * It keeps lengths, structural flags, selected safe enum-like values, and
 * short fingerprints so repeated calls can be compared without exposing the
 * original payload in logcat or the floating window.
 */
internal class DemoAgentTraceStore(context: Context) {
    companion object {
        const val FILE_NAME = "agent-runtime-trace.jsonl"

        private const val MAX_BYTES = 512 * 1024L
        private const val MAX_RETAINED_LINES = 1_500
    }

    private val traceFile = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()
    private var sequence = 0L
    private var runId: String? = null

    fun reset(conversationId: String?, sessionId: String?) {
        synchronized(lock) {
            runId = "run-${System.currentTimeMillis()}"
            sequence = 0L
            runCatching {
                traceFile.parentFile?.mkdirs()
                traceFile.writeText("", StandardCharsets.UTF_8)
                appendLineLocked(
                    DemoAgentTraceFormatter.marker(
                        event = "trace_started",
                        sequence = nextSequenceLocked(),
                        runId = requireNotNull(runId),
                        conversationId = conversationId,
                        sessionId = sessionId
                    )
                )
            }
        }
    }

    fun append(event: AgentEvent) {
        synchronized(lock) {
            val activeRunId = runId ?: "attached-${System.currentTimeMillis()}".also {
                runId = it
                runCatching {
                    appendLineLocked(
                        DemoAgentTraceFormatter.marker(
                            event = "trace_attached",
                            sequence = nextSequenceLocked(),
                            runId = it,
                            conversationId = null,
                            sessionId = null
                        )
                    )
                }
            }
            runCatching {
                appendLineLocked(
                    DemoAgentTraceFormatter.event(
                        event = event,
                        sequence = nextSequenceLocked(),
                        runId = activeRunId,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun nextSequenceLocked(): Long {
        sequence += 1
        return sequence
    }

    private fun appendLineLocked(line: String) {
        traceFile.parentFile?.mkdirs()
        traceFile.appendText(line + "\n", StandardCharsets.UTF_8)
        if (traceFile.length() > MAX_BYTES) compactLocked()
    }

    private fun compactLocked() {
        val retained = traceFile.readLines(StandardCharsets.UTF_8)
            .takeLast(MAX_RETAINED_LINES)
        traceFile.writeText(
            if (retained.isEmpty()) "" else retained.joinToString("\n") + "\n",
            StandardCharsets.UTF_8
        )
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
