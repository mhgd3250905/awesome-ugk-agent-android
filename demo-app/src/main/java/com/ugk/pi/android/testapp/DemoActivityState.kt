package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession

/**
 * Process-scoped state for the demo Activity.
 *
 * The demo is intentionally a single-screen test harness. Keeping the
 * conversation outside the Activity prevents a normal Activity recreation
 * (launcher re-entry, configuration change, or permission/settings return)
 * from looking like a full app restart. It does not hold Views or an Activity
 * reference, so it cannot keep the old window alive.
 */
data class DemoTranscriptEntry(
    val role: String,
    val text: String
)

object DemoActivityState {
    val runCoordinator = DemoAgentRunCoordinator()
    val confirmationPresenter: ActivityUserConfirmationDialogPresenter by lazy {
        ActivityUserConfirmationDialogPresenter()
    }

    var session: AgentSession? = null
    var activeConversationId: String? = null
    var draft: String = ""
    val sessions: MutableMap<String, AgentSession> = mutableMapOf()
    val transcript: MutableList<DemoTranscriptEntry> = mutableListOf()

    var accessibilityPromptShown: Boolean = false
    var overlayPromptShown: Boolean = false

    private var sharedFloatingWindow: AgentFloatingWindow? = null
    private var sharedConversationStore: DemoConversationStore? = null
    private var overlayOwner: Any? = null
    var overlaySend: ((String) -> Boolean)? = null
        private set
    var overlayStop: (() -> Unit)? = null
        private set
    var overlayOpenApp: (() -> Unit)? = null
        private set
    var overlayHide: (() -> Unit)? = null
        private set

    fun floatingWindow(context: Context): AgentFloatingWindow {
        return sharedFloatingWindow ?: AgentFloatingWindow(context.applicationContext).also {
            sharedFloatingWindow = it
        }
    }

    @Synchronized
    fun conversationStore(context: Context): DemoConversationStore {
        return sharedConversationStore ?: DemoConversationStore(context.applicationContext).also {
            sharedConversationStore = it
        }
    }

    fun bindOverlayCallbacks(
        owner: Any,
        onSend: (String) -> Boolean,
        onStop: () -> Unit,
        onOpenApp: () -> Unit,
        onHide: () -> Unit
    ) {
        overlayOwner = owner
        overlaySend = onSend
        overlayStop = onStop
        overlayOpenApp = onOpenApp
        overlayHide = onHide
    }

    fun clearOverlayCallbacks(owner: Any) {
        if (overlayOwner !== owner) return
        overlayOwner = null
        overlaySend = null
        overlayStop = null
        overlayOpenApp = null
        overlayHide = null
    }

    fun rememberSession(conversationId: String, value: AgentSession) {
        activeConversationId = conversationId
        session = value
        sessions[conversationId] = value
        if (sessions.size > MAX_SESSION_CACHE) {
            sessions.keys
                .filter { it != conversationId }
                .take(sessions.size - MAX_SESSION_CACHE)
                .forEach(sessions::remove)
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
    internal fun boundSession(value: AgentSession) {
        val system = value.messages.filterIsInstance<AgentMessage.System>().take(1).map(::compactMessage)
        val nonSystem = value.messages.filterNot { it is AgentMessage.System }
        val budget = (MAX_SESSION_MESSAGES - system.size).coerceAtLeast(1)
        val trimmed = if (nonSystem.size <= budget) nonSystem else trimAtSafeBoundaries(nonSystem, budget)
        value.messages.clear()
        value.messages.addAll(system + trimmed.map(::compactMessage))
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

    private fun compactMessage(message: AgentMessage): AgentMessage = when (message) {
        is AgentMessage.System -> message.copy(content = message.content.take(MAX_MESSAGE_CHARS))
        is AgentMessage.User -> AgentMessage.User(
            content = message.content.take(MAX_MESSAGE_CHARS),
            timeContext = message.timeContext
        )
        is AgentMessage.Assistant -> message.copy(
            content = message.content.take(MAX_MESSAGE_CHARS),
            reasoningContent = message.reasoningContent?.take(MAX_MESSAGE_CHARS)
        )
        is AgentMessage.Tool -> AgentMessage.Tool(
            message.result.copy(content = message.result.content.take(MAX_MESSAGE_CHARS))
        )
    }

    private const val MAX_SESSION_CACHE = 30
    private const val MAX_SESSION_MESSAGES = 160
    private const val MAX_MESSAGE_CHARS = 16_000
}
