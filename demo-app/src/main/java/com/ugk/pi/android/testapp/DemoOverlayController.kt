package com.ugk.pi.android.testapp

import android.content.Context

/**
 * Process-owned overlay graph. The window is created once and its callbacks
 * always route through the current owner binding.
 */
class DemoOverlayController(context: Context) {

    private val appContext = context.applicationContext
    private val commandRouter = DemoOverlayCommandRouter()

    val window: AgentFloatingWindow by lazy {
        AgentFloatingWindow(appContext).apply {
            onSendMessage = { text -> commandRouter.send(text) }
            onStopAgent = { commandRouter.stop() }
            onOpenApp = { commandRouter.openApp() }
            onHide = { commandRouter.hide() }
            onDraftChanged = { value -> commandRouter.draftChanged(value) }
        }
    }

    fun bindCommands(owner: Any, commands: DemoOverlayCommands) {
        commandRouter.bind(owner, commands)
    }

    fun unbindCommands(owner: Any) {
        commandRouter.unbind(owner)
    }
}
