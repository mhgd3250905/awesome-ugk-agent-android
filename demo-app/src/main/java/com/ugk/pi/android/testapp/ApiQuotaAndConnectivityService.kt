package com.ugk.pi.android.testapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 连通性测试结果。
 */
data class ApiConnectivityResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val httpCode: Int? = null
)

/**
 * 余额/用量查询结果。
 */
data class ApiBalanceResult(
    val supported: Boolean,
    val provider: QuotaProviderType,
    val balanceText: String?,
    val error: String? = null
)

/**
 * 综合检测结果。
 */
data class ApiTestSummary(
    val connectivity: ApiConnectivityResult,
    val balance: ApiBalanceResult?
)

/**
 * API 连通性测试与各大模型平台额度查询服务。
 */
object ApiQuotaAndConnectivityService {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /** Compatibility entry point; provider mapping lives in ProviderProfile. */
    fun detectProvider(baseUrl: String): QuotaProviderType =
        ProviderProfile.from(legacyConfig(baseUrl)).vendor

    /**
     * 执行连通性与延迟测试。
     */
    suspend fun testConnectivity(
        config: ApiProviderConfig,
        transport: DemoHttpTransport = JavaNetDemoHttpTransport(
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 10_000
        )
    ): ApiConnectivityResult = withContext(Dispatchers.IO) {
        val profile = ProviderProfile.from(config)
        val start = System.currentTimeMillis()
        sendProbeRequest(buildProbeRequest(profile), transport, start)
    }

    /** Compatibility overload for callers that have not migrated to a profile. */
    suspend fun testConnectivity(
        baseUrl: String,
        apiKey: String,
        model: String,
        transport: DemoHttpTransport = JavaNetDemoHttpTransport(
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 10_000
        )
    ): ApiConnectivityResult = testConnectivity(legacyConfig(baseUrl, apiKey, model), transport)

    /**
     * 发起底层 HTTP 探测并解析状态码与耗时。
     */
    private suspend fun sendProbeRequest(
        request: DemoHttpRequest,
        transport: DemoHttpTransport,
        startTime: Long
    ): ApiConnectivityResult {
        try {
            val code = transport.request(request).statusCode
            val latency = System.currentTimeMillis() - startTime

            return when (code) {
                in 200..299 -> ApiConnectivityResult(
                    success = true,
                    latencyMs = latency,
                    message = "通信正常 (延迟: ${latency}ms)",
                    httpCode = code
                )
                401 -> ApiConnectivityResult(
                    success = false,
                    latencyMs = latency,
                    message = "认证失败 (HTTP 401): API Key 无效或过期",
                    httpCode = code
                )
                403 -> ApiConnectivityResult(
                    success = false,
                    latencyMs = latency,
                    message = "访问受限 (HTTP 403): 权限不足或地域受限",
                    httpCode = code
                )
                404 -> ApiConnectivityResult(
                    success = false,
                    latencyMs = latency,
                    message = "端点不存在 (HTTP 404): 请检查 URL 地址是否正确",
                    httpCode = code
                )
                429 -> ApiConnectivityResult(
                    success = false,
                    latencyMs = latency,
                    message = "请求受限 (HTTP 429): 额度用尽或触发并发上限",
                    httpCode = code
                )
                in 500..599 -> ApiConnectivityResult(
                    success = false,
                    latencyMs = latency,
                    message = "服务异常 (HTTP $code): 供应商服务器出现内部故障",
                    httpCode = code
                )
                else -> ApiConnectivityResult(
                    success = false,
                    latencyMs = latency,
                    message = "请求返回异常状态 (HTTP $code)",
                    httpCode = code
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val detail = when {
                e is java.net.SocketTimeoutException -> "连接超时 (${latency}ms)"
                e is java.net.UnknownHostException -> "无法解析域名主机 (${e.message})"
                e is java.net.ConnectException -> "连接被拒绝 (${e.message})"
                else -> e.message ?: "网络连接异常"
            }
            return ApiConnectivityResult(
                success = false,
                latencyMs = latency,
                message = "连接失败: $detail"
            )
        }
    }

    private fun buildProbeRequest(profile: ProviderProfile): DemoHttpRequest {
        val body = buildJsonObject {
            put("model", profile.config.model)
            put("max_tokens", 1)
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }.toString()

        return when (profile.resolvedProtocol) {
            ProviderProtocol.ANTHROPIC_MESSAGES -> DemoHttpRequest(
                method = "POST",
                url = profile.probeEndpoint,
                headers = mapOf(
                    "x-api-key" to profile.config.apiKey,
                    "anthropic-version" to "2023-06-01",
                    "content-type" to "application/json"
                ),
                body = body
            )

            ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> DemoHttpRequest(
                method = "POST",
                url = profile.probeEndpoint,
                headers = mapOf(
                    "Authorization" to "Bearer ${profile.config.apiKey}",
                    "Content-Type" to "application/json"
                ),
                body = body
            )

            ProviderProtocol.AUTO -> error("ProviderProfile must contain a resolved protocol")
        }
    }

    /**
     * 自动识别平台并查询剩余额度或用量。
     */
    suspend fun queryBalance(
        config: ApiProviderConfig,
        transport: DemoHttpTransport = JavaNetDemoHttpTransport(
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 10_000
        )
    ): ApiBalanceResult = withContext(Dispatchers.IO) {
        val profile = ProviderProfile.from(config)
        when (profile.vendor) {
            QuotaProviderType.DEEPSEEK -> queryDeepSeekBalance(profile, transport)
            QuotaProviderType.SILICONFLOW -> querySiliconFlowBalance(profile, transport)
            QuotaProviderType.MOONSHOT -> queryMoonshotBalance(profile, transport)
            QuotaProviderType.OPENROUTER -> queryOpenRouterBalance(profile, transport)
            QuotaProviderType.UNKNOWN -> ApiBalanceResult(
                supported = false,
                provider = profile.vendor,
                balanceText = null,
                error = "该平台未开放公开余额查询接口"
            )
        }
    }

    /** Compatibility overload for callers that have not migrated to a profile. */
    suspend fun queryBalance(
        baseUrl: String,
        apiKey: String,
        transport: DemoHttpTransport = JavaNetDemoHttpTransport(
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 10_000
        )
    ): ApiBalanceResult = queryBalance(legacyConfig(baseUrl, apiKey), transport)

    /**
     * 综合执行连通性与额度检测。
     */
    suspend fun testAndQuery(
        config: ApiProviderConfig,
        transport: DemoHttpTransport = JavaNetDemoHttpTransport(
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 10_000
        )
    ): ApiTestSummary = withContext(Dispatchers.IO) {
        val connectivity = testConnectivity(config, transport)
        val balance = if (connectivity.success) {
            queryBalance(config, transport)
        } else {
            null
        }
        ApiTestSummary(connectivity, balance)
    }

    /** Compatibility overload for callers that have not migrated to a profile. */
    suspend fun testAndQuery(
        baseUrl: String,
        apiKey: String,
        model: String,
        transport: DemoHttpTransport = JavaNetDemoHttpTransport(
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 10_000
        )
    ): ApiTestSummary = testAndQuery(legacyConfig(baseUrl, apiKey, model), transport)

    private fun legacyConfig(
        baseUrl: String,
        apiKey: String = "",
        model: String = ""
    ): ApiProviderConfig = ApiProviderConfig(
        id = "legacy-provider",
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model
    )

    // ── DeepSeek 余额查询 ────────────────────────────────────────────────────────
    // GET https://api.deepseek.com/user/balance
    // Response: { "is_available": true, "balance_infos": [{ "currency": "CNY", "total_balance": "10.00", ... }] }
    fun parseDeepSeekBalanceResponse(body: String): ApiBalanceResult {
        return runCatching {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val infos = root["balance_infos"]?.jsonArray
            val first = infos?.firstOrNull()?.jsonObject
            if (first != null) {
                val currency = first["currency"]?.jsonPrimitive?.contentOrNull ?: "CNY"
                val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else "￥"
                val total = first["total_balance"]?.jsonPrimitive?.contentOrNull ?: "0.00"
                val granted = first["granted_balance"]?.jsonPrimitive?.contentOrNull
                val toppedUp = first["topped_up_balance"]?.jsonPrimitive?.contentOrNull

                val detail = buildString {
                    append("$symbol$total")
                    if (!toppedUp.isNullOrBlank() || !granted.isNullOrBlank()) {
                        append(" (")
                        val parts = mutableListOf<String>()
                        if (!toppedUp.isNullOrBlank()) parts.add("充值 $symbol$toppedUp")
                        if (!granted.isNullOrBlank()) parts.add("赠金 $symbol$granted")
                        append(parts.joinToString(" | "))
                        append(")")
                    }
                }
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.DEEPSEEK,
                    balanceText = detail
                )
            } else {
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.DEEPSEEK,
                    balanceText = null,
                    error = "未获取到有效余额信息"
                )
            }
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.DEEPSEEK,
                balanceText = null,
                error = it.message ?: "解析响应失败"
            )
        }
    }

    private suspend fun queryDeepSeekBalance(
        profile: ProviderProfile,
        transport: DemoHttpTransport
    ): ApiBalanceResult = queryKnownBalance(profile, transport, ::parseDeepSeekBalanceResponse)

    // ── 硅基流动 (SiliconFlow) 余额查询 ─────────────────────────────────────────
    // GET https://api.siliconflow.cn/v1/user/info
    // Response: { "code": 20000, "data": { "balance": "10.00", "totalBalance": "10.00", "chargeBalance": "0.00" } }
    fun parseSiliconFlowBalanceResponse(body: String, isUsd: Boolean = false): ApiBalanceResult {
        return runCatching {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject
            if (data != null) {
                val total = data["totalBalance"]?.jsonPrimitive?.contentOrNull
                    ?: data["balance"]?.jsonPrimitive?.contentOrNull
                    ?: "0.00"
                val charge = data["chargeBalance"]?.jsonPrimitive?.contentOrNull
                val symbol = if (isUsd) "$" else "￥"

                val detail = buildString {
                    append("$symbol$total")
                    if (!charge.isNullOrBlank()) {
                        append(" (已充值 $symbol$charge)")
                    }
                }
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.SILICONFLOW,
                    balanceText = detail
                )
            } else {
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.SILICONFLOW,
                    balanceText = null,
                    error = "未获取到用户信息"
                )
            }
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.SILICONFLOW,
                balanceText = null,
                error = it.message ?: "解析响应失败"
            )
        }
    }

    private suspend fun querySiliconFlowBalance(
        profile: ProviderProfile,
        transport: DemoHttpTransport
    ): ApiBalanceResult = queryKnownBalance(profile, transport) { body ->
        parseSiliconFlowBalanceResponse(body, profile.quotaIsUsd)
    }

    // ── Moonshot AI (Kimi) 余额查询 ────────────────────────────────────────────
    // GET https://api.moonshot.cn/v1/users/me/balance
    // Response: { "code": 0, "data": { "available_balance": 15.5, "voucher_balance": 5.5, "cash_balance": 10.0 } }
    fun parseMoonshotBalanceResponse(body: String, isUsd: Boolean = false): ApiBalanceResult {
        return runCatching {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject
            if (data != null) {
                val symbol = if (isUsd) "$" else "￥"
                val available = data["available_balance"]?.jsonPrimitive?.contentOrNull ?: "0.00"
                val cash = data["cash_balance"]?.jsonPrimitive?.contentOrNull
                val voucher = data["voucher_balance"]?.jsonPrimitive?.contentOrNull

                val detail = buildString {
                    append("$symbol$available")
                    if (!cash.isNullOrBlank() || !voucher.isNullOrBlank()) {
                        append(" (")
                        val parts = mutableListOf<String>()
                        if (!cash.isNullOrBlank()) parts.add("现金 $symbol$cash")
                        if (!voucher.isNullOrBlank()) parts.add("代金券 $symbol$voucher")
                        append(parts.joinToString(" | "))
                        append(")")
                    }
                }
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.MOONSHOT,
                    balanceText = detail
                )
            } else {
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.MOONSHOT,
                    balanceText = null,
                    error = "未获取到有效余额数据"
                )
            }
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.MOONSHOT,
                balanceText = null,
                error = it.message ?: "解析响应失败"
            )
        }
    }

    private suspend fun queryMoonshotBalance(
        profile: ProviderProfile,
        transport: DemoHttpTransport
    ): ApiBalanceResult = queryKnownBalance(profile, transport) { body ->
        parseMoonshotBalanceResponse(body, profile.quotaIsUsd)
    }

    // ── OpenRouter 用量与余额查询 ───────────────────────────────────────────────
    // GET https://openrouter.ai/api/v1/auth/key
    // Response: { "data": { "limit": 10.0, "usage": 2.5, "limit_remaining": 7.5, ... } }
    fun parseOpenRouterBalanceResponse(body: String): ApiBalanceResult {
        return runCatching {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject
            if (data != null) {
                val limit = data["limit"]?.jsonPrimitive?.contentOrNull
                val usage = data["usage"]?.jsonPrimitive?.contentOrNull ?: "0"
                val remaining = data["limit_remaining"]?.jsonPrimitive?.contentOrNull

                val detail = buildString {
                    if (!remaining.isNullOrBlank()) {
                        append("$$remaining 可用")
                    }
                    if (!limit.isNullOrBlank()) {
                        append(" / 限额 $$limit")
                    }
                    append(" (已用: $$usage)")
                }
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.OPENROUTER,
                    balanceText = detail
                )
            } else {
                ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.OPENROUTER,
                    balanceText = null,
                    error = "未获取到 Key 额度信息"
                )
            }
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.OPENROUTER,
                balanceText = null,
                error = it.message ?: "解析响应失败"
            )
        }
    }

    private suspend fun queryOpenRouterBalance(
        profile: ProviderProfile,
        transport: DemoHttpTransport
    ): ApiBalanceResult = queryKnownBalance(profile, transport, ::parseOpenRouterBalanceResponse)

    private suspend fun queryKnownBalance(
        profile: ProviderProfile,
        transport: DemoHttpTransport,
        parse: (String) -> ApiBalanceResult
    ): ApiBalanceResult {
        val endpoint = profile.quotaEndpoint ?: return ApiBalanceResult(
            supported = false,
            provider = profile.vendor,
            balanceText = null,
            error = "该平台未开放公开余额查询接口"
        )
        return try {
            val response = transport.request(
                DemoHttpRequest(
                    method = "GET",
                    url = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer ${profile.config.apiKey}",
                        "Accept" to "application/json"
                    )
                )
            )
            if (response.statusCode !in 200..299) {
                ApiBalanceResult(
                    supported = true,
                    provider = profile.vendor,
                    balanceText = null,
                    error = "查询失败 (HTTP ${response.statusCode})"
                )
            } else {
                parse(response.body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiBalanceResult(
                supported = true,
                provider = profile.vendor,
                balanceText = null,
                error = e.message ?: "网络请求失败"
            )
        }
    }
}
