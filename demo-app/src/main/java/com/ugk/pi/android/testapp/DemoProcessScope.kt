package com.ugk.pi.android.testapp

import android.content.Context

/**
 * Composition root for the demo process.
 *
 * The Application owns one instance, while durable storage and the actual
 * overlay window remain lazy until a caller needs them.
 */
class DemoProcessScope private constructor(context: Context) {

    private val appContext = context.applicationContext

    val runCoordinator: DemoAgentRunCoordinator = DemoAgentRunCoordinator(
        sessionFinalizer = { session -> DemoActivityState.boundSession(session) }
    )
    val confirmationPresenter: ActivityUserConfirmationDialogPresenter =
        ActivityUserConfirmationDialogPresenter()
    val conversationStore: DemoConversationStore by lazy {
        DemoConversationStore(appContext)
    }
    val overlayController: DemoOverlayController = DemoOverlayController(appContext)

    companion object {
        @Volatile
        private var shared: DemoProcessScope? = null

        fun get(context: Context): DemoProcessScope {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: DemoProcessScope(context.applicationContext).also { shared = it }
            }
        }
    }
}
