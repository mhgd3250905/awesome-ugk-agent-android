package com.ugk.pi.android.testapp

import com.ugk.pi.attention.UrgentMessage
import com.ugk.pi.attention.InteractiveUrgentMessagePresenter
import com.ugk.pi.attention.UrgentPresentation
import com.ugk.pi.attention.UrgentPresentationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adapts the optional SDK capability to this app's existing process-owned window. */
internal class DemoUrgentMessagePresenter(
    private val overlayController: DemoOverlayController,
    private val conversationIdForSession: (String) -> String?
) : InteractiveUrgentMessagePresenter {
    override suspend fun show(message: UrgentMessage): UrgentPresentation =
        withContext(Dispatchers.Main.immediate) {
            val hasControls = message.actions.isNotEmpty() || message.form != null
            val conversationId = message.binding?.sessionId?.let(conversationIdForSession)
            if (hasControls && conversationId == null) {
                UrgentPresentation(UrgentPresentationStatus.UNAVAILABLE)
            } else {
                UrgentPresentation(overlayController.window.showUrgentMessage(message, conversationId))
            }
        }
}
