package com.ugk.pi.android.testapp

import com.ugk.pi.attention.UrgentMessage
import com.ugk.pi.attention.UrgentMessagePresenter
import com.ugk.pi.attention.UrgentPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adapts the optional SDK capability to this app's existing process-owned window. */
internal class DemoUrgentMessagePresenter(
    private val overlayController: DemoOverlayController
) : UrgentMessagePresenter {
    override suspend fun show(message: UrgentMessage): UrgentPresentation =
        withContext(Dispatchers.Main.immediate) {
            UrgentPresentation(overlayController.window.showUrgentMessage(message))
        }
}
