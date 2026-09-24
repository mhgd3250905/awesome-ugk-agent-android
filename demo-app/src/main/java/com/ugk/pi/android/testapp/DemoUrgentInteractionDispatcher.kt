package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.attention.UrgentPresentationBinding
import java.util.ArrayDeque
import java.util.LinkedHashSet

/** A real user action on an Agent-authored urgent screen, bound to its original session. */
internal data class DemoUrgentInteraction(
    val binding: UrgentPresentationBinding,
    val conversationId: String,
    val title: String,
    val kind: Kind,
    val controlId: String,
    val controlLabel: String,
    val value: String? = null
) {
    enum class Kind { BUTTON, FORM }

    fun asConversationMessage(): String = when (kind) {
        Kind.BUTTON ->
            "我点击了悬浮提醒「$title」中的「$controlLabel」按钮。（控件 ID：$controlId）"
        Kind.FORM ->
            "我在悬浮提醒「$title」的「$controlLabel」输入框提交了：${value.orEmpty()}（控件 ID：$controlId）"
    }
}

/** Starts a new SDK_EVENT turn after the current turn finishes; no parallel Agent run is created. */
internal class DemoUrgentInteractionDispatcher(
    context: Context,
    private val processScope: DemoProcessScope
) {
    private val appContext = context.applicationContext
    private val pending = ArrayDeque<DemoUrgentInteraction>()
    private val acceptedPresentationIds = LinkedHashSet<String>()
    private val conversationRuntime = processScope.conversationRuntime
    private val coordinator = conversationRuntime.runCoordinator
    private var draining = false

    init {
        coordinator.setProcessIdleListener { drain() }
    }

    /** Called on the main thread by the overlay. A true result consumes this screen's event once. */
    fun submit(event: DemoUrgentInteraction): Boolean {
        if (pending.size >= MAX_PENDING || event.binding.presentationId in acceptedPresentationIds) return false
        if (processScope.delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) return false
        if (!ownsCurrentSession(event)) return false
        if (conversationRuntime.agentRuntime == null) return false
        pending.addLast(event)
        acceptedPresentationIds.add(event.binding.presentationId)
        while (acceptedPresentationIds.size > MAX_RECENT_IDS) {
            acceptedPresentationIds.remove(acceptedPresentationIds.first())
        }
        return drain()
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Stopping or replacing the current turn also drops clicks waiting behind it. */
    fun cancelPending() {
        pending.clear()
    }

    fun resumePending() {
        drain()
    }

    private fun ownsCurrentSession(event: DemoUrgentInteraction): Boolean =
        conversationRuntime.activeConversationId == event.conversationId &&
            conversationRuntime.sessionFor(event.conversationId)?.id == event.binding.sessionId &&
            conversationRuntime.conversationStore.get(event.conversationId) != null

    private fun drain(): Boolean {
        if (draining || coordinator.isRunning()) return true
        val event = pending.peekFirst() ?: return true
        draining = true
        try {
            if (!ownsCurrentSession(event)) {
                pending.removeFirst()
                acceptedPresentationIds.remove(event.binding.presentationId)
                processScope.overlayController.window.addLog("悬浮提醒所属会话已变化，操作未发送")
                return false
            }
            val runtime = conversationRuntime.agentRuntime ?: return true
            val store = conversationRuntime.conversationStore
            val session = conversationRuntime.sessionFor(event.conversationId) ?: return true
            val message = event.asConversationMessage()
            if (runCatching { store.appendMessagesAndFlush(
                    event.conversationId,
                    listOf(DemoStoredMessage("user", message))
                ) }.getOrNull() == null
            ) {
                pending.removeFirst()
                acceptedPresentationIds.remove(event.binding.presentationId)
                processScope.overlayController.window.addLog("悬浮提醒操作保存失败")
                return false
            }
            pending.removeFirst()
            runCatching { DemoAgentTraceStore(appContext).reset(event.conversationId, session.id) }
            runCatching { processScope.overlayController.window.apply {
                setSending(true)
                setStatus("正在处理悬浮操作")
                addLog("收到操作：${event.controlLabel.take(40)}")
            } }
            try {
                coordinator.start(
                    runtime = runtime,
                    session = session,
                    conversationId = event.conversationId,
                    message = message,
                    runLifecycle = conversationRuntime.capabilityInterlock,
                    source = AgentRunSource.SDK_EVENT,
                    taskId = event.binding.presentationId,
                    onOutcome = { outcome ->
                        val answer = when (outcome) {
                            is AgentEvent.Completed -> outcome.content
                            is AgentEvent.Failed -> "悬浮提醒操作未完成：${outcome.message}"
                            else -> ""
                        }
                        if (answer.isNotBlank()) {
                            checkNotNull(store.appendMessagesAndFlush(
                                event.conversationId,
                                listOf(DemoStoredMessage("assistant", answer))
                            )) { "Unable to persist the urgent interaction result." }
                        }
                        processScope.overlayController.window.apply {
                            setSending(false)
                            setStatus(if (outcome is AgentEvent.Completed) "已完成" else "失败")
                        }
                    }
                )
            } catch (error: RuntimeException) {
                runCatching {
                    store.appendMessagesAndFlush(
                        event.conversationId,
                        listOf(DemoStoredMessage("assistant", "悬浮提醒操作已记录，但 Agent 未能启动：${error.message.orEmpty()}"))
                    )
                }
                runCatching {
                    processScope.overlayController.window.apply {
                        setSending(false)
                        setStatus("失败")
                    }
                }
            }
            return true
        } finally {
            draining = false
        }
    }

    private companion object {
        const val MAX_PENDING = 4
        const val MAX_RECENT_IDS = 32
    }
}
