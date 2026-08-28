package com.ugk.pi.android.testapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * API 供应商枚举（借鉴 cc-switch services/balance.rs 架构设计）。
 */
enum class QuotaProviderType(val displayName: String) {
    DEEPSEEK("DeepSeek"),
    SILICONFLOW("硅基流动"),
    MOONSHOT("Moonshot/Kimi"),
    OPENROUTER("OpenRouter"),
    UNKNOWN("通用/未知平台")
}

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

    /**
     * 根据 Base URL 自动嗅探识别供应商（参照 cc-switch detect_provider 逻辑）。
     */
    fun detectProvider(baseUrl: String): QuotaProviderType {
        val lower = baseUrl.lowercase()
        return when {
            lower.contains("api.deepseek.com") -> QuotaProviderType.DEEPSEEK
            lower.contains("siliconflow.cn") || lower.contains("siliconflow.com") -> QuotaProviderType.SILICONFLOW
            lower.contains("moonshot.cn") || lower.contains("moonshot.ai") -> QuotaProviderType.MOONSHOT
            lower.contains("openrouter.ai") -> QuotaProviderType.OPENROUTER
            else -> QuotaProviderType.UNKNOWN
        }
    }

    /**
     * 执行连通性与延迟测试。
     */
    suspend fun testConnectivity(
        baseUrl: String,
        apiKey: String,
        model: String
    ): ApiConnectivityResult = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val start = System.currentTimeMillis()

        // 判断是否优先使用 Anthropic 协议端点
        val isAnthropic = cleanBaseUrl.contains("anthropic") ||
                cleanBaseUrl.endsWith("/messages") ||
                cleanBaseUrl.contains("api.anthropic.com")

        if (isAnthropic) {
            val testUrl = if (cleanBaseUrl.endsWith("/messages")) cleanBaseUrl else "$cleanBaseUrl/v1/messages"
            val body = """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
            val headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json"
            )
            return@withContext sendProbeRequest("POST", testUrl, headers, body, start)
        }

        // OpenAI 兼容协议探测：先尝试轻量 GET /models 或直接 POST /chat/completions
        val modelsUrl = if (cleanBaseUrl.endsWith("/v1")) "$cleanBaseUrl/models" else "$cleanBaseUrl/v1/models"
        val getHeaders = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json"
        )
        val getResult = sendProbeRequest("GET", modelsUrl, getHeaders, null, start)
        if (getResult.success) {
            return@withContext getResult
        }

        // 若 /models 返回 404 或不支持 GET，回退尝试 1 token chat completion
        val chatUrl = if (cleanBaseUrl.endsWith("/v1")) {
            "$cleanBaseUrl/chat/completions"
        } else if (cleanBaseUrl.endsWith("/chat/completions")) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/v1/chat/completions"
        }

        val chatBody = """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
        val chatHeaders = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        return@withContext sendProbeRequest("POST", chatUrl, chatHeaders, chatBody, start)
    }

    /**
     * 发起底层 HTTP 探测并解析状态码与耗时。
     */
    private fun sendProbeRequest(
        method: String,
        urlString: String,
        headers: Map<String, String>,
        body: String?,
        startTime: Long
    ): ApiConnectivityResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }

            val code = connection.responseCode
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
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 自动识别平台并查询剩余额度或用量。
     */
    suspend fun queryBalance(
        baseUrl: String,
        apiKey: String
    ): ApiBalanceResult = withContext(Dispatchers.IO) {
        val provider = detectProvider(baseUrl)
        when (provider) {
            QuotaProviderType.DEEPSEEK -> queryDeepSeekBalance(apiKey)
            QuotaProviderType.SILICONFLOW -> querySiliconFlowBalance(baseUrl, apiKey)
            QuotaProviderType.MOONSHOT -> queryMoonshotBalance(baseUrl, apiKey)
            QuotaProviderType.OPENROUTER -> queryOpenRouterBalance(apiKey)
            QuotaProviderType.UNKNOWN -> ApiBalanceResult(
                supported = false,
                provider = provider,
                balanceText = null,
                error = "该平台未开放公开余额查询接口"
            )
        }
    }

    /**
     * 综合执行连通性与额度检测。
     */
    suspend fun testAndQuery(
        baseUrl: String,
        apiKey: String,
        model: String
    ): ApiTestSummary = withContext(Dispatchers.IO) {
        val connectivity = testConnectivity(baseUrl, apiKey, model)
        val balance = if (connectivity.success) {
            queryBalance(baseUrl, apiKey)
        } else {
            null
        }
        ApiTestSummary(connectivity, balance)
    }

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

    private fun queryDeepSeekBalance(apiKey: String): ApiBalanceResult {
        return runCatching {
            val url = URL("https://api.deepseek.com/user/balance")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                return ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.DEEPSEEK,
                    balanceText = null,
                    error = "查询失败 (HTTP ${connection.responseCode})"
                )
            }
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseDeepSeekBalanceResponse(body)
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.DEEPSEEK,
                balanceText = null,
                error = it.message ?: "网络请求失败"
            )
        }
    }

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

    private fun querySiliconFlowBalance(baseUrl: String, apiKey: String): ApiBalanceResult {
        return runCatching {
            val isUsd = baseUrl.lowercase().contains("siliconflow.com")
            val endpoint = if (isUsd) {
                "https://api.siliconflow.com/v1/user/info"
            } else {
                "https://api.siliconflow.cn/v1/user/info"
            }
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                return ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.SILICONFLOW,
                    balanceText = null,
                    error = "查询失败 (HTTP ${connection.responseCode})"
                )
            }
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseSiliconFlowBalanceResponse(body, isUsd)
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.SILICONFLOW,
                balanceText = null,
                error = it.message ?: "网络请求失败"
            )
        }
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

    private fun queryMoonshotBalance(baseUrl: String, apiKey: String): ApiBalanceResult {
        return runCatching {
            val isUsd = baseUrl.lowercase().contains("moonshot.ai")
            val endpoint = if (isUsd) {
                "https://api.moonshot.ai/v1/users/me/balance"
            } else {
                "https://api.moonshot.cn/v1/users/me/balance"
            }
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                return ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.MOONSHOT,
                    balanceText = null,
                    error = "查询失败 (HTTP ${connection.responseCode})"
                )
            }
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseMoonshotBalanceResponse(body, isUsd)
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.MOONSHOT,
                balanceText = null,
                error = it.message ?: "网络请求失败"
            )
        }
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

    private fun queryOpenRouterBalance(apiKey: String): ApiBalanceResult {
        return runCatching {
            val url = URL("https://openrouter.ai/api/v1/auth/key")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                return ApiBalanceResult(
                    supported = true,
                    provider = QuotaProviderType.OPENROUTER,
                    balanceText = null,
                    error = "查询失败 (HTTP ${connection.responseCode})"
                )
            }
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseOpenRouterBalanceResponse(body)
        }.getOrElse {
            ApiBalanceResult(
                supported = true,
                provider = QuotaProviderType.OPENROUTER,
                balanceText = null,
                error = it.message ?: "网络请求失败"
            )
        }
    }
}
