package com.ugk.pi.android.testapp

import org.junit.Assert.*
import org.junit.Test

class DemoWorkflowInterlockTest {
    @Test fun workflowAndRecordingUseOneExclusiveLease() {
        val workflow = Any()
        val recording = Any()
        assertTrue(DemoCapabilityInterlock.tryAcquireWorkflow(workflow))
        try {
            assertTrue(DemoCapabilityInterlock.isScreenOperationOwned())
            assertFalse(DemoCapabilityInterlock.isRecordingOwned())
            assertFalse(DemoCapabilityInterlock.tryAcquireRecording(recording))
            assertFalse(DemoCapabilityInterlock.tryAcquireWorkflow(Any()))
            DemoCapabilityInterlock.releaseWorkflow(Any())
            assertTrue(DemoCapabilityInterlock.isWorkflowOwnedBy(workflow))
            val agent = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
            assertTrue(runCatching { agent.onRunStarted() }.isFailure)
        } finally { DemoCapabilityInterlock.releaseWorkflow(workflow) }
        assertFalse(DemoCapabilityInterlock.isScreenOperationOwned())
        assertTrue(DemoCapabilityInterlock.tryAcquireRecording(recording))
        try { assertFalse(DemoCapabilityInterlock.tryAcquireWorkflow(workflow)) }
        finally { DemoCapabilityInterlock.releaseRecording(recording) }
    }

    @Test fun agentThinkingAlreadyBlocksWorkflowBeforeFirstScreenTool() {
        val agent = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val workflow = Any()
        agent.onRunStarted()
        try { assertFalse(DemoCapabilityInterlock.tryAcquireWorkflow(workflow)) }
        finally { agent.onRunFinished() }
        assertTrue(DemoCapabilityInterlock.tryAcquireWorkflow(workflow))
        DemoCapabilityInterlock.releaseWorkflow(workflow)
    }
}
