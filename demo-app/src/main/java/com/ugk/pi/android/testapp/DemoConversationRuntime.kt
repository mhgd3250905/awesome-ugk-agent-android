package com.ugk.pi.android.testapp

import android.content.Context
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
        mainDispatcher = mainDispatcher
    )
    internal val capabilityInterlock: DemoCapabilityInterlock = DemoCapabilityInterlock(
        DemoScreenAutomationPolicy::isScreenWorkflowTool
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

    fun budgetForContextWindow(contextWindow: String? = activeContextWindow): Pair<Int, Int> {
        val profile = ContextProfile.resolve(contextWindow ?: activeContextWindow)
        return profile.sessionMaxMessages to profile.sessionMaxChars
    }

    private companion object {
        const val MAX_SESSION_CACHE = 30
    }
}

/** Compatibility transcript entry retained for the runtime's existing transcript model. */
data class DemoTranscriptEntry(
    val role: String,
    val text: String
)
