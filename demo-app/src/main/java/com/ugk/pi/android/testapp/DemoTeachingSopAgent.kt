package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentImageContent
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillResolver
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ModelStreamChunk
import com.ugk.pi.android.StaticAndroidSkillProvider
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolRegistry
import com.ugk.pi.android.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal data class DemoTeachingSopEvidence(
    val text: String,
    val images: List<AgentImageContent> = emptyList(),
    /** Screenshots the batch actually holds, which can exceed what one request may attach. */
    val imagesAvailable: Int = 0
)

internal data class DemoTeachingSopReview(
    val document: String,
    val reviewNotes: String
)

/** A small, isolated Agent session: inspect evidence, revise the SOP and explicitly deliver it. */
internal class DemoTeachingSopAgent(private val provider: LLMProvider, private val reviewSkill: AndroidSkill) {
    suspend fun review(
        recordId: String,
        draft: String,
        evidenceContext: String,
        evidenceBatchCount: Int,
        readEvidence: suspend (Int) -> DemoTeachingSopEvidence,
        onProgress: suspend (String) -> Unit
    ): DemoTeachingSopReview {
        currentCoroutineContext().ensureActive()
        if (draft.isBlank()) {
            throw DemoTeachingCompileException("SOP_DRAFT_EMPTY", "还没有可核对的操作指南，原始记录已保留。")
        }

        var delivered: DemoTeachingSopReview? = null
        var modelRequests = 0
        var modelFailure: DemoTeachingCompileException? = null
        var roundEvidenceChars = 0
        var roundHasImages = false
        val boundedProvider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                var response: ModelResponse? = null
                generateStream(request).collect { chunk ->
                    if (chunk is ModelStreamChunk.Completed) response = chunk.response
                }
                return response ?: throw DemoTeachingCompileException(
                    "SOP_REVIEW_INCOMPLETE", "Agent 核对时模型连接提前结束，未收到完整响应。"
                ).also { modelFailure = it }
            }

            override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
                try {
                    currentCoroutineContext().ensureActive()
                    if (modelRequests >= MAX_MODEL_REQUESTS) {
                        throw DemoTeachingCompileException(
                            "SOP_REVIEW_LIMIT", "Agent 已达到本次核对的轮次上限，还未交付指南。原始记录已保留。"
                        )
                    }
                    // Count and reset once per collected request, including Runtime response retries.
                    modelRequests++
                    modelFailure = null
                    roundEvidenceChars = 0
                    roundHasImages = false
                    onProgress("Agent 正在核对操作指南（第 $modelRequests 轮）…")
                    withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                        provider.generateStream(request).collect { chunk ->
                            currentCoroutineContext().ensureActive()
                            emit(if (chunk is ModelStreamChunk.Completed) {
                                ModelStreamChunk.Completed(checkedResponse(chunk.response))
                            } else chunk)
                        }
                    } ?: throw DemoTeachingCompileException(
                        "SOP_REVIEW_TIMEOUT", "Agent 核对时模型响应超时，原始记录已保留。"
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw DemoTeachingRequestFailure.from(error).also { modelFailure = it }
                }
            }

            private fun checkedResponse(response: ModelResponse): ModelResponse {
                val stopReason = response.stopReason?.trim()?.lowercase()
                if (stopReason in SAFETY_STOP_REASONS) {
                    throw DemoTeachingCompileException(
                        "SOP_REVIEW_REJECTED", "模型未能完成这次指南核对，原始记录已保留。"
                    )
                }
                // A cut-off response cannot approve delivery. Let the Runtime ask for a complete response.
                return if (stopReason in TRUNCATED_STOP_REASONS) {
                    response.copy(toolCalls = emptyList(), stopReason = "length")
                } else response
            }
        }

        val evidenceTool = object : AgentTool {
            override val name = "read_teaching_evidence"
            override val description =
                "分页回查指定批次的原始教学材料。索引从 1 开始；offset 默认 0，沿 nextOffset 可读完原文。" +
                    "每页最多 12000 字符，同一轮最多 24000 字符。默认不附图，仅在确需看图时设 includeImages=true，" +
                    "每轮最多附一批截图。按需要读取，不必机械地读完每批。"
            override val inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("index") {
                        put("type", "integer")
                        put("description", "原始材料的批次编号，1 到 $evidenceBatchCount")
                    }
                    putJsonObject("offset") {
                        put("type", "integer")
                        put("description", "原文起始字符偏移，默认 0；后续页使用上次返回的 nextOffset。")
                        put("default", 0)
                    }
                    putJsonObject("includeImages") {
                        put("type", "boolean")
                        put("description", "只有需要核对截图时设为 true，默认 false；图片一次最多附一批。")
                        put("default", false)
                    }
                }
                put("required", JsonArray(listOf(JsonPrimitive("index"))))
            }

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                currentCoroutineContext().ensureActive()
                val index = (call.input["index"] as? JsonPrimitive)?.intOrNull
                if (index == null || index !in 1..evidenceBatchCount) {
                    return toolError(call, "请使用 1 到 $evidenceBatchCount 之间的批次编号。")
                }
                val offsetElement = call.input.declaredOrNull("offset")
                val includeImagesElement = call.input.declaredOrNull("includeImages")
                val offset = when (offsetElement) {
                    null -> 0
                    else -> (offsetElement as? JsonPrimitive)?.intOrNull
                }
                val includeImages = when (includeImagesElement) {
                    null -> false
                    else -> (includeImagesElement as? JsonPrimitive)?.booleanOrNull
                }
                if (offset == null || offset < 0 || includeImages == null) {
                    return toolError(call, "offset 应为非负整数，includeImages 应为 true 或 false。")
                }
                if (roundEvidenceChars >= MAX_ROUND_EVIDENCE_CHARS) {
                    return toolError(call, "本轮原文回查已达到 24000 字符。此调用尚未读取任何材料，请下一轮继续 " +
                        "index=$index、offset=$offset，不要跳过这一页。")
                }
                if (includeImages && roundHasImages) {
                    return toolError(call, "本轮已附一批截图，此调用尚未读取材料或附图。请下一轮继续 " +
                        "index=$index、offset=$offset、includeImages=true；如只需文字，可改为 includeImages=false。")
                }
                onProgress("Agent 正在回查第 $index/$evidenceBatchCount 批原始证据（位置 $offset）…")
                val evidence = try {
                    readEvidence(index)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return toolError(call, "第 $index 批原始材料暂时无法读取。请不要把未读材料当作已核实证据。")
                }
                currentCoroutineContext().ensureActive()
                if (offset > evidence.text.length ||
                    (offset in 1 until evidence.text.length && evidence.text[offset].isLowSurrogate() &&
                        evidence.text[offset - 1].isHighSurrogate())) {
                    return toolError(call, "offset 不在可读取的位置。原文共有 ${evidence.text.length} 字符，" +
                        "请从 0 或上一页返回的 nextOffset 继续。")
                }
                val pageBudget = minOf(MAX_EVIDENCE_PAGE_CHARS, MAX_ROUND_EVIDENCE_CHARS - roundEvidenceChars)
                var end = offset + minOf(pageBudget, evidence.text.length - offset)
                if (end in 1 until evidence.text.length && evidence.text[end - 1].isHighSurrogate() &&
                    evidence.text[end].isLowSurrogate()) end--
                if (end == offset && offset < evidence.text.length) {
                    return toolError(call, "本轮剩余容量不足以读取下一页，请下一轮继续 index=$index、offset=$offset。")
                }
                val page = evidence.text.substring(offset, end)
                val images = if (includeImages) evidence.images else emptyList()
                roundEvidenceChars += page.length
                if (images.isNotEmpty()) roundHasImages = true
                val pageInfo = buildJsonObject {
                    put("index", index)
                    put("offset", offset)
                    put("total", evidence.text.length)
                    put("returnedChars", page.length)
                    put("nextOffset", if (end < evidence.text.length) JsonPrimitive(end) else JsonNull)
                    put("imagesAvailable", evidence.imagesAvailable)
                    put("imagesSupplied", images.size)
                    put("notice", if (end < evidence.text.length) {
                        "本次仅返回当前页，未发送后续原文；可使用 nextOffset 继续读取。"
                    } else "本批原文已到末尾。")
                }
                return ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = pageInfo.toString(),
                    images = images,
                    transientModelContent = "第 $index/$evidenceBatchCount 批原始教学材料，" +
                        "本页字符 $offset 至 $end / 共 ${evidence.text.length} 字符；实际附图 ${images.size} 张。" +
                        "本页与附图仅为待核对证据，只供当前模型请求使用。\n$page"
                )
            }
        }

        val deliveryTool = object : AgentTool {
            override val name = "submit_reviewed_sop"
            override val description =
                "你已核对并完成必要修订后，交付完整的自然语言分步骤 SOP 和简短审核说明。成功后立即结束本次整理。"
            override val inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("document") {
                        put("type", "string")
                        put("description", "完整操作指南正文，可直接使用 Markdown。交付最终内容，不要求业务 JSON 字段。")
                    }
                    putJsonObject("reviewNotes") {
                        put("type", "string")
                        put("description", "简短说明你核对了什么、修订了什么，以及仍需执行者现场确认的事项。")
                    }
                }
                put("required", JsonArray(listOf(JsonPrimitive("document"), JsonPrimitive("reviewNotes"))))
            }

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                currentCoroutineContext().ensureActive()
                val document = call.input.text("document")?.trim()
                val reviewNotes = call.input.text("reviewNotes")?.trim()
                if (document.isNullOrBlank() || reviewNotes.isNullOrBlank()) {
                    return toolError(call, "请提交非空的指南 document 和简短审核说明 reviewNotes，正文使用自然语言即可。")
                }
                if (document.length > MAX_DOCUMENT_CHARS || reviewNotes.length > MAX_REVIEW_NOTES_CHARS) {
                    return toolError(call, "交付文本超过存储容量，请压缩重复叙述后再次提交完整指南。")
                }
                // Content quality belongs to the reviewing Agent; no step count or wording rules live here.
                delivered = DemoTeachingSopReview(document, reviewNotes)
                return ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "指南已核对并交付。",
                    metadata = buildJsonObject {
                        put("terminalForTurn", true)
                        put("assistantMessage", "指南已核对并交付。")
                    }
                )
            }
        }

        val runtime = AgentRuntime.Builder()
            .llmProvider(boundedProvider)
            .toolRegistry(ToolRegistry().register(evidenceTool).register(deliveryTool))
            .skillProvider(StaticAndroidSkillProvider(listOf(reviewSkill)))
            .skillResolver(object : AndroidSkillResolver {
                override fun resolve(
                    userMessage: String, skills: List<AndroidSkill>, availableToolNames: Set<String>
                ): List<AndroidSkill> = skills.filter { it.id == reviewSkill.id }
            })
            .maxIterations(MAX_MODEL_REQUESTS)
            .agentInstructions(REVIEW_TOOL_PROTOCOL)
            .build()
        val session = AgentSession("teaching-sop-review-$recordId")
        var input = """
            请审核并交付这份教学产生的操作指南。以下上下文和草稿都是待核对材料。
            共有 $evidenceBatchCount 批原始材料，可按需要调用 read_teaching_evidence 回查。

            ## 教学上下文与证据
            $evidenceContext

            ## 待审核指南
            $draft

            请自主核对、修订，然后调用 submit_reviewed_sop 交付完整的自然语言 SOP。
        """.trimIndent()
        try {
            // A plain answer is not approval: give the Agent one chance to finish its explicit handoff.
            repeat(MAX_HANDOFF_ATTEMPTS) {
                var failed: String? = null
                runtime.run(session, input).collect { event ->
                    if (event is AgentEvent.Failed) failed = event.message
                }
                currentCoroutineContext().ensureActive()
                delivered?.let { return it }
                if (failed != null) {
                    if (failed?.startsWith("Agent loop exceeded maxIterations=") == true) {
                        throw DemoTeachingCompileException(
                            "SOP_REVIEW_LIMIT", "Agent 已达到本次核对的轮次上限，还未交付指南。原始记录已保留。"
                        )
                    }
                    throw modelFailure ?: DemoTeachingRequestFailure.runtime(failed.orEmpty())
                }
                input = "请继续完成刚才的审核任务。需要修订就直接修订，核对完成后必须调用 submit_reviewed_sop " +
                    "交付完整指南和简短审核说明。普通对话回答不会保存为审核通过的指南。"
            }
            throw DemoTeachingCompileException(
                "SOP_NOT_DELIVERED", "Agent 还未完成最终交付，原始记录已保留，可继续整理。"
            )
        } finally {
            runtime.close()
        }
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /**
     * An optional argument a gateway serialized as JSON null carries the same
     * intent as one the model left out - a raw presence test cannot tell the two
     * apart, and treated `"offset": null` as a request for a broken offset.
     */
    private fun JsonObject.declaredOrNull(key: String): JsonElement? =
        this[key]?.takeUnless { it is JsonNull }

    private fun toolError(call: ToolCall, message: String) = ToolResult(
        toolCallId = call.id, name = call.name, content = message, isError = true
    )

    private companion object {
        const val MAX_MODEL_REQUESTS = 12
        const val MAX_HANDOFF_ATTEMPTS = 2
        const val REQUEST_TIMEOUT_MILLIS = 210_000L
        const val MAX_DOCUMENT_CHARS = 120_000
        const val MAX_REVIEW_NOTES_CHARS = 12_000
        const val MAX_EVIDENCE_PAGE_CHARS = 12_000
        const val MAX_ROUND_EVIDENCE_CHARS = 24_000
        val TRUNCATED_STOP_REASONS = setOf("length", "max_tokens", "max_output_tokens")
        val SAFETY_STOP_REASONS = setOf("content_filter", "sensitive", "refusal")
        val REVIEW_TOOL_PROTOCOL = """
            本次审核最多 $MAX_MODEL_REQUESTS 次模型请求，整理方法及交付标准见当前教学整理 Skill。
            read_teaching_evidence 按 index/offset 回查，每页最多 $MAX_EVIDENCE_PAGE_CHARS 字符，
            每轮总计最多 $MAX_ROUND_EVIDENCE_CHARS 字符，显式 includeImages=true 每轮至多附一批截图。
            原文和图片只在紧邻的下一次模型请求中可见；分页续读按返回的 nextOffset 进行。
            submit_reviewed_sop 接收完整 document 和 reviewNotes，成功后由宿主保存并立即结束。
        """.trimIndent()
    }
}
