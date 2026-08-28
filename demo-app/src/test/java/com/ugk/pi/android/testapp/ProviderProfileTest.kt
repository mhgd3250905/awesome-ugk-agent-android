package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileTest {

    @Test
    fun providerProtocolJsonRoundTripsAndUnknownLegacyValuesUseAuto() {
        val state = ApiProviderSettingsState(
            activeId = "cfg-1",
            configs = listOf(
                config(
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS
                )
            )
        )

        val encoded = ApiProviderSettingsJson.encode(state)
        assertTrue(encoded.contains("\"protocol\":\"openai_chat_completions\""))
        assertEquals(
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            ApiProviderSettingsJson.decode(encoded).configs.single().protocol
        )

        val missingProtocol = ApiProviderSettingsJson.decode(legacyJson())
        assertEquals(ProviderProtocol.AUTO, missingProtocol.configs.single().protocol)

        val unknownProtocol = ApiProviderSettingsJson.decode(
            legacyJson(protocolJson = "\"protocol\":\"future_protocol\",")
        )
        assertEquals(ProviderProtocol.AUTO, unknownProtocol.configs.single().protocol)
    }

    @Test
    fun autoProtocolUsesExistingUrlRulesAndExplicitProtocolWins() {
        assertEquals(
            ProviderProtocol.ANTHROPIC_MESSAGES,
            ProviderProfile.from(config(baseUrl = "https://proxy.example/anthropic"))
                .protocol
        )
        assertEquals(
            ProviderProtocol.ANTHROPIC_MESSAGES,
            ProviderProfile.from(config(baseUrl = "https://proxy.example/v1/messages"))
                .protocol
        )
        assertEquals(
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            ProviderProfile.from(config(baseUrl = "https://provider.example"))
                .protocol
        )
        assertEquals(
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            ProviderProfile.from(
                config(
                    baseUrl = "https://api.anthropic.com",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS
                )
            ).protocol
        )
    }

    @Test
    fun profileNormalizesAnthropicAndOpenAiEndpointsWithoutDuplicatePaths() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            ProviderProfile.from(config("https://api.anthropic.com")).endpoint
        )
        assertEquals(
            "https://proxy.example/v1/messages",
            ProviderProfile.from(config("https://proxy.example/v1/messages")).endpoint
        )
        assertEquals(
            "https://proxy.example/v1/messages",
            ProviderProfile.from(
                config(
                    baseUrl = "https://proxy.example/v1/",
                    protocol = ProviderProtocol.ANTHROPIC_MESSAGES
                )
            ).endpoint
        )

        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderProfile.from(
                config(
                    baseUrl = "https://api.openai.com",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS
                )
            ).endpoint
        )
        assertEquals(
            "https://proxy.example/v1/chat/completions",
            ProviderProfile.from(
                config(
                    baseUrl = "https://proxy.example/v1",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS
                )
            ).endpoint
        )
        assertEquals(
            "https://proxy.example/v1/chat/completions",
            ProviderProfile.from(
                config(
                    baseUrl = "https://proxy.example/v1/chat/completions",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS
                )
            ).endpoint
        )
    }

    @Test
    fun profileOwnsQuotaVendorRecognition() {
        val cases = listOf(
            "https://api.deepseek.com" to QuotaProviderType.DEEPSEEK,
            "https://api.siliconflow.cn/v1" to QuotaProviderType.SILICONFLOW,
            "https://api.siliconflow.com/v1" to QuotaProviderType.SILICONFLOW,
            "https://api.moonshot.cn/v1" to QuotaProviderType.MOONSHOT,
            "https://api.moonshot.ai/v1" to QuotaProviderType.MOONSHOT,
            "https://openrouter.ai/api/v1" to QuotaProviderType.OPENROUTER,
            "https://api.example.com/v1" to QuotaProviderType.UNKNOWN
        )

        cases.forEach { (baseUrl, expectedVendor) ->
            assertEquals(expectedVendor, ProviderProfile.from(config(baseUrl)).vendor)
        }
    }

    @Test
    fun probeAndRuntimeUseTheSameProtocolEndpointAndHeaders() = runBlocking {
        val cases = listOf(
            ProviderProtocol.ANTHROPIC_MESSAGES to "https://proxy.example/v1/messages",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS to "https://proxy.example/v1/chat/completions"
        )

        cases.forEach { (protocol, expectedEndpoint) ->
            val transport = RecordingDemoHttpTransport(
                responseBody = when (protocol) {
                    ProviderProtocol.ANTHROPIC_MESSAGES ->
                        """{"content":[{"type":"text","text":"ok"}]}"""
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS ->
                        """{"choices":[{"message":{"content":"ok"}}]}"""
                    ProviderProtocol.AUTO -> error("test case must resolve to an explicit protocol")
                }
            )
            val config = config(
                baseUrl = "https://proxy.example",
                protocol = protocol
            )
            val profile = ProviderProfile.from(config)

            profile.createRuntimeProvider(transport).generate(simpleRequest())
            val runtimeRequest = transport.requests.single()

            transport.requests.clear()
            val connectivity = ApiQuotaAndConnectivityService.testConnectivity(config, transport)
            val probeRequest = transport.requests.single()

            assertTrue(connectivity.success)
            assertEquals("POST", runtimeRequest.method)
            assertEquals("POST", probeRequest.method)
            assertEquals(expectedEndpoint, runtimeRequest.url)
            assertEquals(runtimeRequest.url, probeRequest.url)
            assertEquals(runtimeRequest.headers, probeRequest.headers)
        }
    }

    @Test
    fun quotaQueryUsesTheInjectedTransportAndProfileVendor() = runBlocking {
        val cases = listOf(
            "https://api.deepseek.com" to
                "https://api.deepseek.com/user/balance",
            "https://api.siliconflow.com/v1" to
                "https://api.siliconflow.com/v1/user/info",
            "https://api.moonshot.ai/v1" to
                "https://api.moonshot.ai/v1/users/me/balance",
            "https://openrouter.ai/api/v1" to
                "https://openrouter.ai/api/v1/auth/key"
        )

        cases.forEach { (baseUrl, expectedEndpoint) ->
            val transport = RecordingDemoHttpTransport(responseBody = "{}")
            val result = ApiQuotaAndConnectivityService.queryBalance(
                config(baseUrl),
                transport
            )

            assertTrue(result.supported)
            assertFalse(transport.requests.isEmpty())
            assertEquals("GET", transport.requests.single().method)
            assertEquals(expectedEndpoint, transport.requests.single().url)
        }
    }

    @Test
    fun runtimeStreamingStillUsesTransportStreamMethod() = runBlocking {
        val transport = RecordingDemoHttpTransport(
            responseBody = """{"choices":[{"message":{"content":"ok"}}]}""",
            streamLines = flowOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                "data: [DONE]"
            )
        )
        val config = config(
            baseUrl = "https://proxy.example",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS
        )

        val chunks = profileProvider(config, transport).generateStream(simpleRequest())
        chunks.collect {}

        assertTrue(transport.requests.isEmpty())
        assertEquals(
            "https://proxy.example/v1/chat/completions",
            transport.streamRequests.single().url
        )
    }

    private fun profileProvider(
        config: ApiProviderConfig,
        transport: DemoHttpTransport
    ): LLMProvider = ProviderProfile.from(config).createRuntimeProvider(transport)

    private fun simpleRequest(): ModelRequest = ModelRequest(
        sessionId = "provider-profile-test",
        messages = listOf(AgentMessage.User("hello")),
        tools = emptyList()
    )

    private fun config(
        baseUrl: String = "https://provider.example",
        protocol: ProviderProtocol = ProviderProtocol.AUTO
    ): ApiProviderConfig = ApiProviderConfig(
        id = "provider-profile-test",
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = "test-model",
        protocol = protocol
    )

    private fun legacyJson(protocolJson: String = ""): String = """
        {
            "activeId": "legacy",
            "configs": [
                {
                    "id": "legacy",
                    "baseUrl": "https://provider.example",
                    "apiKey": "test-key",
                    "model": "test-model",
                    $protocolJson
                    "name": "legacy"
                }
            ]
        }
    """.trimIndent()

    private class RecordingDemoHttpTransport(
        private val responseBody: String,
        private val streamLines: Flow<String> = flowOf("data: [DONE]")
    ) : DemoHttpTransport {
        val requests = mutableListOf<DemoHttpRequest>()
        val streamRequests = mutableListOf<DemoHttpRequest>()

        override suspend fun request(request: DemoHttpRequest): DemoHttpResponse {
            requests += request
            return DemoHttpResponse(statusCode = 200, body = responseBody)
        }

        override fun postStream(request: DemoHttpRequest): Flow<String> {
            streamRequests += request
            return streamLines
        }
    }
}
