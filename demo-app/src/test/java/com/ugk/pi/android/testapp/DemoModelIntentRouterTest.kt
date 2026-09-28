package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentImageContent
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentToolDefinition
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ModelStreamChunk
import com.ugk.pi.android.ToolCall
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DemoModelIntentRouterTest {
    @Test
    fun continueRoutesWithoutImagesAndPreservesOriginalMainRequest() = runBlocking {
        val mainResponse = ModelResponse("图片说明")
        val provider = SpyProvider(ModelResponse("""{"route":"continue"}"""), mainResponse)
        val request = request("请解释图片")

        assertSame(mainResponse, router(provider).generate(request))

        assertTextOnlyRoute(provider.requests.first())
        assertSame(request, provider.requests.last())
        assertSame(request.messages.last(), provider.requests.last().messages.last())
        assertEquals(2, (provider.requests.last().messages.last() as AgentMessage.User).images.size)
    }

    @Test
    fun streamingContinueAlsoPreservesOriginalImages() = runBlocking {
        val mainResponse = ModelResponse("图片说明")
        val provider = SpyProvider(ModelResponse("""{"route":"continue"}"""), mainResponse)
        val request = request("请解释图片")

        val chunks = router(provider).generateStream(request).toList()

        assertEquals(listOf(ModelStreamChunk.Completed(mainResponse)), chunks)
        assertTextOnlyRoute(provider.requests.first())
        assertSame(request, provider.requests.last())
    }

    @Test
    fun explicitTextTimingStillProducesProposalWithoutMainModelRequest() = runBlocking {
        val provider = SpyProvider(ModelResponse("""{"route":"delay","delaySeconds":60,"instruction":"提醒我喝水"}"""))

        val result = router(provider).generate(request("60秒后提醒我喝水"))

        assertEquals(1, provider.requests.size)
        assertTextOnlyRoute(provider.requests.single())
        assertEquals("demo_delay_propose", result.toolCalls.single().name)
        assertEquals("60", result.toolCalls.single().input.getValue("delaySeconds").jsonPrimitive.content)
    }

    @Test
    fun attachmentDependentContinueLetsMainAgentProposeAfterReadingOriginalImages() = runBlocking {
        val proposal = ModelResponse("", listOf(ToolCall("proposal", "demo_delay_propose", JsonObject(emptyMap()))))
        val provider = SpyProvider(ModelResponse("""{"route":"continue"}"""), proposal)
        val request = request("按图片里的时间和内容提醒我")

        assertSame(proposal, router(provider).generate(request))

        val routing = provider.requests.first()
        assertTextOnlyRoute(routing)
        val instructions = (routing.messages.first() as AgentMessage.System).content
        assertTrue(instructions.contains("action or timing depends on attachment contents"))
        assertTrue(instructions.contains("return {\"route\":\"continue\"}"))
        assertSame(request, provider.requests.last())
        assertEquals("demo_delay_propose", provider.requests.last().tools.single().name)
    }

    @Test
    fun invalidRouteRetriesTextOnlyThenPassesOriginalRequest() = runBlocking {
        val provider = SpyProvider(ModelResponse("invalid"), ModelResponse("""{"route":"continue"}"""), ModelResponse("done"))
        val request = request("看看图片")

        router(provider).generate(request)

        assertEquals(3, provider.requests.size)
        provider.requests.take(2).forEach(::assertTextOnlyRoute)
        assertTrue((provider.requests[1].messages.first() as AgentMessage.System).content.contains("previous routing output was invalid"))
        assertSame(request, provider.requests.last())
    }

    @Test
    fun twoInvalidRoutesFailWithoutStartingMainRequest() = runBlocking {
        val provider = SpyProvider(ModelResponse("invalid"), ModelResponse("invalid"))
        try {
            router(provider).generate(request("看看图片"))
            fail("Invalid routing must not create a task or start a main request")
        } catch (_: IllegalStateException) {
            assertEquals(2, provider.requests.size)
            provider.requests.forEach(::assertTextOnlyRoute)
        }
    }

    private fun assertTextOnlyRoute(request: ModelRequest) {
        assertTrue(request.messages.filterIsInstance<AgentMessage.User>().all { it.images.isEmpty() })
        assertTrue(request.tools.isEmpty())
        assertTrue((request.messages.last() as AgentMessage.User).content.contains("历史文字"))
    }

    private fun request(text: String) = ModelRequest(
        sessionId = "session",
        messages = listOf(
            AgentMessage.User("历史文字", images = listOf(AgentImageContent("old-image"))),
            AgentMessage.User(text, images = listOf(AgentImageContent("first-image"), AgentImageContent("second-image", "image/png")))
        ),
        tools = listOf(AgentToolDefinition("demo_delay_propose", "Propose a timer", JsonObject(emptyMap()))),
        isFirstModelRequest = true
    )

    private fun router(provider: LLMProvider) = DemoModelIntentRouter(
        provider,
        ProviderProfile.from(ApiProviderConfig("test", "https://example.invalid/v1", "unused", "test-model"))
    )

    private class SpyProvider(vararg responses: ModelResponse) : LLMProvider {
        val requests = mutableListOf<ModelRequest>()
        private val responses = responses.toList().iterator()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return responses.next()
        }
    }
}
