package com.ugk.pi.android.testapp

import com.ugk.pi.android.ModelResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Text response completeness plus legacy JSON decoding; SOP content has no required business schema. */
internal object DemoTeachingResponseParser {
    fun requireComplete(response: ModelResponse) {
        val reason = DemoModelStopReasons.normalized(response.stopReason)
        if (reason != null && reason in DemoModelStopReasons.truncated) {
            fail("OUTPUT_TRUNCATED", "模型达到输出上限（$reason），返回内容已截断，原始教学记录已保留",
                "stopReason=$reason")
        }
        if (reason != null && reason in DemoModelStopReasons.safety) {
            fail("OUTPUT_BLOCKED", "模型因安全策略停止输出（$reason），本次结果不能保存为教学经验",
                "stopReason=$reason")
        }
        if (response.toolCalls.isNotEmpty()) {
            fail("UNEXPECTED_TOOL_CALLS", "模型返回了操作调用，不能保存为教学经验",
                "toolCalls.count=${response.toolCalls.size}")
        }
        checkRawContent(response.content)
    }

    fun step(content: String): JsonObject {
        val obj = objectContent(content)
        checkFields(obj, STEP_FIELDS, emptySet(), "步骤摘要")
        val normalized = obj.toMutableMap()
        STEP_FIELDS.forEach { field ->
            normalized[field] = stringArray(obj.getValue(field), field, maxItems = 100,
                requiredNonEmpty = field == "facts")
        }
        return JsonObject(normalized).also { checkCompactLength(it, 12_000, "步骤摘要") }
    }

    fun guide(content: String): DemoTeachingGuide {
        val obj = objectContent(content)
        checkFields(obj, GUIDE_REQUIRED_FIELDS, GUIDE_OPTIONAL_FIELDS, "最佳实践")
        listOf("title", "goal").forEach { field ->
            stringValue(obj.getValue(field), field, maxChars = 1_000)
        }
        val normalized = obj.toMutableMap()
        (GUIDE_ARRAY_FIELDS + GUIDE_OPTIONAL_FIELDS.filter { it in obj }).forEach { field ->
            normalized[field] = stringArray(obj.getValue(field), field, maxItems = 60,
                requiredNonEmpty = field == "steps", maxItemChars = 1_200)
        }
        val result = JsonObject(normalized)
        checkCompactLength(result, 24_000, "最佳实践")
        return DemoTeachingStore.decodeGuide(result)
    }

    private fun objectContent(content: String): JsonObject {
        checkRawContent(content)
        val outer = jsonValue(unwrapFence(content.trim()))
        // Decode at most one JSON string envelope; never search prose for an apparent JSON fragment.
        val value = if (outer is JsonPrimitive && outer.isString) jsonValue(outer.content.trim()) else outer
        return value as? JsonObject
            ?: fail("FIELD_TYPE", "模型返回的顶层内容应为 JSON 对象", "content")
    }

    private fun checkRawContent(content: String) {
        if (content.length > MAX_RAW_CHARS) {
            fail("RAW_OUTPUT_TOO_LARGE", "模型返回的原始文本超过安全长度上限",
                "content.length=${content.length};limit=$MAX_RAW_CHARS")
        }
        if (content.isBlank()) {
            fail("EMPTY_CONTENT", "模型没有返回整理正文，原始教学记录已保留", "content.length=${content.length}")
        }
    }

    private fun unwrapFence(content: String): String {
        if (!content.startsWith("```")) return content
        val lines = content.lines()
        if (!FENCE_OPENING.matches(lines.first().trimEnd())) {
            fail("JSON_FENCE_FORMAT", "模型返回的代码围栏不是单个 JSON 包装", "content")
        }
        val closing = lines.indexOfLast { it.trimEnd() == "```" }
        if (closing <= 0) {
            fail("JSON_FENCE_UNCLOSED", "模型返回的 JSON 围栏未闭合，不能保存", "content")
        }
        if (closing != lines.lastIndex) {
            fail("JSON_FENCE_FORMAT", "模型在 JSON 围栏之外返回了额外内容", "content")
        }
        val body = lines.subList(1, closing).joinToString("\n").trim()
        if (body.lineSequence().any { it.trimStart().startsWith("```") }) {
            fail("JSON_FENCE_FORMAT", "模型返回了多个或嵌套的 JSON 围栏", "content")
        }
        return body
    }

    private fun jsonValue(content: String): JsonElement {
        if (content.isBlank()) {
            fail("EMPTY_CONTENT", "模型没有返回整理正文，原始教学记录已保留", "content.length=${content.length}")
        }
        return try {
            Json.parseToJsonElement(content)
        } catch (_: SerializationException) {
            // Parser exception messages can contain response excerpts; do not retain them as causes/details.
            fail("JSON_SYNTAX", "模型返回的 JSON 语法不正确，不能保存", "content")
        }
    }

    private fun checkFields(obj: JsonObject, required: Set<String>, optional: Set<String>, label: String) {
        val missing = required.filter { it !in obj }
        if (missing.isNotEmpty()) {
            fail("MISSING_FIELDS", "模型返回的${label}缺少必要字段",
                "missing=${missing.joinToString(",")};count=${missing.size}")
        }
        val extraCount = obj.keys.count { it !in required && it !in optional }
        if (extraCount > 0) {
            fail("UNKNOWN_FIELDS", "模型返回的${label}包含不支持的额外字段", "content.extraFieldCount=$extraCount")
        }
    }

    private fun stringArray(
        value: JsonElement, path: String, maxItems: Int, requiredNonEmpty: Boolean, maxItemChars: Int? = null
    ): JsonArray {
        val items = value as? JsonArray
            ?: fail("FIELD_TYPE", "模型返回的字段类型不正确，应为文字数组", path)
        if (items.size > maxItems) {
            fail("ARRAY_TOO_LARGE", "模型返回的数组条目数量超过上限", "$path.count=${items.size};limit=$maxItems")
        }
        if (requiredNonEmpty && items.isEmpty()) {
            fail("EMPTY_REQUIRED_ARRAY", "模型没有返回必要的步骤或事实", "$path.count=0")
        }
        return JsonArray(items.mapIndexed { index, item ->
            val itemPath = "$path[$index]"
            if (item is JsonObject) {
                // A bare {"text": "..."} adds no facts. Any other field might, so it cannot be discarded.
                val extraCount = item.keys.count { it != "text" }
                if (extraCount > 0) {
                    fail("UNKNOWN_FIELDS", "模型返回的条目包含不支持的附加字段", "$itemPath.extraFieldCount=$extraCount")
                }
                val text = item["text"]
                    ?: fail("MISSING_FIELDS", "模型返回的文字条目缺少必要字段", "$itemPath.text")
                stringValue(text, "$itemPath.text", maxItemChars)
            } else {
                stringValue(item, itemPath, maxItemChars)
            }
        })
    }

    private fun stringValue(value: JsonElement, path: String, maxChars: Int?): JsonPrimitive {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }
            ?: fail("FIELD_TYPE", "模型返回的字段类型不正确，应为文字", path)
        if (text.content.isBlank()) {
            fail("EMPTY_TEXT", "模型返回了空白的必要文字", path)
        }
        if (maxChars != null && text.content.length > maxChars) {
            fail("TEXT_TOO_LONG", "模型返回的单条文字超过长度上限", "$path.length=${text.content.length};limit=$maxChars")
        }
        return text
    }

    private fun checkCompactLength(obj: JsonObject, maxChars: Int, label: String) {
        val length = obj.toString().length
        if (length > maxChars) {
            fail("OUTPUT_TOO_LARGE", "模型返回的${label}超过整理长度上限", "content.compactLength=$length;limit=$maxChars")
        }
    }

    private fun fail(code: String, message: String, detail: String): Nothing =
        throw DemoTeachingCompileException(code, message, detail)

    private const val MAX_RAW_CHARS = 120_000
    private val FENCE_OPENING = Regex("```(?:json)?[ \\t]*", RegexOption.IGNORE_CASE)
    private val STEP_FIELDS = linkedSetOf("facts", "corrections", "completionEvidence", "uncertainties")
    private val GUIDE_REQUIRED_FIELDS = linkedSetOf("title", "goal", "prerequisites", "steps", "corrections", "completionChecks", "uncertainties")
    private val GUIDE_OPTIONAL_FIELDS = linkedSetOf("intentAliases", "targetApps", "notApplicable")
    private val GUIDE_ARRAY_FIELDS = listOf("prerequisites", "steps", "corrections", "completionChecks", "uncertainties")
}

internal class DemoTeachingCompileException(
    val code: String,
    override val message: String,
    val detail: String = ""
) : IllegalStateException(message)
