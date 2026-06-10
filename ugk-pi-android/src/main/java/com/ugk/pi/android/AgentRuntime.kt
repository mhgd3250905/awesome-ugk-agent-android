package com.ugk.pi.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class AgentRuntime(
    private val llmProvider: LLMProvider,
    private val toolRegistry: ToolRegistry,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val skillProvider: AndroidSkillProvider = EmptyAndroidSkillProvider,
    private val skillResolver: AndroidSkillResolver = KeywordAndroidSkillResolver(),
    private val skillPromptBuilder: AndroidSkillPromptBuilder = AndroidSkillPromptBuilder(),
    private val timeContextProvider: AgentTimeContextProvider = SystemAgentTimeContextProvider
) {
    class Builder {
        private var llmProvider: LLMProvider? = null
        private var toolRegistry: ToolRegistry = ToolRegistry()
        private var maxIterations: Int = DEFAULT_MAX_ITERATIONS
        private var skillResolver: AndroidSkillResolver = KeywordAndroidSkillResolver()
        private var skillPromptBuilder: AndroidSkillPromptBuilder = AndroidSkillPromptBuilder()
        private var timeContextProvider: AgentTimeContextProvider = SystemAgentTimeContextProvider
        private val skills = mutableListOf<AndroidSkill>()

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

        fun skillProvider(skillProvider: AndroidSkillProvider): Builder {
            this.skills.clear()
            this.skills += skillProvider.skills()
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

        fun register(plugin: AgentCapabilityPlugin): Builder {
            require(plugin.id.isNotBlank()) { "Plugin id must not be blank" }
            plugin.tools().forEach { toolRegistry.register(it) }
            skills += plugin.skills()
            return this
        }

        fun build(): AgentRuntime {
            return AgentRuntime(
                llmProvider = requireNotNull(llmProvider) { "LLMProvider is required" },
                toolRegistry = toolRegistry,
                maxIterations = maxIterations,
                skillProvider = StaticAndroidSkillProvider(skills.toList()),
                skillResolver = skillResolver,
                skillPromptBuilder = skillPromptBuilder,
                timeContextProvider = timeContextProvider
            )
        }
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
        require(maxIterations > 0) { "maxIterations must be greater than 0" }

        session.messages += userMessageWithTimeContext(input.content)
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

        while (completedIterations < maxIterations) {
            modelRequestIteration++
            val requestMessages = buildRequestMessages(
                sessionMessages = session.messages.toList(),
                activeSkillMessage = activeSkillMessage,
                transientSystemMessage = incompleteResponseCorrection
            )
            val tools = toolRegistry.definitions()
            emit(
                AgentEvent.ModelRequestStarted(
                    iteration = modelRequestIteration,
                    messageCount = requestMessages.size,
                    toolCount = tools.size
                )
            )
            val startedAt = System.currentTimeMillis()
            val response = llmProvider.generate(
                ModelRequest(
                    sessionId = session.id,
                    messages = requestMessages,
                    tools = tools
                )
            )

            emit(
                AgentEvent.ModelResponded(
                    content = response.content,
                    toolCalls = response.toolCalls,
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                    stopReason = response.stopReason
                )
            )

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
                session.messages += AgentMessage.Assistant(response.content)
                emit(AgentEvent.Completed(response.content))
                return@flow
            }

            completedIterations++
            consecutiveIncompleteResponses = 0
            incompleteResponseCorrection = null
            session.messages += AgentMessage.Assistant(
                content = response.content,
                toolCalls = response.toolCalls,
                reasoningContent = response.reasoningContent
            )

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
                session.messages += AgentMessage.Tool(result)
                emit(AgentEvent.ToolFinished(result))
                terminalCompletion(result)?.let { completion ->
                    session.messages += AgentMessage.Assistant(completion)
                    emit(AgentEvent.Completed(completion))
                    return@flow
                }
            }

            pendingUserMessages()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { message ->
                    session.messages += userMessageWithTimeContext(message)
                    emit(AgentEvent.UserMessageAppended(message))
                }
        }

        val message = "Agent loop exceeded maxIterations=$maxIterations"
        emit(AgentEvent.Failed(message))
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
        transientSystemMessage: AgentMessage.System? = null
    ): List<AgentMessage> {
        val systemMessages = sessionMessages.filterIsInstance<AgentMessage.System>()
        val nonSystemMessages = sessionMessages.filterNot { it is AgentMessage.System }
        val requestMessages =
            systemMessages + listOfNotNull(activeSkillMessage, transientSystemMessage) + nonSystemMessages
        return requestMessages.withUserTimePrefixes()
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
            timeContext = context
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
                    priorMessages = session.messages.toList(),
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

internal const val DEFAULT_MAX_ITERATIONS = 50
private const val MAX_INCOMPLETE_RESPONSE_RETRIES = 2
private const val INCOMPLETE_RESPONSE_FAILURE_MESSAGE =
    "Model returned an incomplete final response three consecutive times."

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
