package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import com.ugk.pi.system.skill.ScreenUiSnapshot
import com.ugk.pi.system.skill.ScreenUiElement
import com.ugk.pi.system.skill.ScreenGlobalActionRequest
import com.ugk.pi.system.skill.ScreenAutomationBackend
import com.ugk.pi.system.skill.ScreenActionRequest
import com.ugk.pi.system.skill.AccessibilityScreenAutomationBackend
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A single UI-approved run; not an Agent tool confirmation ticket. */
internal class DemoWorkflowActionGateway(
    private val plan: DemoWorkflowPlan,
    private val isAuthorized: () -> Boolean,
    private val launchPackage: suspend (String) -> Unit,
    private val backend: ScreenAutomationBackend,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val validityMillis: Long = 300_000L,
    private val maxRecoveryBacks: Int = 1
) {
    private val digest = plan.digest()
    private val created = nowMillis()
    private val packages = plan.steps.flatMap { listOf(it.packageName, it.postcondition.packageName) }.toSet()
    @Volatile private var valid = true
    private var index = 0
    private var dispatched = false
    private var backs = 0
    val sessionId = "workflow-${java.util.UUID.randomUUID()}"

    init {
        require(validityMillis in 1..300_000L && maxRecoveryBacks in 0..1)
    }

    fun invalidate() { valid = false }

    fun verify(candidate: DemoWorkflowPlan = plan) {
        check(valid && isAuthorized()) { "本次操作授权已失效，请重新确认" }
        check(nowMillis() - created in 0..validityMillis) { "本次操作授权已过期" }
        check(candidate.version == plan.version && candidate.digest() == digest && plan.digest() == digest) { "路径版本已改变" }
    }

    private fun current(step: DemoWorkflowStep) {
        verify()
        check(plan.steps.getOrNull(index) == step) { "拒绝越过当前步骤" }
    }

    suspend fun stepAction(step: DemoWorkflowStep, snapshot: ScreenUiSnapshot?, element: ScreenUiElement?) {
        currentCoroutineContext().ensureActive()
        current(step)
        check(!dispatched) { "动作已派发，不能重放" }
        check(step.action in setOf("launch", "click", "long_click", "scroll_forward", "scroll_backward", "back", "check"))
        check(step.packageName in packages && step.packageName !in HOST_PACKAGES)
        if (step.action != "launch") {
            requireNotNull(snapshot)
            check(snapshot.sessionId == sessionId && snapshot.packageName == step.packageName)
            requireSafe(snapshot)
        }
        if (step.action in NODE_ACTIONS) {
            requireNotNull(snapshot)
            requireNotNull(element)
            check(resolveTarget(snapshot, step) == element) { "目标不是当前唯一语义匹配" }
        }
        // Set before crossing the Android boundary, including rejection/exception paths.
        dispatched = true
        val result = when (step.action) {
            "launch" -> { launchPackage(step.packageName); null }
            "check" -> null
            "back" -> backend.performGlobalAction(ScreenGlobalActionRequest("back"))
            else -> backend.performAction(sessionId, ScreenActionRequest(snapshot!!.snapshotId, element!!.nodeId, step.action))
        }
        currentCoroutineContext().ensureActive()
        verify()
        check(result == null || result.success) { "动作未确认成功，停止且不重放：${result?.code}" }
    }

    suspend fun recoveryBack(step: DemoWorkflowStep, snapshot: ScreenUiSnapshot) {
        currentCoroutineContext().ensureActive()
        current(step)
        check(!dispatched && backs < maxRecoveryBacks) { "返回恢复已超出授权范围" }
        check(snapshot.sessionId == sessionId && snapshot.packageName in packages)
        requireSafe(snapshot)
        backs++
        check(backend.performGlobalAction(ScreenGlobalActionRequest("back")).success) { "返回失败，请接手" }
        currentCoroutineContext().ensureActive()
        verify()
    }

    fun verified(step: DemoWorkflowStep) {
        current(step)
        check(dispatched)
        index++
        dispatched = false
    }

    companion object {
        private val HOST_PACKAGES = setOf("com.ugk.pi.agent", "com.ugk.pi.android.testapp")
        val NODE_ACTIONS = setOf("click", "long_click", "scroll_forward", "scroll_backward")

        fun requireSafe(snapshot: ScreenUiSnapshot) {
            check(snapshot.packageName !in HOST_PACKAGES && !snapshot.truncated && snapshot.elements.isNotEmpty()) { "页面不完整或属于宿主" }
            check(snapshot.elements.none { it.editable || it.type.contains("EditText", true) || it.actions.any { a -> a.name == "set_text" } }) { "输入页面需要用户接手" }
        }

        fun matches(element: ScreenUiElement, selector: DemoWorkflowSelector): Boolean =
            element.enabled && element.visibleToUser && !element.editable &&
                (selector.viewId == null || selector.viewId == element.viewId) &&
                (selector.text == null || selector.text == element.text) &&
                (selector.description == null || selector.description == element.contentDesc) &&
                (selector.className == null || selector.className.substringAfterLast('.') == element.type.substringAfterLast('.')) &&
                (selector.checked == null || element.checkable && selector.checked == element.checked)

        fun resolveTarget(snapshot: ScreenUiSnapshot, step: DemoWorkflowStep): ScreenUiElement? {
            if (snapshot.packageName != step.packageName) return null
            val selector = step.selector ?: return null
            if (selector.viewId.isNullOrBlank() && selector.text.isNullOrBlank() && selector.description.isNullOrBlank()) return null
            val node = snapshot.elements.filter { it.packageName == step.packageName && matches(it, selector) }.singleOrNull() ?: return null
            fun supports(n: ScreenUiElement) = n.actions.any { it.name == step.action } || step.action == "click" && n.clickable
            if (supports(node)) return node
            // AccessibilityScreenAutomationBackend emits rootIndex.childIndex... and
            // parseScreenNodePath accepts only nonnegative integer components. Never
            // infer ancestry from another backend's opaque ID or from screen bounds.
            fun path(id: String): List<Int>? {
                return id.split('.').map { part ->
                    part.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                }
            }
            if (step.action != "click") return null
            val childPath = path(node.nodeId) ?: return null
            return snapshot.elements.filter { parent ->
                val parentPath = path(parent.nodeId)
                parentPath != null && parentPath.size < childPath.size && childPath.take(parentPath.size) == parentPath &&
                    parent.windowIndex == node.windowIndex &&
                    parent.packageName == step.packageName && parent.enabled && parent.visibleToUser && !parent.editable && supports(parent)
            }.singleOrNull()
        }
    }
}
