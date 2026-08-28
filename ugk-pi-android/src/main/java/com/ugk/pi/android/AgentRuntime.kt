package com.ugk.pi.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

class AgentRuntime(
    private val llmProvider: LLMProvider,
    private val toolRegistry: ToolRegistry,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val skillProvider: AndroidSkillProvider = EmptyAndroidSkillProvider,
    private val skillResolver: AndroidSkillResolver = KeywordAndroidSkillResolver(),
    private val skillPromptBuilder: AndroidSkillPromptBuilder = AndroidSkillPromptBuilder(),
    private val timeContextProvider: AgentTimeContextProvider = SystemAgentTimeContextProvider,
    private val agentInstructions: List<String> = emptyList(),
    private val transcriptPreparationPolicy: TranscriptPreparationPolicy =
        NoOpTranscriptPreparationPolicy
) {
    private var lifecyclePlugins: List<AgentCapabilityPlugin> = emptyList()
    private var lifecyclePluginsAttached = false
    private val closed = AtomicBoolean(false)

    class Builder {
        private var llmProvider: LLMProvider? = null
        private var toolRegistry: ToolRegistry = ToolRegistry()
        private var maxIterations: Int = DEFAULT_MAX_ITERATIONS
        private var skillResolver: AndroidSkillResolver = KeywordAndroidSkillResolver()
        private var skillPromptBuilder: AndroidSkillPromptBuilder = AndroidSkillPromptBuilder()
        private var timeContextProvider: AgentTimeContextProvider = SystemAgentTimeContextProvider
        private var transcriptPreparationPolicy: TranscriptPreparationPolicy =
            NoOpTranscriptPreparationPolicy
        private val skills = mutableListOf<AndroidSkill>()
        private var customSkillProvider: AndroidSkillProvider? = null
        private val agentInstructions = mutableListOf<String>()
        private val plugins = mutableListOf<AgentCapabilityPlugin>()

        fun llmProvider(llmProvider: LLMProvider): Builder {
            this.llmProvider = llmProvider
            return this
        }

        fun toolRegistry(toolRegistry: ToolRegistry): Builder {
            this.toolRegistry = toolRegistry
            return this
        }

        fun maxIterations(maxIterations: Int): Builder {
            this.maxIterations = maxIterations
            return this
        }

        /**
         * Sets the skill provider that the runtime queries on every run.
         *
         * The provider is held by reference: its [AndroidSkillProvider.skills]
         * is invoked per run, so a dynamic implementation can return updated
         * skills without rebuilding the runtime. As before, this replaces any
         * plugin-registered skills; a custom provider takes full ownership of
         * the skill list and must merge static plugin skills itself if needed.
         * Implementations must be safe to call from concurrent runs.
         */
        fun skillProvider(skillProvider: AndroidSkillProvider): Builder {
            this.skills.clear()
            this.customSkillProvider = skillProvider
            return this
        }

        fun skillResolver(skillResolver: AndroidSkillResolver): Builder {
            this.skillResolver = skillResolver
            return this
        }

        fun skillPromptBuilder(skillPromptBuilder: AndroidSkillPromptBuilder): Builder {
            this.skillPromptBuilder = skillPromptBuilder
            return this
        }

        fun timeContextProvider(timeContextProvider: AgentTimeContextProvider): Builder {
            this.timeContextProvider = timeContextProvider
            return this
        }

        fun transcriptPreparationPolicy(policy: TranscriptPreparationPolicy): Builder {
            transcriptPreparationPolicy = policy
            return this
        }

        /**
         * Adds a global system-level contract for the runtime Agent without
         * mutating the conversation history. Plugin-provided instructions are
         * added automatically by [register].
         */
        fun agentInstructions(instructions: String): Builder {
            agentInstructions += instructions
            return this
        }

        fun register(plugin: AgentCapabilityPlugin): Builder {
            require(plugin.id.isNotBlank()) { "Plugin id must not be blank" }
            plugin.tools().forEach { toolRegistry.register(it) }
            skills += plugin.skills()
            agentInstructions += plugin.agentInstructions()
            plugins += plugin
            return this
        }

        fun build(): AgentRuntime {
            return AgentRuntime(
                llmProvider = requireNotNull(llmProvider) { "LLMProvider is required" },
                toolRegistry = toolRegistry,
                maxIterations = maxIterations,
                skillProvider = customSkillProvider ?: StaticAndroidSkillProvider(skills.toList()),
                skillResolver = skillResolver,
                skillPromptBuilder = skillPromptBuilder,
                timeContextProvider = timeContextProvider,
                agentInstructions = agentInstructions
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct(),
                transcriptPreparationPolicy = transcriptPreparationPolicy
            ).also { runtime ->
                runtime.attachLifecyclePlugins(plugins.toList())
            }
        }
    }

    /**
     * Requests cancellation from every registered capability plugin.
     *
     * This forwards on every call while the Runtime is open: a plugin may
     * start new work after an earlier cancellation request. Once [close] has
     * been called, no further plugin work is expected and this becomes a
     * no-op.
     */
    fun cancelAllPlugins(): Int {
        if (closed.get()) return 0
        return lifecyclePlugins.sumOf { it.cancelAll() }
    }

    /**
     * Releases all registered capability plugins exactly once for this Runtime.
     * Plugins are closed in reverse registration order.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecyclePlugins.asReversed().forEach { it.close() }
    }

    @Synchronized
    private fun attachLifecyclePlugins(plugins: List<AgentCapabilityPlugin>) {
        check(!lifecyclePluginsAttached) { "Runtime plugins have already been attached" }
        lifecyclePlugins = plugins
        lifecyclePluginsAttached = true
    }

    fun run(
        session: AgentSession,
        userMessage: String
    ): Flow<AgentEvent> = run(
        session = session,
        input = AgentRunInput(content = userMessage)
    )

    fun run(
        session: AgentSession,
        input: AgentRunInput
    ): Flow<AgentEvent> = run(
        session = session,
        input = input,
        pendingUserMessages = { emptyList() }
    )

    fun run(
        session: AgentSession,
        input: AgentRunInput,
        pendingUserMessages: suspend () -> List<String>
    ): Flow<AgentEvent> = flow {
        if (!session.runGate.tryLock()) {
            emit(AgentEvent.Failed(sessionAlreadyRunningMessage(session.id)))
            return@flow
        }

        try {
            emitAll(runInternal(session, input, pendingUserMessages))
        } finally {
            session.runGate.unlock()
        }
    }

    private fun runInternal(
        session: AgentSession,
        input: AgentRunInput,
        pendingUserMessages: suspend () -> List<String>
    ): Flow<AgentEvent> = flow {
        require(maxIterations > 0) { "maxIterations must be greater than 0" }

        val inputImages = immutableListSnapshot(input.images)
        val inputMessage = userMessageWithTimeContext(input.content)
        session.append(inputMessage)
        emit(
            AgentEvent.Started(
                sessionId = session.id,
                source = input.source,
                taskId = input.taskId,
                visibleInConversation = input.visibleInConversation
            )
        )

        val activeSkillMessage = buildActiveSkillMessage(input.content)

        var completedIterations = 0
        var modelRequestIteration = 0
        var consecutiveIncompleteResponses = 0
        var incompleteResponseCorrection: AgentMessage.System? = null
        var transientInputAttachment: AgentMessage.User? = if (inputImages.isEmpty()) {
            null
        } else {
            AgentMessage.User(
                content = "",
                timeContext = null,
                images = inputImages
            )
        }
        var transientModelMessages: List<AgentMessage> = emptyList()

        while (completedIterations < maxIterations) {
            modelRequestIteration++
            val preparedSessionMessages = try {
                session.prepareTranscript(transcriptPreparationPolicy)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emit(
                    AgentEvent.Failed(
                        transcriptPreparationFailureMessage(error)
                    )
                )
                return@flow
            }
            val requestMessages = buildRequestMessages(
                sessionMessages = preparedSessionMessages,
                activeSkillMessage = activeSkillMessage,
                transientSystemMessage = incompleteResponseCorrection,
                transientInputAttachment = transientInputAttachment,
                transientModelMessages = transientModelMessages
            )
            // Each transient attachment belongs to this one request only.
            transientInputAttachment = null
            transientModelMessages = emptyList()
            val tools = toolRegistry.definitions()
            emit(
                AgentEvent.ModelRequestStarted(
                    iteration = modelRequestIteration,
                    messageCount = requestMessages.size,
                    toolCount = tools.size
                )
            )
            val startedAt = System.currentTimeMillis()
            var fullResponse: ModelResponse? = null
            try {
                llmProvider.generateStream(
                    ModelRequest(
                        sessionId = session.id,
                        messages = requestMessages,
                        tools = tools
                    )
                ).collect { chunk ->
                    when (chunk) {
                        is ModelStreamChunk.ThinkingDelta -> {
                            emit(AgentEvent.ModelThinkingDelta(chunk.delta))
                        }
                        is ModelStreamChunk.ContentDelta -> {
                            emit(AgentEvent.ModelContentDelta(chunk.delta))
                        }
                        is ModelStreamChunk.Completed -> {
                            fullResponse = chunk.response
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emit(
                    AgentEvent.Failed(
                        error.message ?: error::class.java.name
                    )
                )
                return@flow
            }

            val response = fullResponse ?: run {
                emit(AgentEvent.Failed("模型响应流异常中断，未获取到完整响应"))
                return@flow
            }

            emit(
                AgentEvent.ModelResponded(
                    content = response.content,
                    toolCalls = response.toolCalls,
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                    stopReason = response.stopReason,
                    reasoningContent = response.reasoningContent
                )
            )

            if (!response.toolCalls.hasUniqueIds()) {
                emit(AgentEvent.Failed(DUPLICATE_TOOL_CALL_IDS_FAILURE_MESSAGE))
                return@flow
            }

            if (response.toolCalls.isEmpty()) {
                if (response.isIncompleteFinalResponse()) {
                    consecutiveIncompleteResponses++
                    if (consecutiveIncompleteResponses > MAX_INCOMPLETE_RESPONSE_RETRIES) {
                        emit(AgentEvent.Failed(INCOMPLETE_RESPONSE_FAILURE_MESSAGE))
                        return@flow
                    }
                    incompleteResponseCorrection = AgentMessage.System(
                        incompleteResponseRetryPrompt(response.content)
                    )
                    continue
                }
                transientModelMessages = emptyList()
                session.append(AgentMessage.Assistant(response.content))
                if (!prepareTranscriptAtCompletion(session)) return@flow
                emit(AgentEvent.Completed(response.content))
                return@flow
            }

            completedIterations++
            consecutiveIncompleteResponses = 0
            incompleteResponseCorrection = null
            transientModelMessages = emptyList()
            session.append(AgentMessage.Assistant(
                content = response.content,
                toolCalls = response.toolCalls,
                reasoningContent = response.reasoningContent
            ))

            val nextTransientModelMessages = mutableListOf<AgentMessage>()
            try {
                response.toolCalls.forEach { call ->
                    emit(AgentEvent.ToolStarted(call))
                    val result = executeTool(
                        call = call,
                        session = session,
                        input = input,
                        progressSink = { progress ->
                            emit(AgentEvent.ToolProgress(call, progress))
                        }
                    )
                    // Tool 附件只供紧邻的下一次模型请求使用。持久化 transcript
                    // 和事件保持纯文本，避免截图在 AgentSession 中累积或进入诊断输出。
                    // 临时附件和敏感文本只发送到下一次模型请求；持久化会话和事件只保留元数据，
                    // 避免屏幕原图或剪贴板原文进入 AgentSession 或诊断输出。
                    val durableResult = result.copy(
                        images = emptyList(),
                        imageContext = null,
                        transientModelContent = null
                    )
                    session.append(AgentMessage.Tool(durableResult))
                    if (result.images.isNotEmpty() || result.transientModelContent != null) {
                        nextTransientModelMessages += AgentMessage.User(
                            content = result.transientModelContent
                                ?: result.imageContext
                                ?: "The previous tool returned a screen image. Use only the visible screen content when reasoning about the next step.",
                            images = immutableListSnapshot(result.images)
                        )
                    }
                    emit(AgentEvent.ToolFinished(durableResult))
                    terminalCompletion(durableResult)?.let { completion ->
                        session.completeToolBatch(
                            response.toolCalls,
                            missingResultContent = "Tool execution was skipped because another tool ended the turn."
                        )
                        session.append(AgentMessage.Assistant(completion))
                        if (!prepareTranscriptAtCompletion(session)) return@flow
                        emit(AgentEvent.Completed(completion))
                        return@flow
                    }
                }
            } catch (cancelled: CancellationException) {
                // A cancelled run must not leave the assistant tool_use envelope
                // without results: providers reject the whole next request when a
                // tool_use has no tool_result, which would break the session
                // permanently. Answer every unanswered call, then rethrow.
                session.completeToolBatch(response.toolCalls)
                prepareTranscriptAfterCancellation(session)
                throw cancelled
            }
            transientModelMessages = nextTransientModelMessages

            pendingUserMessages()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { message ->
                    session.append(userMessageWithTimeContext(message))
                    emit(AgentEvent.UserMessageAppended(message))
                }
        }

        val message = "Agent loop exceeded maxIterations=$maxIterations"
        if (!prepareTranscriptAtCompletion(session)) return@flow
        emit(AgentEvent.Failed(message))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.prepareTranscriptAtCompletion(
        session: AgentSession
    ): Boolean {
        return try {
            session.prepareTranscript(transcriptPreparationPolicy)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emit(AgentEvent.Failed(transcriptPreparationFailureMessage(error)))
            false
        }
    }

    /** Cancellation has precedence over a policy failure, but still gets a best-effort boundary. */
    private fun prepareTranscriptAfterCancellation(session: AgentSession) {
        try {
            session.prepareTranscript(transcriptPreparationPolicy)
        } catch (_: Throwable) {
            // The original CancellationException remains the run outcome and
            // Session's transactional replacement keeps the old transcript.
        }
    }

    private fun buildActiveSkillMessage(userMessage: String): AgentMessage.System? {
        val availableToolNames = toolRegistry.all().map { it.name }.toSet()
        val activeSkills = skillResolver.resolve(
            userMessage = userMessage,
            skills = skillProvider.skills(),
            availableToolNames = availableToolNames
        )
        val prompt = skillPromptBuilder.build(activeSkills, availableToolNames)
        if (prompt.isBlank()) return null

        return AgentMessage.System(prompt)
    }

    private fun ModelResponse.isIncompleteFinalResponse(): Boolean {
        if (content.isBlank()) return true
        return stopReason == "max_tokens" || stopReason == "length"
    }

    private fun buildRequestMessages(
        sessionMessages: List<AgentMessage>,
        activeSkillMessage: AgentMessage.System?,
        transientSystemMessage: AgentMessage.System? = null,
        transientInputAttachment: AgentMessage.User? = null,
        transientModelMessages: List<AgentMessage> = emptyList()
    ): List<AgentMessage> {
        val runtimeAgentMessages = agentInstructions
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map { AgentMessage.System(it) }
            .toList()
        val systemMessages = sessionMessages.filterIsInstance<AgentMessage.System>()
        val nonSystemMessages = sessionMessages.filterNot { it is AgentMessage.System }
        val requestConversationMessages =
            runtimeAgentMessages +
                systemMessages +
                listOfNotNull(activeSkillMessage, transientSystemMessage) +
                nonSystemMessages
        val transientRequestMessages = listOfNotNull(transientInputAttachment) +
            transientModelMessages.withUserTimePrefixes()
        return immutableListSnapshot(
            (requestConversationMessages.withUserTimePrefixes() + transientRequestMessages)
                .mergeAdjacentUserMessages()
        )
    }

    private fun List<AgentMessage>.mergeAdjacentUserMessages(): List<AgentMessage> {
        val merged = mutableListOf<AgentMessage>()
        forEach { message ->
            val previous = merged.lastOrNull() as? AgentMessage.User
            if (previous != null && message is AgentMessage.User) {
                merged[merged.lastIndex] = AgentMessage.User(
                    content = listOf(previous.content, message.content)
                        .filter(String::isNotBlank)
                        .joinToString("\n"),
                    timeContext = previous.timeContext ?: message.timeContext,
                    images = immutableListSnapshot(previous.images + message.images)
                )
            } else {
                merged += message
            }
        }
        return merged
    }

    private fun List<AgentMessage>.withUserTimePrefixes(): List<AgentMessage> {
        return map { message ->
            if (message is AgentMessage.User) {
                message.withTimePrefix()
            } else {
                message
            }
        }
    }

    private fun AgentMessage.User.withTimePrefix(): AgentMessage.User {
        val context = timeContext ?: timeContextProvider.currentContext()
        return AgentMessage.User(
            content = "${context.prefix()}\n$content",
            timeContext = context,
            images = images
        )
    }

    private fun userMessageWithTimeContext(content: String): AgentMessage.User {
        return AgentMessage.User(
            content = content,
            timeContext = timeContextProvider.currentContext()
        )
    }

    private suspend fun executeTool(
        call: ToolCall,
        session: AgentSession,
        input: AgentRunInput,
        progressSink: suspend (ToolProgress) -> Unit = {}
    ): ToolResult {
        val tool = toolRegistry.get(call.name)
            ?: return ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = "Tool not registered: ${call.name}",
                isError = true
            )

        return try {
            tool.execute(
                call = call,
                context = ToolExecutionContext(
                    sessionId = session.id,
                    priorMessages = session.snapshot(),
                    runSource = input.source,
                    taskId = input.taskId,
                    visibleInConversation = input.visibleInConversation,
                    reportProgress = progressSink
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = error.message ?: error::class.java.name,
                isError = true
            )
        }
    }

    private fun terminalCompletion(result: ToolResult): String? {
        val terminalForTurn = result.metadata["terminalForTurn"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false
        if (!terminalForTurn) return null

        return result.metadata["assistantMessage"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: result.content
    }

}

/**
 * Default number of model/tool iterations allowed for one run.
 *
 * This remains a finite safety limit, while giving screen and terminal
 * workflows enough room to continue past the former 50- and 200-iteration cutoffs.
 * Hosts can override it with [AgentRuntime.Builder.maxIterations].
 */
internal const val DEFAULT_MAX_ITERATIONS = 500
private const val MAX_INCOMPLETE_RESPONSE_RETRIES = 2
private fun sessionAlreadyRunningMessage(sessionId: String): String =
    "AgentSession '$sessionId' is already running."
private const val INCOMPLETE_RESPONSE_FAILURE_MESSAGE =
    "Model returned an incomplete final response three consecutive times."
private const val DUPLICATE_TOOL_CALL_IDS_FAILURE_MESSAGE =
    "Model response contained duplicate tool call ids."

private fun List<ToolCall>.hasUniqueIds(): Boolean =
    size == map { it.id }.toSet().size

private fun incompleteResponseRetryPrompt(partialContent: String): String {
    val partial = partialContent.trim()
    val detail = if (partial.isBlank()) {
        "Your previous response was empty."
    } else {
        "Your previous response was incomplete and ended with: \"$partial\""
    }
    return "$detail Reproduce the complete final answer from the beginning using the existing conversation and tool results. " +
        "If more information is genuinely required, call only the next necessary tool. Do not repeat completed tool calls."
}

private fun transcriptPreparationFailureMessage(error: Throwable): String =
    "Transcript preparation failed: ${error.message ?: error::class.java.name}"
