package com.ugk.pi.android.testapp

import com.ugk.pi.android.AnthropicRetryPolicy
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ModelStreamChunk
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/** Teaching-only request settings; ordinary chat and the user's saved provider settings are untouched. */
internal class DemoTeachingModelProvider(config: ApiProviderConfig) : LLMProvider {
    private val profile = ProviderProfile.from(config)
    val protocolId: String get() = profile.resolvedProtocol.stableId
    private val officialGlm53 = profile.resolvedProtocol == ProviderProtocol.OPENAI_CHAT_COMPLETIONS &&
        URI(profile.endpoint).host.equals("open.bigmodel.cn", ignoreCase = true) &&
        config.model.lowercase() in setOf("glm-5.3", "glm-5.3-flash", "glm-5.3-flashx")
    private val outputBudget = (config.maxOutputTokens ?: 8192).coerceIn(1, 131_072)
    val cacheScope = fingerprint(listOf(
        "teaching-provider-v1", profile.endpoint, profile.resolvedProtocol.stableId,
        config.model, outputBudget.toString(), if (officialGlm53) "reasoning-low" else "provider-default"
    ).joinToString("\n"))

    private val delegate = profile.createRuntimeProvider(object : DemoHttpTransport {
        private val network = JavaNetDemoHttpTransport(readTimeoutMillis = 180_000)
        private fun prepare(request: DemoHttpRequest): DemoHttpRequest {
            if (!officialGlm53 || request.method != "POST" || request.url != profile.endpoint) return request
            val body = Json.parseToJsonElement(checkNotNull(request.body)).jsonObject
            // GLM-5.3 requires thinking; low is its supported concise-reasoning mode, not disabled thinking.
            val adjusted = JsonObject(body + mapOf(
                "max_tokens" to JsonPrimitive(outputBudget),
                "reasoning_effort" to JsonPrimitive("low"),
                "thinking" to buildJsonObject { put("type", "enabled") }
            ))
            return request.copy(body = adjusted.toString())
        }
        override suspend fun request(request: DemoHttpRequest): DemoHttpResponse {
            val response = network.request(prepare(request))
            checkServiceResponse(response.statusCode, response.body)
            return response
        }
        override fun postStream(request: DemoHttpRequest): Flow<String> = flow {
            network.postStream(prepare(request)).collect { line ->
                // Inspect only the response envelope. Never log a streamed text/reasoning delta.
                val payload = line.removePrefix("data:").trim()
                if (payload.startsWith("{") && payload.contains("\"error\"")) {
                    checkServiceResponse(200, payload)
                }
                emit(line)
            }
        }
    }, AnthropicRetryPolicy(maxAttempts = 1))

    private fun checkServiceResponse(status: Int, body: String) {
        val hasError = runCatching {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("error") is JsonObject
        }.getOrDefault(false)
        if (status !in 200..299 || hasError) throw DemoTeachingRequestFailure.http(status, body)
    }

    override suspend fun generate(request: ModelRequest): ModelResponse = try {
        delegate.generate(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw DemoTeachingRequestFailure.from(error)
    }

    override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
        try {
            delegate.generateStream(request).collect { emit(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw DemoTeachingRequestFailure.from(error)
        }
    }

    companion object {
        fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
