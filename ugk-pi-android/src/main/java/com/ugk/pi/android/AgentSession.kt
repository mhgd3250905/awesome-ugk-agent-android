package com.ugk.pi.android

import kotlinx.coroutines.sync.Mutex
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock

internal fun <T> immutableListSnapshot(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

class AgentSession(
    val id: String,
    messages: List<AgentMessage> = emptyList()
) {
    /**
     * Copy-on-write transcript state. This lock is separate from [runGate]:
     * callers can read [messages] outside a runtime run.
     */
    internal val transcriptLock = ReentrantLock()
    private var transcript: List<AgentMessage> =
        immutableSnapshot(messages.map(::durableMessage))

    init {
        validateTranscript(transcript)
    }

    /**
     * Returns a stable read-only snapshot of the current transcript. The
     * returned list is not backed by the session and cannot be cast back into
     * a mutable list to change it.
     */
    val messages: List<AgentMessage>
        get() = withTranscriptLock { immutableSnapshot(transcript) }

    internal fun append(message: AgentMessage) {
        val durable = durableMessage(message)
        withTranscriptLock {
            transcript = immutableSnapshot(transcript + durable)
        }
    }

    /**
     * Completes the most recent tool-use envelope without duplicating results
     * that were already appended before cancellation.
     */
    internal fun completeToolBatch(
        toolCalls: List<ToolCall>,
        missingResultContent: String = CANCELLED_TOOL_RESULT
    ) {
        withTranscriptLock {
            if (toolCalls.isEmpty()) return@withTranscriptLock

            val envelopeIndex = transcript.indexOfLast {
                it is AgentMessage.Assistant && it.toolCalls.isNotEmpty()
            }
            if (envelopeIndex < 0) return@withTranscriptLock

            val answeredIds = transcript
                .drop(envelopeIndex + 1)
                .takeWhile { it is AgentMessage.Tool }
                .filterIsInstance<AgentMessage.Tool>()
                .map { it.result.toolCallId }
                .toMutableSet()

            val completedTranscript = transcript.toMutableList()
            toolCalls.forEach { call ->
                if (!answeredIds.add(call.id)) return@forEach
                completedTranscript += AgentMessage.Tool(
                    ToolResult(
                        toolCallId = call.id,
                        name = call.name,
                        content = missingResultContent,
                        isError = true
                    )
                )
            }
            transcript = immutableSnapshot(completedTranscript)
        }
    }

    internal fun snapshot(): List<AgentMessage> =
        withTranscriptLock { immutableSnapshot(transcript) }

    /**
     * Applies a pure preparation result atomically. The current transcript is
     * not changed until the complete candidate has been copied and validated.
     */
    internal fun prepareTranscript(policy: TranscriptPreparationPolicy): List<AgentMessage> {
        val prepared = policy.prepare(snapshot())
        replaceCandidate(copyAndValidate(prepared.messages))
        return snapshot()
    }

    internal fun replaceTranscript(messages: List<AgentMessage>) {
        replaceCandidate(copyAndValidate(messages))
    }

    /**
     * Guards the mutable conversation history while a runtime is collecting a
     * run for this session. It is intentionally not a constructor property so
     * it remains an internal runtime concern.
     */
    internal val runGate = Mutex()

    private companion object {
        const val CANCELLED_TOOL_RESULT =
            "Tool execution was cancelled before this call completed. " +
                "The user stopped the run or the runtime shut down."

        fun <T> immutableSnapshot(values: List<T>): List<T> =
            immutableListSnapshot(values)

        fun copyAndValidate(messages: List<AgentMessage>): List<AgentMessage> {
            val candidate = messages.map(::durableMessage)
            validateTranscript(candidate)
            return immutableSnapshot(candidate)
        }

        fun durableMessage(message: AgentMessage): AgentMessage = when (message) {
            is AgentMessage.System -> message
            is AgentMessage.User -> AgentMessage.User(
                content = message.content,
                timeContext = message.timeContext
            )
            is AgentMessage.Assistant -> message.copy(
                toolCalls = immutableSnapshot(message.toolCalls)
            )
            is AgentMessage.Tool -> AgentMessage.Tool(
                message.result.copy(
                    images = emptyList(),
                    imageContext = null,
                    transientModelContent = null
                )
            )
        }

        fun validateTranscript(messages: List<AgentMessage>) {
            val nonSystem = messages.filterNot { it is AgentMessage.System }
            if (nonSystem.isEmpty()) return
            require(nonSystem.first() is AgentMessage.User) {
                "Transcript must start with a user message after system messages"
            }

            var index = 0
            while (index < nonSystem.size) {
                when (val message = nonSystem[index]) {
                    is AgentMessage.Assistant -> {
                        val toolCalls = message.toolCalls
                        if (toolCalls.isEmpty()) {
                            index++
                            continue
                        }
                        val expectedIds = toolCalls.map { it.id }
                        require(expectedIds.size == expectedIds.toSet().size) {
                            "Transcript assistant tool_use ids must be unique"
                        }
                        val resultIds = mutableListOf<String>()
                        var resultIndex = index + 1
                        while (
                            resultIndex < nonSystem.size &&
                            nonSystem[resultIndex] is AgentMessage.Tool
                        ) {
                            val result = (nonSystem[resultIndex] as AgentMessage.Tool).result
                            require(result.toolCallId in expectedIds) {
                                "Transcript contains a tool_result without a matching tool_use"
                            }
                            require(result.toolCallId !in resultIds) {
                                "Transcript contains duplicate tool_result ${result.toolCallId}"
                            }
                            resultIds += result.toolCallId
                            resultIndex++
                        }
                        require(resultIds.toSet() == expectedIds.toSet()) {
                            "Transcript assistant tool_use must be followed by all tool_results"
                        }
                        index = resultIndex
                    }
                    is AgentMessage.Tool -> {
                        error("Transcript contains an orphan tool_result")
                    }
                    else -> index++
                }
            }
        }
    }

    private fun replaceCandidate(candidate: List<AgentMessage>) {
        withTranscriptLock {
            require(
                transcript.none { it !is AgentMessage.System } ||
                    candidate.any { it is AgentMessage.User }
            ) {
                "Prepared transcript cannot erase the existing conversation"
            }
            transcript = immutableSnapshot(candidate)
        }
    }

    private fun <T> withTranscriptLock(block: () -> T): T {
        transcriptLock.lock()
        return try {
            block()
        } finally {
            transcriptLock.unlock()
        }
    }
}
