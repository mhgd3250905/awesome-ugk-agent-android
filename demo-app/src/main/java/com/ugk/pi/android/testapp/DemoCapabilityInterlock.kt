package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.AgentToolInterlock
import com.ugk.pi.android.AgentToolInterlockDecision
import com.ugk.pi.android.AgentToolInterlockPolicy

/** Run-scoped lifecycle seam for host-owned capability ownership. */
internal interface DemoAgentRunLifecycle {
    fun onRunStarted()
    fun onEvent(event: AgentEvent)
    fun onRunCancelled()
    fun onRunFinished()
}

/**
 * Owns Demo's capability interlock state for Agent Runs.
 *
 * Capability ownership is process-level (D-025): a workflow ToolStarted
 * acquires the screen capability for exactly one run until that run's
 * terminal lifecycle boundary. While ownership is held:
 * - the owning run's Terminal tools stay blocked (the run is in a screen
 *   workflow and must not interleave terminal commands), and
 * - every other run — foreground or a scheduled background executor, each
 *   of which owns its own [DemoCapabilityInterlock] instance — has its
 *   workflow (screen) tools blocked, so two agents never drive the same
 *   physical screen concurrently.
 * Tools outside both sets are never blocked by this policy.
 */
internal class DemoCapabilityInterlock(
    private val workflowToolMatcher: (String) -> Boolean
) : DemoAgentRunLifecycle {
    private var runActive = false

    private val policy = AgentToolInterlockPolicy { tool, _, _ ->
        ProcessState.interlockDecisionFor(
            requester = this@DemoCapabilityInterlock,
            toolName = tool.name,
            workflowToolMatcher = workflowToolMatcher
        )
    }

    @Synchronized
    override fun onRunStarted() {
        runActive = true
        // A new run on this instance retires a previous run of the same
        // instance that never reached a terminal boundary. Ownership held
        // by another active instance is never touched.
        ProcessState.release(this)
    }

    @Synchronized
    override fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStarted -> {
                if (runActive && workflowToolMatcher(event.call.name)) {
                    ProcessState.acquire(this)
                }
            }
            is AgentEvent.Completed,
            is AgentEvent.Failed -> release()
            else -> Unit
        }
    }

    @Synchronized
    override fun onRunCancelled() {
        release()
    }

    @Synchronized
    override fun onRunFinished() {
        release()
    }

    fun toolInterlockPolicy(): AgentToolInterlockPolicy = policy

    fun toolDecorator(): AgentToolDecorator = AgentToolDecorator { tool: AgentTool ->
        AgentToolInterlock(tool, policy)
    }

    @Synchronized
    fun isCapabilityOwned(): Boolean = ProcessState.isOwnedBy(this)

    @Synchronized
    private fun release() {
        runActive = false
        ProcessState.release(this)
    }

    /**
     * Process-wide ownership state shared by every [DemoCapabilityInterlock]
     * instance. Only this lock guards the state and its critical sections
     * never lock an interlock instance, so instance locks (lifecycle methods)
     * and this lock cannot form a cycle.
     */
    private object ProcessState {
        private val lock = Any()
        private var owner: DemoCapabilityInterlock? = null
        private var blockingCapability: String? = null

        fun interlockDecisionFor(
            requester: DemoCapabilityInterlock,
            toolName: String,
            workflowToolMatcher: (String) -> Boolean
        ): AgentToolInterlockDecision? = synchronized(lock) {
            val capability = blockingCapability ?: return null
            val requesterIsOwner = owner === requester
            val blocked = when {
                toolName in TERMINAL_TOOL_NAMES -> requesterIsOwner
                workflowToolMatcher(toolName) -> !requesterIsOwner
                else -> false
            }
            if (blocked) {
                AgentToolInterlockDecision(
                    blockingCapability = capability,
                    message = "该 Tool 当前不可用，因为 capability '$capability' 正被一个进行中的 Agent Run 持有。"
                )
            } else {
                null
            }
        }

        fun acquire(by: DemoCapabilityInterlock) = synchronized(lock) {
            if (blockingCapability == null) {
                owner = by
                blockingCapability = SCREEN_CAPABILITY
            }
        }

        fun release(by: DemoCapabilityInterlock) = synchronized(lock) {
            if (owner === by) {
                owner = null
                blockingCapability = null
            }
        }

        fun isOwnedBy(by: DemoCapabilityInterlock): Boolean = synchronized(lock) {
            blockingCapability != null && owner === by
        }
    }

    private companion object {
        const val SCREEN_CAPABILITY = "android-screen-automation"

        /**
         * Terminal capability tools as wired by [DemoAgentRuntimeFactory] via
         * TerminalAgentPlugin's D-025 decorator seam. The owning run's calls
         * to these stay blocked for the whole screen workflow.
         */
        val TERMINAL_TOOL_NAMES = setOf(
            "terminal_bash_execute",
            "local_http_server_start",
            "local_http_server_stop",
            "local_http_server_status"
        )
    }
}
