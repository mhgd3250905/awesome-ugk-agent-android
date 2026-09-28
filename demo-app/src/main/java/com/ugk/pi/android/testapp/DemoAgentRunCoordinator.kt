package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentImageContent
import com.ugk.pi.android.AgentRunInput
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * Process-scoped owner for one Agent run.
 *
 * The Activity only observes this object. A configuration/permission
 * recreation can therefore detach one UI instance without cancelling the
 * Agent job or losing its reducer state. Callbacks are explicitly detached
 * and are never retained after their Activity is gone.
 */
data class DemoAgentRunOutcome(
    val generation: Long,
    val conversationId: String,
    val event: AgentEvent,
    val source: AgentRunSource = AgentRunSource.USER,
    val handledByProcessOwner: Boolean = false
)

data class DemoAgentRunSnapshot(
    val generation: Long,
    val conversationId: String?,
    val state: DemoRunState,
    val isRunning: Boolean,
    val queuedMessages: Int,
    val pendingOutcome: DemoAgentRunOutcome?,
    val source: AgentRunSource = AgentRunSource.USER
)

class DemoAgentRunCoordinator(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val sessionFinalizer: (AgentSession) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val queuedMessages = ArrayDeque<String>()

    private var job: Job? = null
    private var generation = 0L
    private var conversationId: String? = null
    private var session: AgentSession? = null
    private var state = DemoRunState.initial()
    private var pendingOutcome: DemoAgentRunOutcome? = null
    private var source: AgentRunSource = AgentRunSource.USER
    private var outcomeObserver: ((AgentEvent) -> Unit)? = null
    private var activeRunLifecycle: DemoAgentRunLifecycle? = null
    private var listenerOwner: Any? = null
    private var eventListener: ((AgentEvent) -> Unit)? = null
    private var finishListener: (() -> Unit)? = null
    private var processIdleListener: (() -> Unit)? = null

    /** Process-owned dispatchers use this to start queued turns after the prior run exits. */
    fun setProcessIdleListener(listener: () -> Unit) {
        processIdleListener = listener
    }

    fun attach(
        owner: Any,
        onEvent: (AgentEvent) -> Unit,
        onFinished: () -> Unit
    ): DemoAgentRunSnapshot {
        listenerOwner = owner
        eventListener = onEvent
        finishListener = onFinished
        return snapshot()
    }

    fun detach(owner: Any) {
        if (listenerOwner !== owner) return
        listenerOwner = null
        eventListener = null
        finishListener = null
    }

    internal fun start(
        runtime: AgentRuntime,
        session: AgentSession,
        conversationId: String,
        message: String,
        images: List<AgentImageContent> = emptyList(),
        runLifecycle: DemoAgentRunLifecycle? = null,
        source: AgentRunSource = AgentRunSource.USER,
        taskId: String? = null,
        onOutcome: ((AgentEvent) -> Unit)? = null,
        onFinished: (() -> Unit)? = null
    ): Long {
        check(job == null) { "An Agent run is already active" }
        check(!DemoCapabilityInterlock.isRecordingOwned()) { "请先结束当前演示录制。" }
        val runId = ++generation
        this.conversationId = conversationId
        this.session = session
        this.source = source
        outcomeObserver = onOutcome
        activeRunLifecycle = runLifecycle
        runLifecycle?.onRunStarted()
        // Publish a busy snapshot synchronously. The runtime's first Started
        // event arrives asynchronously, so the composer must not briefly look
        // idle and accept a competing run.
        state = DemoRunState.initial().reduce(AgentEvent.Started(session.id))
        pendingOutcome = null
        val runSession = session
        lateinit var launchedJob: Job
        launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.Default) {
                    val input = AgentRunInput(
                        content = message,
                        source = source,
                        taskId = taskId,
                        images = images
                    )
                    runtime.run(runSession, input).collect { event ->
                        withContext(mainDispatcher) {
                            runLifecycle?.onEvent(event)
                            dispatch(runId, event)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // stop() owns the visible cancelled state. A stale job must
                // never be allowed to overwrite a newer run.
            } catch (error: Throwable) {
                withContext(mainDispatcher) {
                    dispatch(runId, AgentEvent.Failed(error.message ?: "运行时发生未知错误"))
                }
            } finally {
                withContext(NonCancellable + mainDispatcher) {
                    runCatching { runLifecycle?.onRunFinished() }
                    runCatching { sessionFinalizer(runSession) }
                    if (job !== launchedJob) return@withContext
                    job = null
                    if (activeRunLifecycle === runLifecycle) activeRunLifecycle = null
                    runCatching { onFinished?.invoke() }
                    runCatching { finishListener?.invoke() }
                    runCatching { processIdleListener?.invoke() }
                }
            }
        }
        job = launchedJob
        launchedJob.start()
        return runId
    }

    /** Cancel the current run and invalidate all callbacks from its Job. */
    fun stop(): DemoAgentRunSnapshot {
        if (job?.isActive != true && !state.isBusy) return snapshot()
        generation++
        runCatching { activeRunLifecycle?.onRunCancelled() }
        job?.cancel()
        state = state.cancel()
        pendingOutcome = null
        outcomeObserver = null
        return snapshot()
    }

    fun enqueue(message: String): Boolean {
        val value = message.trim()
        if (value.isBlank() || queuedMessages.size >= MAX_QUEUED_MESSAGES) return false
        queuedMessages.addLast(value)
        return true
    }

    fun removeNextQueued(): String? =
        if (queuedMessages.isEmpty()) null else queuedMessages.removeFirst()

    fun clearQueue() = queuedMessages.clear()

    fun isRunning(): Boolean = job != null || state.isBusy

    /** Reset the terminal presentation when the user switches conversations. */
    fun resetForConversation(conversationId: String) {
        generation++
        this.conversationId = conversationId
        state = DemoRunState.initial()
        pendingOutcome = null
        source = AgentRunSource.USER
        outcomeObserver = null
        queuedMessages.clear()
    }

    fun setDetailsExpanded(expanded: Boolean) {
        state = state.setDetailsExpanded(expanded)
    }

    fun acknowledgeOutcome(generation: Long = this.generation) {
        if (pendingOutcome?.generation == generation) pendingOutcome = null
    }

    fun consumePendingOutcome(conversationId: String): DemoAgentRunOutcome? {
        val outcome = pendingOutcome ?: return null
        if (outcome.conversationId != conversationId) return null
        pendingOutcome = null
        return outcome
    }

    fun snapshot(): DemoAgentRunSnapshot = DemoAgentRunSnapshot(
        generation = generation,
        conversationId = conversationId,
        state = state,
        isRunning = isRunning(),
        queuedMessages = queuedMessages.size,
        pendingOutcome = pendingOutcome,
        source = source
    )

    private fun dispatch(runId: Long, event: AgentEvent) {
        if (runId != generation) return
        state = if (
            event is AgentEvent.Started &&
            state.status == DemoRunStatus.THINKING &&
            state.sessionId == event.sessionId
        ) {
            // start() already published the synchronous busy snapshot; do not
            // reset a process-card expansion made during the first frame.
            state.copy(taskId = event.taskId)
        } else {
            state.reduce(event)
        }
        if (event is AgentEvent.Completed || event is AgentEvent.Failed) {
            val id = conversationId ?: return
            val observer = outcomeObserver
            val handled = observer != null && runCatching { observer(event) }.isSuccess
            pendingOutcome = DemoAgentRunOutcome(runId, id, event, source, handled)
        }
        eventListener?.invoke(event)
    }

    private companion object {
        const val MAX_QUEUED_MESSAGES = 20
    }
}
