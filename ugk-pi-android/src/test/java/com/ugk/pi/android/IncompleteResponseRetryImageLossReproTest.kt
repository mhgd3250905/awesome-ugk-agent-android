package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REPRO for the image-losing retry candidate defect (candidate 3).
 *
 * When a run carries input images and the first model response is an
 * incomplete final response (blank content, no tool calls), the runtime
 * retries the request. The input images are attached as a one-shot transient
 * [AgentMessage.User] that is consumed by the first request only
 * ("Each transient attachment belongs to this one request only.",
 * AgentRuntime.runInternal), and the incomplete-response retry `continue`
 * does not re-arm it. The retry request therefore reaches the model with NO
 * images at all.
 *
 * This test pins the CURRENT behavior: request #1 carries the image,
 * request #2 (the retry) carries none, and the durable transcript carries
 * none either (the latter two facts match the existing design assertions in
 * AgentRuntimeTranscriptPreparationTest, which explicitly asserts that input
 * images are "not resent on an incomplete-response retry").
 */
class IncompleteResponseRetryImageLossReproTest {

    @Test
    fun `incomplete-response retry request loses the input images`() = runBlocking {
        val image = AgentImageContent(base64Data = "AQID", mimeType = "image/png")
        val requests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requests += request
                    return if (requests.size == 1) {
                        // Blank content with no tool calls triggers
                        // isIncompleteFinalResponse() and forces the retry.
                        ModelResponse(content = "")
                    } else {
                        ModelResponse(content = "done")
                    }
                }
            })
            .build()
        val session = AgentSession("image-retry-loss")

        val events = runtime.run(
            session,
            AgentRunInput(content = "look at this", images = listOf(image))
        ).toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertEquals(2, requests.size)

        // Request #1 does carry the input image.
        assertEquals(
            "first request must carry the input image",
            listOf(image),
            requests[0].messages.filterIsInstance<AgentMessage.User>().flatMap { it.images }
        )

        // Defect evidence: the retry request contains no images anywhere.
        val retryImageBlocks = requests[1].messages
            .filterIsInstance<AgentMessage.User>()
            .flatMap { it.images }
        assertTrue(
            "current behavior: the incomplete-response retry request loses all input images " +
                "(retry carried ${retryImageBlocks.size} images)",
            retryImageBlocks.isEmpty()
        )
        assertTrue(
            "no non-user message carries images either",
            requests[1].messages.all { message ->
                message !is AgentMessage.User || message.images.isEmpty()
            }
        )

        // Durable transcript keeps no images (existing design: durable
        // messages are stripped of attachments on append).
        assertFalse(
            "durable transcript must not contain the image (existing design)",
            session.messages.any { message ->
                message is AgentMessage.User && message.images.isNotEmpty()
            }
        )
    }
}
