package com.ugk.pi.android.testapp

import kotlinx.serialization.json.*

/** Builds ordered evidence without truncating teaching content or changing the durable record. */
internal object DemoTeachingEvidencePreparation {
    fun prepare(record: DemoTeachingRecord): PreparedTeachingEvidence {
        val parts = mutableListOf<TeachingEvidencePart>()
        var denoised = false
        record.segments.forEachIndexed { segmentIndex, segment ->
            val segmentNumber = segmentIndex + 1
            parts += TeachingEvidencePart(segmentNumber, null, buildJsonObject {
                put("kind", "segment")
                put("segmentNumber", segmentNumber)
                if (segmentIndex == 0) {
                    put("teachingTitle", record.title)
                    put("teachingStatus", record.status)
                }
                put("instruction", segment.instruction)
                put("status", segment.status)
                put("reply", segment.reply)
            }.toString())

            segment.actions.forEachIndexed { actionIndex, action ->
                val actionNumber = actionIndex + 1
                val result = prepareResult(action)
                denoised = denoised || result.denoised
                parts += TeachingEvidencePart(
                    segmentNumber = segmentNumber,
                    actionNumber = actionNumber,
                    content = buildJsonObject {
                        put("kind", "action")
                        put("segmentNumber", segmentNumber)
                        put("actionNumber", actionNumber)
                        put("name", action.name)
                        put("input", action.input)
                        put("isError", action.isError?.let(::JsonPrimitive) ?: JsonNull)
                        // Each part carries its own evidence so batching cannot leave dangling references.
                        put("result", result.content)
                        result.elementPackageDefault?.let { put("elementPackageDefault", it) }
                        if ((action.result?.length ?: 0) >= LEGACY_RESULT_CHAR_LIMIT) {
                            put("possibleIncompleteResult", true)
                            put("resultEvidenceNotice", "已保存的返回文字达到历史记录的12,000字符限长，可能曾在保存时截断；现有文字完整保留，缺失内容不可推断。")
                        }
                        action.beforeImage?.let { put("beforeImage", it) }
                        action.afterImage?.let { put("afterImage", it) }
                        put("gaps", JsonArray(action.gaps.map(::JsonPrimitive)))
                    }.toString(),
                    imageNames = listOfNotNull(action.beforeImage, action.afterImage).distinct()
                )
            }
        }
        return PreparedTeachingEvidence(parts, denoised)
    }

    private fun prepareResult(action: DemoTeachingAction): PreparedResult {
        val raw = action.result ?: return PreparedResult(JsonNull)
        val unchanged = PreparedResult(JsonPrimitive(raw))
        if (action.isError != false || action.name !in SCREEN_TOOLS) return unchanged
        val payload = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return unchanged
        // An inconsistent status must remain visible as originally recorded.
        if (payload["success"]?.let { it != JsonPrimitive(true) } == true ||
            payload["code"]?.let { it != JsonPrimitive("OK") } == true) return unchanged

        return when (action.name) {
            "screen_read_ui_tree" -> prepareTree(payload, "elements") ?: unchanged
            "screen_find_ui_element" -> prepareTree(payload, "matches") ?: unchanged
            "screen_capture_visual" -> {
                if (!payload.hasString("observationId") || !payload.hasString("package") ||
                    payload["imageAttached"] != JsonPrimitive(true)) return unchanged
                val clean = payload.toMutableMap()
                if (payload.hasString("sessionId")) clean.remove("sessionId")
                if ((payload["capturedAtEpochMillis"] as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } == true) {
                    clean.remove("capturedAtEpochMillis")
                }
                PreparedResult(JsonObject(clean), denoised = clean.size != payload.size)
            }
            else -> {
                if (payload["success"] != JsonPrimitive(true) || payload["code"] != JsonPrimitive("OK")) return unchanged
                val clean = payload.toMutableMap()
                if (payload.hasString("gestureId")) clean.remove("gestureId")
                // Keep dispatch/effect verification, error recovery and screen-change evidence intact.
                PreparedResult(JsonObject(clean), denoised = clean.size != payload.size)
            }
        }
    }

    private fun prepareTree(payload: JsonObject, elementKey: String): PreparedResult? {
        if (!payload.hasString("snapshotId") || !payload.hasString("package")) return null
        val elements = payload[elementKey] as? JsonArray ?: return null
        // A shared default would misrepresent nodes that originally omitted their package.
        val packagesAreExplicit = elements.all { it is JsonObject && it.hasString("package") }
        val pagePackage = payload.getValue("package")
        var inheritedPackage = false
        val compactElements = elements.map { element ->
            if (packagesAreExplicit && element is JsonObject && element["package"] == pagePackage) {
                inheritedPackage = true
                JsonObject(element.filterKeys { it != "package" })
            } else element
        }
        // Retain every node, its order/path, labels, states, geometry, actions and unknown fields.
        val clean = if (inheritedPackage) JsonObject(payload + (elementKey to JsonArray(compactElements))) else payload
        return PreparedResult(
            content = clean,
            denoised = inheritedPackage,
            elementPackageDefault = pagePackage.jsonPrimitive.content.takeIf { inheritedPackage }
        )
    }

    private fun JsonObject.hasString(key: String): Boolean = (get(key) as? JsonPrimitive)?.isString == true

    private data class PreparedResult(
        val content: JsonElement,
        val denoised: Boolean = false,
        val elementPackageDefault: String? = null
    )

    private const val LEGACY_RESULT_CHAR_LIMIT = 12_000
    private val TREE_OBSERVATION_TOOLS = setOf("screen_read_ui_tree", "screen_find_ui_element")
    private val SCREEN_TOOLS = TREE_OBSERVATION_TOOLS + setOf(
        "screen_capture_visual", "screen_perform_action", "screen_visual_gesture",
        "screen_gesture", "screen_press_key", "screen_global_action"
    )
}

internal data class PreparedTeachingEvidence(val parts: List<TeachingEvidencePart>, val denoised: Boolean)

internal data class TeachingEvidencePart(
    val segmentNumber: Int,
    val actionNumber: Int?,
    val content: String,
    val imageNames: List<String> = emptyList()
)
