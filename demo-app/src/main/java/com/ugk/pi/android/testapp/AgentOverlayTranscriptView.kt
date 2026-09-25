package com.ugk.pi.android.testapp

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Reuses chat views in place so streaming, typing and status changes preserve the reader's place. */
internal class AgentOverlayTranscriptView(context: Context) : LinearLayout(context) {
    private data class MessageHolder(val view: DemoChatMessageView, var message: AgentOverlayMessage)
    private val messages = linkedMapOf<String, MessageHolder>()
    private val processView = DemoChatProcessCardView(context).apply { tag = "overlay-process" }
    private val previewView = DemoChatMessageView(context).apply { tag = "overlay-assistant-preview" }
    private var previewText: String? = null
    private var processExpanded = false
    private var scope: Pair<String?, String?>? = null
    var onImageOpen: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        tag = "overlay-transcript"
        processView.setOnExpandedChangeListener { processExpanded = it }
        previewView.onImageClick = { onImageOpen?.invoke(it) }
    }

    fun render(snapshot: AgentOverlaySnapshot, assistantPreview: String?): Boolean {
        val nextScope = snapshot.conversationId to snapshot.runId
        if (scope != nextScope) {
            processExpanded = false
            processView.setExpanded(false)
            scope = nextScope
        }
        val source = snapshot.messages.ifEmpty {
            snapshot.latestMessage?.takeIf { it.isNotBlank() }?.let {
                listOf(AgentOverlayMessage("legacy-message", snapshot.latestMessageRole ?: "assistant", it))
            }.orEmpty()
        }
        val ordered = mutableListOf<View>()
        var changed = messages.keys.toList() != source.map { it.id }
        val process = snapshot.process ?: legacyProcess(snapshot)
        // A turn's process follows its user/system input and precedes its assistant response.
        val processIndex = source.indexOfLast { it.role != "assistant" } + 1
        source.forEachIndexed { index, message ->
            if (index == processIndex && process != null) ordered.add(processView)
            val holder = messages[message.id]
            val view = if (holder == null) {
                DemoChatMessageView(context).apply {
                    tag = "overlay-message:${message.id}"
                    onImageClick = { onImageOpen?.invoke(it) }
                    bind(message.chatRole(), message.content, message.imagePaths)
                }.also { messages[message.id] = MessageHolder(it, message) }
            } else {
                if (holder.message != message) {
                    holder.view.bind(message.chatRole(), message.content, message.imagePaths)
                    holder.message = message
                    changed = true
                }
                holder.view
            }
            ordered.add(view)
        }
        if (processIndex == source.size && process != null) ordered.add(processView)
        if (process != null) processView.bind(process.copy(expanded = processExpanded, isRunning = snapshot.isBusy))
        val preview = assistantPreview?.takeIf { it.isNotBlank() && snapshot.isBusy }
        if (preview != null && source.lastOrNull()?.let { it.role == "assistant" && it.content == preview } != true) {
            if (previewText == null) previewView.bind(DemoChatMessageRole.ASSISTANT, preview)
            else if (previewText != preview) previewView.updateStreamingText(preview)
            ordered.add(previewView)
        }
        if (previewText != preview) changed = true
        previewText = preview
        val retained = source.map { it.id }.toSet()
        messages.keys.retainAll(retained)
        if ((0 until childCount).map { getChildAt(it) } != ordered) {
            // Keep existing children attached whenever their position has not changed.
            for (index in childCount - 1 downTo 0) {
                if (getChildAt(index) !in ordered) removeViewAt(index)
            }
            ordered.forEachIndexed { index, view ->
                if (getChildAt(index) !== view) {
                    if (view.parent === this) removeView(view)
                    addView(view, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        if (view === processView) {
                            topMargin = context.dp(4)
                            bottomMargin = context.dp(6)
                        }
                    })
                }
            }
            changed = true
        }
        return changed
    }

    private fun AgentOverlayMessage.chatRole() =
        if (role == "user") DemoChatMessageRole.USER else DemoChatMessageRole.ASSISTANT

    private fun legacyProcess(snapshot: AgentOverlaySnapshot): DemoChatProcessState? {
        if (snapshot.steps.isEmpty() && !snapshot.isBusy) return null
        val stage = when {
            snapshot.statusLabel.contains("确认") -> DemoChatProcessStage.WAITING_CONFIRMATION
            snapshot.statusLabel.contains("失败") -> DemoChatProcessStage.ERROR
            snapshot.statusLabel.contains("停止") -> DemoChatProcessStage.STOPPED
            !snapshot.isBusy -> DemoChatProcessStage.COMPLETED
            else -> DemoChatProcessStage.THINKING
        }
        return DemoChatProcessState(stage, isRunning = snapshot.isBusy, steps = snapshot.steps.map {
            DemoChatProcessStep(it.id, it.title, when {
                it.statusLabel.contains("失败") -> DemoChatProcessStepStatus.ERROR
                it.statusLabel.contains("停止") -> DemoChatProcessStepStatus.STOPPED
                it.statusLabel.contains("确认") -> DemoChatProcessStepStatus.WAITING
                it.statusLabel.contains("完成") || it.statusLabel.contains("成功") -> DemoChatProcessStepStatus.COMPLETE
                else -> DemoChatProcessStepStatus.ACTIVE
            }, it.detail, it.resultSummary)
        })
    }
}
