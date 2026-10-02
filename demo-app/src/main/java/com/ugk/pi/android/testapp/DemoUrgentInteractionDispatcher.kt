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
    private val ledger = DemoUrgentInteractionLedger()
    private val conversationRuntime = processScope.conversationRuntime
    private val coordinator = conversationRuntime.runCoordinator
    private var draining = false

    init {
        coordinator.setProcessIdleListener { drain() }
    }

    /** Called on the main thread by the overlay. A true result consumes this screen's event once. */
    fun submit(event: DemoUrgentInteraction): Boolean {
        if (DemoCapabilityInterlock.isScreenOperationOwned()) return false
        if (!ledger.accept(event)) return false
        if (!timerAllowsInteraction(event)) {
            ledger.discard(event)
            return false
        }
        if (!ownsCurrentSession(event)) {
            ledger.discard(event)
            return false
        }
        if (conversationRuntime.agentRuntime == null) {
            ledger.discard(event)
            return false
        }
        return drain()
    }

    fun hasPending(): Boolean = ledger.hasPending()

    /** Stopping or replacing the current turn also drops clicks waiting behind it. */
    fun cancelPending() {
        ledger.discardAll()
    }

    fun resumePending() {
        drain()
    }

    private fun ownsCurrentSession(event: DemoUrgentInteraction): Boolean =
        conversationRuntime.activeConversationId == event.conversationId &&
            conversationRuntime.sessionFor(event.conversationId)?.id == event.binding.sessionId &&
            conversationRuntime.conversationStore.get(event.conversationId) != null

    private fun timerAllowsInteraction(event: DemoUrgentInteraction): Boolean =
        when (val timer = processScope.delayedTasks.snapshot()) {
            DemoDelayedTaskState.Idle -> true
            is DemoDelayedTaskState.Executing -> timer.task.conversationId == event.conversationId
            is DemoDelayedTaskState.Waiting ->
                timer.task.repeating && timer.task.conversationId == event.conversationId
            is DemoDelayedTaskState.Proposed -> false
        }

    private fun drain(): Boolean {
        if (DemoCapabilityInterlock.isScreenOperationOwned()) {
            // The queued clicks are gone, so the screens that produced them must
            // be able to produce them again: see DemoUrgentInteractionLedger.
            cancelPending()
            return false
        }
        if (draining || coordinator.isRunning()) return true
        val event = ledger.next() ?: return true
        draining = true
        try {
            if (!ownsCurrentSession(event)) {
                ledger.discard(event)
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
                ledger.discard(event)
                processScope.overlayController.window.addLog("悬浮提醒操作保存失败")
                return false
            }
            ledger.deliver(event)
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
                            // DemoAgentRunCoordinator invokes this observer inside
                            // runCatching (that is how it computes `handled`), so an
                            // exception thrown here is swallowed and everything after
                            // it in this lambda never runs: the check below used to
                            // leave the floating window on "正在处理悬浮操作" for the
                            // rest of the session whenever the conversation had been
                            // deleted. A result that cannot be persisted is now
                            // reported instead of fatal, and the reset always runs.
                            val persisted = runCatching {
                                store.appendMessagesAndFlush(
                                    event.conversationId,
                                    listOf(DemoStoredMessage("assistant", answer))
                                ) != null
                            }.getOrDefault(false)
                            if (!persisted) {
                                runCatching {
                                    processScope.overlayController.window.addLog("悬浮提醒结果未能保存")
                                }
                            }
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

}

/**
 * The queue of urgent-screen actions and the memory of which screens have
 * already been spoken for.
 *
 * Split out of [DemoUrgentInteractionDispatcher] because nothing in that class
 * can be reached from a host test: it needs an Android Context, an overlay
 * window and a live Agent run, and the module's unit tests contain no reference
 * to urgent interactions at all. The bookkeeping below is pure, and it is where
 * the defect was.
 *
 * The rule: a presentation id is remembered while its event is queued or has
 * been delivered, and released as soon as the event is dropped without being
 * delivered. Remembering a discarded event is what made a user's tap on an
 * urgent screen permanently unrecoverable - the queue was emptied, the overlay
 * was told the action did not go through, and every later attempt for that
 * `presentationId` was refused by the deduplication before it could be queued.
 */
internal class DemoUrgentInteractionLedger(
    private val maxPending: Int = MAX_PENDING,
    private val maxRemembered: Int = MAX_REMEMBERED
) {
    private val pending = ArrayDeque<DemoUrgentInteraction>()
    private val remembered = LinkedHashSet<String>()

    /** True when this screen's action is now queued; a repeat of a queued or delivered id is refused. */
    fun accept(event: DemoUrgentInteraction): Boolean {
        val presentationId = event.binding.presentationId
        if (pending.size >= maxPending || presentationId in remembered) return false
        pending.addLast(event)
        remember(presentationId)
        return true
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun pendingCount(): Int = pending.size

    fun next(): DemoUrgentInteraction? = pending.peekFirst()

    /** The event leaves the queue and its id stays remembered: this screen has been answered once. */
    fun deliver(event: DemoUrgentInteraction) {
        remove(event)
        remember(event.binding.presentationId)
    }

    /** The event is thrown away without being delivered, so its screen may be tapped again. */
    fun discard(event: DemoUrgentInteraction) {
        remove(event)
        remembered.remove(event.binding.presentationId)
    }

    /** Every queued action is thrown away: the whole batch of screens becomes reusable. */
    fun discardAll() {
        val dropped = pending.toList()
        pending.clear()
        dropped.forEach { remembered.remove(it.binding.presentationId) }
    }

    private fun remove(event: DemoUrgentInteraction) {
        // Ids are unique while queued (accept refuses a repeat), so the
        // predicate selects exactly this event.
        if (!pending.removeIf { it.binding.presentationId == event.binding.presentationId }) {
            throw IllegalStateException("urgent interaction is not queued: ${event.binding.presentationId}")
        }
    }

    private fun remember(presentationId: String) {
        remembered.add(presentationId)
        while (remembered.size > maxRemembered) {
            remembered.remove(remembered.first())
        }
    }

    private companion object {
        const val MAX_PENDING = 4
        const val MAX_REMEMBERED = 32
    }
}
