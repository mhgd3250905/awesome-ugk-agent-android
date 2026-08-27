package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ModelRequest(
    val sessionId: String,
    val messages: List<AgentMessage>,
    val tools: List<AgentToolDefinition>
)

data class ModelResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val stopReason: String? = null,
    val reasoningContent: String? = null
)

/**
 * 流式模型响应分片。
 */
sealed class ModelStreamChunk {
    /** 思考链（Reasoning / CoT）流式字符增量 */
    data class ThinkingDelta(val delta: String) : ModelStreamChunk()

    /** 模型正文内容（Content）流式字符增量 */
    data class ContentDelta(val delta: String) : ModelStreamChunk()

    /** 流式输出完成，携带拼接完整的最终 ModelResponse */
    data class Completed(val response: ModelResponse) : ModelStreamChunk()
}

interface LLMProvider {
    suspend fun generate(request: ModelRequest): ModelResponse

    /**
     * 流式请求接口。默认实现调用 [generate] 并一次性发射完成响应，保证完全向下兼容。
     */
    fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
        emit(ModelStreamChunk.Completed(generate(request)))
    }
}
