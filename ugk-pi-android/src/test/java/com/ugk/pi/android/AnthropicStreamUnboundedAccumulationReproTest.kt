package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REPRO for the unbounded SSE accumulation candidate defect (candidate 2).
 *
 * The non-streaming path is hard-capped inside the transport:
 * JavaNetHttpTransport.post reads at most maxResponseBytes (default 4 MB) and
 * throws "HTTP response exceeds maxResponseBytes=..." beyond that. The
 * streaming path in [AnthropicMessagesProvider.parseSseStream] has NO
 * provider-level cap: accumulatedContent, currentToolInputJson and toolCalls
 * grow with every incoming `content_block_delta` / `input_json_delta` event
 * and the per-line cap of the transport does not bound the total across
 * events.
 *
 * The tests below feed ~8 MB of text deltas (and ~4.9 MB of tool-argument
 * deltas, i.e. beyond the 4 MB non-streaming cap) through a test transport
 * and pin the CURRENT behavior: the provider happily returns everything,
 * with no truncation and no failure.
 */
class AnthropicStreamUnboundedAccumulationReproTest {

    /** Matches the default cap of JavaNetHttpTransport non-streaming reads. */
    private val nonStreamingCapBytes = 4 * 1024 * 1024

    @Test
    fun `streamed text accumulates 8MB across deltas with no cap or cutoff`() = runBlocking {
        val chunk = "A".repeat(4096)
        val deltaCount = 2000
        val lines = ArrayList<String>(deltaCount + 4).apply {
            add("data: {\"type\":\"message_start\",\"message\":{}}")
            add("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}")
            repeat(deltaCount) {
                add("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$chunk\"}}")
            }
            add("data: {\"type\":\"content_block_stop\",\"index\":0}")
            add("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")
            add("data: {\"type\":\"message_stop\"}")
        }
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "https://example.com",
            transport = StreamingLinesTransport(lines)
        )

        val completed = provider.generateStream(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        ).toList().filterIsInstance<ModelStreamChunk.Completed>().single()

        val expectedChars = deltaCount * chunk.length // 8_388_608 chars
        assertEquals(
            "streamed text must be accumulated in full (no truncation, no failure)",
            expectedChars,
            completed.response.content.length
        )
        assertTrue(
            "accumulated stream text ($expectedChars ASCII chars) exceeds the " +
                "non-streaming maxResponseBytes cap ($nonStreamingCapBytes bytes) " +
                "with nothing stopping it — evidence of unbounded accumulation",
            expectedChars > nonStreamingCapBytes
        )
    }

    @Test
    fun `streamed tool arguments accumulate past the non-streaming cap into a complete tool call`() =
        runBlocking {
            val piece = "x".repeat(4096)
            val pieceCount = 1200 // ~4.9 MB of argument payload
            val lines = ArrayList<String>(pieceCount + 7).apply {
                add("data: {\"type\":\"message_start\",\"message\":{}}")
                add("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_big\",\"name\":\"echo\"}}")
                add("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"cmd\\\":\\\"\"}}")
                repeat(pieceCount) {
                    add("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"$piece\"}}")
                }
                add("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"}\"}}")
                add("data: {\"type\":\"content_block_stop\",\"index\":0}")
                add("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}")
                add("data: {\"type\":\"message_stop\"}")
            }
            val provider = AnthropicMessagesProvider(
                apiKey = "test-key",
                model = "test-model",
                baseUrl = "https://example.com",
                transport = StreamingLinesTransport(lines)
            )

            val completed = provider.generateStream(
                ModelRequest(
                    sessionId = "s1",
                    messages = listOf(AgentMessage.User("hello")),
                    tools = emptyList()
                )
            ).toList().filterIsInstance<ModelStreamChunk.Completed>().single()

            val call = completed.response.toolCalls.single()
            val argumentChars = call.input["cmd"]?.jsonPrimitive?.contentOrNull?.length
            val expectedChars = pieceCount * piece.length // 4_915_200 chars
            assertEquals(expectedChars, argumentChars)
            assertTrue(
                "tool argument accumulation ($expectedChars ASCII chars) exceeds the " +
                    "non-streaming maxResponseBytes cap ($nonStreamingCapBytes bytes) " +
                    "with nothing stopping it — evidence of unbounded accumulation",
                expectedChars > nonStreamingCapBytes
            )
        }

    private class StreamingLinesTransport(
        private val lines: List<String>
    ) : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse =
            throw UnsupportedOperationException("stream repro must not call post()")

        override fun postStream(request: HttpRequest): Flow<String> = flow {
            lines.forEach { emit(it) }
        }
    }
}
