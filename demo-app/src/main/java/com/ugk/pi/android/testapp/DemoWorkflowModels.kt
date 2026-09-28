package com.ugk.pi.android.testapp

import java.security.MessageDigest

internal data class DemoWorkflowSelector(val viewId: String? = null, val text: String? = null, val description: String? = null, val className: String? = null, val checked: Boolean? = null)
internal data class DemoWorkflowCondition(val packageName: String, val selectors: List<DemoWorkflowSelector> = emptyList(), val visualQuestion: String? = null)
internal data class DemoWorkflowStep(val id: String, val title: String, val action: String, val packageName: String, val selector: DemoWorkflowSelector? = null, val postcondition: DemoWorkflowCondition, val sourceEventIds: List<Int> = emptyList())
internal data class DemoWorkflowPlan(val draftId: String, val version: Int = 0, val createdAt: Long, val title: String, val goal: String, val steps: List<DemoWorkflowStep>, val warnings: List<String> = emptyList(), val modelCalls: Int = 0, val imagesSent: Int = 0, val completionCriteria: String = "") {
    /** Canonical UTF-8 JSON with fixed key order; excludes compilation usage counters. */
    fun digest(): String = MessageDigest.getInstance("SHA-256").digest(DemoWorkflowJson.plan(this, false).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
internal data class DemoWorkflowIntent(val draftId: String, val goal: String, val completionCriteria: String, val updatedAt: Long)
internal data class DemoWorkflowRunRecord(val id: String, val draftId: String, val version: Int, val planDigest: String, val isTrial: Boolean, val status: String, val startedAt: Long, val endedAt: Long? = null, val completedSteps: Int = 0, val totalSteps: Int = 0, val modelCalls: Int = 0, val imagesSent: Int = 0, val message: String = "")
