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
 * Owns Demo's capability interlock state for exactly one Agent Run.
 *
 * A workflow ToolStarted acquires ownership. ToolFinished is intentionally
 * not a release boundary; only the run's terminal lifecycle releases it.
 */
internal class DemoCapabilityInterlock(
    private val workflowToolMatcher: (String) -> Boolean
) : DemoAgentRunLifecycle {
    private var runActive = false
    private var blockingCapability: String? = null

    private val policy = AgentToolInterlockPolicy { _, _, _ ->
        synchronized(this) {
            blockingCapability?.let { capability ->
                AgentToolInterlockDecision(
                    blockingCapability = capability,
                    message = "该 Tool 当前不可用，因为 capability '$capability' 持有本次 Agent Run。"
                )
            }
        }
    }

    @Synchronized
    override fun onRunStarted() {
        runActive = true
        blockingCapability = null
    }

    @Synchronized
    override fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStarted -> {
                if (runActive && workflowToolMatcher(event.call.name)) {
                    blockingCapability = SCREEN_CAPABILITY
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
    fun isCapabilityOwned(): Boolean = blockingCapability != null

    @Synchronized
    private fun release() {
        runActive = false
        blockingCapability = null
    }

    private companion object {
        const val SCREEN_CAPABILITY = "android-screen-automation"
    }
}
