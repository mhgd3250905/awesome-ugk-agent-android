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
        val system = value.messages.filterIsInstance<AgentMessage.System>().take(1)
        val tail = value.messages
            .filterNot { it is AgentMessage.System }
            .takeLast(MAX_SESSION_MESSAGES - system.size)
            .map(::compactMessage)
        value.messages.clear()
        value.messages.addAll(system.map(::compactMessage) + tail)
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
