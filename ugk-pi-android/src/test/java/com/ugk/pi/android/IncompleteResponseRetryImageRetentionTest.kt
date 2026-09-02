package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression test for the image-losing incomplete-response retry.
 *
 * When a run carries input images and the first model response is an
 * incomplete final response (blank content, no tool calls), the runtime
 * retries with a prompt that demands reproducing the complete answer from
 * the original inputs. The input images used to be attached as a one-shot
 * transient [AgentMessage.User] consumed by the first request only, so the
 * retry reached the model with NO images at all — a self-contradictory
 * contract for multimodal input.
 *
 * The retry path now re-arms the original input attachment. Normal
 * tool-iteration requests keep the one-shot semantics (see
 * AgentRuntimeTest "tool attachments are consumed by the next request and
 * not an incomplete retry"), and the durable transcript never stores the
 * images; total re-sends are bounded by MAX_INCOMPLETE_RESPONSE_RETRIES.
 */
class IncompleteResponseRetryImageRetentionTest {

    @Test
    fun `incomplete-response retry request re-attaches the input images`() = runBlocking {
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
        val session = AgentSession("image-retry-retention")

        val events = runtime.run(
            session,
            AgentRunInput(content = "look at this", images = listOf(image))
        ).toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertEquals(2, requests.size)

        // Request #1 carries the input image.
        assertEquals(
            "first request must carry the input image",
            listOf(image),
            requests[0].messages.filterIsInstance<AgentMessage.User>().flatMap { it.images }
        )

        // Fixed behavior: the retry request re-attaches the original input
        // images so the model can actually reproduce the full answer.
        assertEquals(
            "incomplete-response retry must re-attach the input images",
            listOf(image),
            requests[1].messages.filterIsInstance<AgentMessage.User>().flatMap { it.images }
        )

        // Durable transcript keeps no images (the attachment stays transient;
        // only the retry re-arms it, durable messages are stripped on append).
        assertFalse(
            "durable transcript must not contain the image",
            session.messages.any { message ->
                message is AgentMessage.User && message.images.isNotEmpty()
            }
        )
    }
}
