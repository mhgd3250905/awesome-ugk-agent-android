package com.ugk.pi.android.testapp

import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Converts provider failures without retaining server prose, request bodies or exception causes. */
internal object DemoTeachingRequestFailure {
    fun from(error: Exception): DemoTeachingCompileException {
        if (error is CancellationException) throw error
        if (error is DemoTeachingCompileException) return error
        known(error.message.orEmpty())?.let { return it }
        return when (error) {
            is SocketTimeoutException -> failure("SOCKET_TIMEOUT", "模型连接或响应超时，请稍后重试。")
            is UnknownHostException -> failure("DNS_FAILED", "无法解析模型平台地址，请检查网络和接口配置。")
            is ConnectException, is NoRouteToHostException -> failure("CONNECTION_FAILED", "无法连接模型平台，请检查网络或稍后重试。")
            is SSLException -> failure("TLS_FAILED", "模型平台的安全连接失败，请检查网络和接口配置。")
            is MalformedURLException -> failure("REQUEST_CONFIG_INVALID", "模型接口地址无效，请检查配置。")
            is SocketException -> failure("CONNECTION_LOST", "与模型平台的连接已中断，请稍后重试。")
            is IOException -> failure("NETWORK_IO_FAILED", "模型请求发生网络读写错误，请检查网络后重试。")
            is SerializationException -> failure("RESPONSE_FORMAT_INVALID", "模型平台返回的响应格式无法解析。")
            else -> {
                val type = error.javaClass.simpleName.takeIf { SAFE_EXCEPTION_TYPE.matches(it) } ?: "Exception"
                failure("REQUEST_FAILED", "模型请求失败（$type），请稍后重试。", "exceptionType=$type")
            }
        }
    }

    fun http(status: Int, body: String): DemoTeachingCompileException {
        val root = if (body.length <= MAX_ERROR_BODY_CHARS) {
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        } else null
        val error = root?.get("error") as? JsonObject
        val code = safeCode(error?.text("code"))
        val type = safeCode(error?.text("type"))
        val requestId = safeRequestId(root?.text("request_id")) ?: safeRequestId(root?.text("requestId"))
        val message = error?.text("message") ?: root?.text("message").orEmpty()
        return serviceFailure(status.takeIf { it in 100..599 }, message, code, type, requestId)
    }

    fun runtime(message: String): DemoTeachingCompileException = known(message)
        ?: failure("SOP_REVIEW_FAILED", "Agent 核对尚未完成，暂未交付指南。", "runtime=unclassified")

    private fun known(raw: String): DemoTeachingCompileException? {
        val message = raw.trim()
        HTTP_FAILURE.matchEntire(message)?.let {
            return http(it.groupValues[1].toInt(), it.groupValues[2])
        }
        SERVICE_PREFIXES.firstOrNull { message.startsWith(it) }?.let { prefix ->
            return serviceFailure(null, message.removePrefix(prefix))
        }
        return when {
            message.startsWith("Agent loop exceeded maxIterations=") ->
                failure("SOP_REVIEW_LIMIT", "Agent 已达到本次核对的轮次上限，还未交付指南。")
            message == "Model returned an incomplete final response three consecutive times." ->
                failure("SOP_RESPONSE_INCOMPLETE", "模型连续返回空白或未完成内容，Agent 尚未交付指南。")
            message == "Model response contained duplicate tool call ids." ->
                failure("MODEL_TOOL_CALL_INVALID", "模型返回了重复的工具调用标识，无法继续核对。")
            message.startsWith("SSE stream ended an event with an unparsable data payload:") ||
                message.startsWith("OpenAI response missing choices[0]") ->
                failure("RESPONSE_FORMAT_INVALID", "模型平台返回的响应格式无法解析。")
            message == "模型响应流异常中断，未获取到完整响应" ->
                failure("RESPONSE_STREAM_INTERRUPTED", "模型响应流在完成前中断，请稍后重试。")
            message.startsWith("HTTP response exceeds maxResponseBytes=") ||
                message.startsWith("HTTP response line exceeds maxResponseBytes=") ||
                message.startsWith("HTTP stream exceeds maxStreamedBytes=") ->
                failure("RESPONSE_TOO_LARGE", "模型平台返回的响应超过接收容量。")
            message.startsWith("Transcript preparation failed:") ->
                failure("SOP_REVIEW_CONTEXT_FAILED", "Agent 的审核上下文准备失败，暂未交付指南。")
            message.startsWith("Skill assembly failed:") ->
                failure("SOP_REVIEW_SETUP_FAILED", "Agent 的审核能力初始化失败，暂未交付指南。")
            message == "Run input is empty." ->
                failure("SOP_REVIEW_INPUT_EMPTY", "没有可供 Agent 核对的内容。")
            message.startsWith("AgentSession '") && message.endsWith("' is already running.") ->
                failure("SOP_REVIEW_BUSY", "已有 Agent 核对任务正在运行，请等待结束。")
            else -> null
        }
    }

    private fun serviceFailure(
        status: Int?, message: String, code: String? = null, type: String? = null, requestId: String? = null
    ): DemoTeachingCompileException {
        // Message text is inspected only to select fixed wording. It is never returned or persisted.
        val hint = listOfNotNull(code, type, message.take(2_000)).joinToString(" ").lowercase(Locale.ROOT)
        fun has(vararg words: String) = words.any { hint.contains(it) }
        val reason = when {
            has("insufficient_balance", "insufficient balance", "balance is insufficient", "insufficient credits", "credit balance is too low", "余额不足", "余额耗尽", "余额已用尽") ->
                "SERVICE_BALANCE_EXHAUSTED" to "模型平台报告余额不足，请检查账户余额。"
            has("insufficient_quota", "quota_exceeded", "quota exceeded", "quota exhausted", "exceeded your current quota", "额度不足", "额度已用尽", "额度耗尽", "配额用尽") ->
                "SERVICE_QUOTA_EXHAUSTED" to "模型平台报告可用额度不足，请检查账户额度。"
            has("context_length_exceeded", "maximum context length", "context window", "context length", "too many tokens", "prompt is too long", "prompt too long", "上下文过长", "上下文长度超", "输入长度超") ->
                "SERVICE_CONTEXT_TOO_LONG" to "本次审核材料超过模型的上下文容量，需要缩小本轮材料。"
            status == 413 || has("request too large", "payload too large", "请求体过大") ->
                "SERVICE_REQUEST_TOO_LARGE" to "本次请求内容超过模型平台允许的大小。"
            status == 401 || has("invalid_api_key", "invalid api key", "authentication_error", "authentication failed", "unauthorized", "鉴权失败", "认证失败", "密钥无效") ->
                "SERVICE_AUTH_FAILED" to "模型平台拒绝认证，请检查当前 API 配置。"
            status == 403 || has("permission_denied", "permission denied", "forbidden", "没有权限", "无权使用", "未开通") ->
                "SERVICE_PERMISSION_DENIED" to "当前账户没有访问该模型或功能的权限，请检查模型权限。"
            has("rate_limit", "rate limit", "too many requests", "请求过于频繁", "并发超限", "并发数超", "频率限制") ->
                "SERVICE_RATE_LIMITED" to "模型平台限制了请求频率或并发数量，请稍后重试。"
            status == 429 ->
                "SERVICE_LIMITED" to "模型平台限制了本次请求，请检查调用频率与可用额度。"
            status != null && status >= 500 ->
                "SERVICE_UNAVAILABLE" to "模型平台暂时无法处理请求，请稍后重试。"
            status == 404 ->
                "SERVICE_NOT_FOUND" to "模型或接口不存在，请检查模型名称和接口配置。"
            has("content_filter", "content policy", "safety policy", "内容审核", "安全策略") ->
                "SERVICE_CONTENT_BLOCKED" to "模型平台因内容安全策略拒绝了本次请求。"
            has("does not support tools", "tools not supported", "does not support image", "unsupported tool", "不支持工具", "不支持图像", "不支持图片") ->
                "SERVICE_CAPABILITY_UNSUPPORTED" to "当前模型或接口不支持本次请求所需的工具或图像能力。"
            status == 400 || status == 422 || has("invalid_request", "invalid parameter", "invalid_param", "参数错误", "参数不合法") ->
                "SERVICE_REQUEST_INVALID" to "模型平台拒绝了本次请求参数，请检查模型与接口配置。"
            else -> "SERVICE_REQUEST_FAILED" to "模型平台未能完成本次请求，请稍后重试。"
        }
        val identifiers = buildList {
            status?.let { add("HTTP $it") }
            (code ?: type)?.let { add("服务错误码 $it") }
        }.joinToString("；")
        val detail = buildList {
            status?.let { add("httpStatus=$it") }
            code?.let { add("serviceCode=$it") }
            type?.let { add("serviceType=$it") }
            requestId?.let { add("requestId=$it") }
        }.joinToString(";")
        return failure(reason.first, reason.second + if (identifiers.isEmpty()) "" else "（$identifiers）", detail)
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.let {
        if (it.isString || it.content.all(Char::isDigit)) it.content else null
    }

    private fun safeCode(value: String?): String? = value?.takeIf {
        SAFE_CODE.matches(it) && !it.startsWith("sk-", true) && !it.startsWith("sk_", true) &&
            !it.startsWith("eyJ") && !OPAQUE_HEX.matches(it)
    }

    private fun safeRequestId(value: String?): String? = value?.takeIf {
        it.length <= 64 && (NUMERIC_REQUEST_ID.matches(it) || UUID_REQUEST_ID.matches(it) ||
            PREFIXED_REQUEST_ID.matches(it))
    }

    private fun failure(code: String, message: String, detail: String = "") =
        DemoTeachingCompileException(code, "$message 原始教学记录已保留。", detail)

    private const val MAX_ERROR_BODY_CHARS = 64_000
    private val HTTP_FAILURE = Regex("^(?:HTTP request failed|OpenAI chat completions request failed|Anthropic messages request failed):\\s*([1-5][0-9]{2})(?:\\s+([\\s\\S]*))?$")
    private val SERVICE_PREFIXES = listOf("OpenAI API error:", "Anthropic API error:", "OpenAI stream error:", "Anthropic SSE stream error:")
    private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val SAFE_CODE = Regex("(?:[A-Za-z][A-Za-z0-9_.-]{0,39}|[0-9]{1,20})")
    private val OPAQUE_HEX = Regex("[a-fA-F0-9]{24,}")
    private val NUMERIC_REQUEST_ID = Regex("[0-9][0-9_-]{0,63}")
    private val UUID_REQUEST_ID = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
    private val PREFIXED_REQUEST_ID = Regex("(?:req|request|trace)[_-][A-Za-z0-9_-]+", RegexOption.IGNORE_CASE)
}
