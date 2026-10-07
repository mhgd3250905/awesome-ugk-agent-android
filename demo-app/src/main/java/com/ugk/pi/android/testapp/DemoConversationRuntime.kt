package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentRuntime
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

    // Runtime ownership is process-level: an Activity recreation (config
    // change, split screen, font scale) must never terminate an in-flight
    // Agent run or clear its queued overlay messages. The Activity only
    // reads and writes through these fields; the finishing Activity is the
    // sole owner of teardown (cancelAllPlugins + close + null).
    var agentRuntime: AgentRuntime? = null
    internal var appliedRuntimeConfig: DemoRuntimeConfig? = null

    /**
     * The authorization mode the registered Tool set was built under. Separate from
     * [appliedRuntimeConfig] on purpose: a provider-config change may stop the turn it
     * belongs to, while an authorization change must not.
     */
    internal var appliedAuthorizationMode: Boolean? = null

    val sessions: MutableMap<String, AgentSession> = mutableMapOf()

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
