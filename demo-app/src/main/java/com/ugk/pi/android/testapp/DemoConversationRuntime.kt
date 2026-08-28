package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Process-owned conversation runtime for the demo.
 *
 * It owns the application-scoped conversation store, the Agent run
 * coordinator, and all conversation/session state that must survive an
 * Activity recreation. It deliberately contains no Activity or View.
 */
class DemoConversationRuntime private constructor(
    private val appContext: Context?,
    mainDispatcher: CoroutineDispatcher
) {
    /** Production constructor: all durable storage is rooted at application context. */
    constructor(context: Context) : this(
        appContext = context.applicationContext,
        mainDispatcher = Dispatchers.Main.immediate
    )

    /** Pure JVM state constructor used by unit tests that do not need Android storage. */
    internal constructor() : this(
        appContext = null,
        mainDispatcher = Dispatchers.Unconfined
    )

    val conversationStore: DemoConversationStore by lazy {
        DemoConversationStore(
            requireNotNull(appContext) {
                "DemoConversationRuntime.conversationStore requires an Android Context"
            }
        )
    }

    val runCoordinator: DemoAgentRunCoordinator = DemoAgentRunCoordinator(
        mainDispatcher = mainDispatcher,
        sessionFinalizer = { value -> boundSession(value) }
    )

    var session: AgentSession? = null
    var activeConversationId: String? = null
    var draft: String = ""
    val sessions: MutableMap<String, AgentSession> = mutableMapOf()
    val transcript: MutableList<DemoTranscriptEntry> = mutableListOf()

    var accessibilityPromptShown: Boolean = false
    var overlayPromptShown: Boolean = false
    var activeContextWindow: String? = null
    var activeAutoCompaction: Boolean = true
    var activeCompactionThreshold: Double = ContextCompactor.DEFAULT_THRESHOLD

    fun rememberSession(conversationId: String, session: AgentSession) {
        activeConversationId = conversationId
        this.session = session
        sessions[conversationId] = session
        if (sessions.size > MAX_SESSION_CACHE) {
            val oldestKey = sessions.keys.firstOrNull { it != activeConversationId }
            if (oldestKey != null) sessions.remove(oldestKey)
        }
    }

    fun sessionFor(conversationId: String): AgentSession? = sessions[conversationId]

    fun clearActiveConversation() {
        activeConversationId = null
        session = null
        transcript.clear()
        draft = ""
    }

    /** Keep runtime history bounded even before it is persisted to JSON. */
    internal fun boundSession(
        value: AgentSession,
        contextWindow: String? = activeContextWindow,
        thresholdRatio: Double = activeCompactionThreshold,
        autoCompaction: Boolean = activeAutoCompaction
    ): CompactionSummary {
        // 先检查并执行上下文智能阶梯压缩（达到阈值时）
        val summary = ContextCompactor.compactIfNeeded(
            session = value,
            contextWindow = contextWindow ?: activeContextWindow,
            thresholdRatio = thresholdRatio,
            autoCompaction = autoCompaction
        )

        val (maxMessages, maxChars) = budgetForContextWindow(contextWindow ?: activeContextWindow)
        val system = value.messages.filterIsInstance<AgentMessage.System>().take(1).map { compactMessage(it, maxChars) }
        val nonSystem = value.messages.filterNot { it is AgentMessage.System }
        val budget = (maxMessages - system.size).coerceAtLeast(1)
        val trimmed = if (nonSystem.size <= budget) nonSystem else trimAtSafeBoundaries(nonSystem, budget)
        value.messages.clear()
        value.messages.addAll(system + trimmed.map { compactMessage(it, maxChars) })
        return summary
    }

    fun budgetForContextWindow(contextWindow: String? = activeContextWindow): Pair<Int, Int> {
        val profile = ContextProfile.resolve(contextWindow ?: activeContextWindow)
        return profile.sessionMaxMessages to profile.sessionMaxChars
    }

    /**
     * Bounds [messages] without breaking the transcript invariants that model
     * providers enforce on the next request: the first message must be a user
     * message, every assistant tool_use must keep its tool_result, and no
     * orphaned tool_result may survive without its assistant envelope.
     *
     * A naive tail cut can split an assistant envelope from its results, which
     * makes every later request on that session fail with a provider 400. The
     * cut therefore only ever happens on whole groups — a user message, or an
     * assistant envelope together with all of its consecutive tool results —
     * and history is filled from the newest end so unused budget still keeps
     * earlier turns.
     */
    private fun trimAtSafeBoundaries(messages: List<AgentMessage>, budget: Int): List<AgentMessage> {
        val kept = trailingWholeGroups(messages, budget)
        val firstUserInKept = kept.indexOfFirst { it is AgentMessage.User }
        if (firstUserInKept > 0) {
            // The budget ended inside an older turn: drop that leading partial
            // turn so the transcript starts with the user role.
            return kept.subList(firstUserInKept, kept.size)
        }
        if (firstUserInKept == 0) return kept

        // No user message inside the bounded window: the current turn alone is
        // larger than the budget. Keep the user message that started it plus as
        // many complete groups after it as fit.
        val enclosingUserIndex = messages.indexOfLast { it is AgentMessage.User }
        if (enclosingUserIndex < 0) return kept
        val tail = trailingWholeGroups(
            messages.subList(enclosingUserIndex + 1, messages.size),
            budget - 1
        )
        return listOf(messages[enclosingUserIndex]) + tail
    }

    /** Collects whole groups from the end of [messages] within [budget]. */
    private fun trailingWholeGroups(messages: List<AgentMessage>, budget: Int): List<AgentMessage> {
        val result = ArrayList<AgentMessage>()
        var end = messages.size
        while (end > 0 && result.size < budget) {
            var start = end - 1
            while (start > 0 && messages[start] is AgentMessage.Tool) start--
            val group = messages.subList(start, end)
            if (result.size + group.size > budget) break
            result.addAll(0, group)
            end = start
        }
        return result
    }

    private fun compactMessage(message: AgentMessage, maxChars: Int = MAX_MESSAGE_CHARS): AgentMessage = when (message) {
        is AgentMessage.System -> message.copy(content = message.content.take(maxChars))
        is AgentMessage.User -> AgentMessage.User(
            content = message.content.take(maxChars),
            timeContext = message.timeContext
        )
        is AgentMessage.Assistant -> message.copy(
            content = message.content.take(maxChars),
            reasoningContent = message.reasoningContent?.take(maxChars)
        )
        is AgentMessage.Tool -> AgentMessage.Tool(
            message.result.copy(content = message.result.content.take(maxChars))
        )
    }

    private companion object {
        const val MAX_SESSION_CACHE = 30
        const val MAX_MESSAGE_CHARS = 16_000
    }
}

/** Compatibility transcript entry retained for the runtime's existing transcript model. */
data class DemoTranscriptEntry(
    val role: String,
    val text: String
)
