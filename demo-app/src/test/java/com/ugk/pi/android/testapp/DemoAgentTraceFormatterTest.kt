package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoAgentTraceFormatterTest {
    @Test
    fun toolTraceKeepsStructureWithoutRawText() {
        val rawText = "secret user text"
        val record = DemoAgentTraceFormatter.event(
            event = AgentEvent.ToolStarted(
                ToolCall(
                    id = "call-1",
                    name = "screen_perform_action",
                    input = buildJsonObject {
                        put("action", "set_text")
                        put("snapshotId", "snapshot-1")
                        put("nodeId", "0.1.2")
                        put("text", rawText)
                    }
                )
            ),
            sequence = 3,
            runId = "run-1",
            timestamp = 100L
        )

        assertTrue(record.contains("\"action\":\"set_text\""))
        assertTrue(record.contains("\"snapshotIdPresent\":true"))
        assertTrue(record.contains("\"nodeIdPresent\":true"))
        assertTrue(record.contains("\"textPresent\":true"))
        assertFalse(record.contains(rawText))
    }

    @Test
    fun modelResponseTraceCanIdentifyRepeatedShapeWithoutResponseText() {
        val rawResponse = "do the same thing again"
        val record = DemoAgentTraceFormatter.event(
            event = AgentEvent.ModelResponded(
                content = rawResponse,
                toolCalls = emptyList(),
                elapsedMillis = 12L,
                stopReason = "stop"
            ),
            sequence = 4,
            runId = "run-1",
            timestamp = 101L
        )

        assertTrue(record.contains("\"toolCallCount\":0"))
        assertTrue(record.contains("\"contentChars\":${rawResponse.length}"))
        assertFalse(record.contains(rawResponse))
    }
}
