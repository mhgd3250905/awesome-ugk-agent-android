package com.ugk.pi.android.testapp

import android.content.Context

/**
 * Process-owned overlay graph. The window is created once and its callbacks
 * always route through the current owner binding.
 */
class DemoOverlayController(context: Context) {

    private val appContext = context.applicationContext
    private val commandRouter = DemoOverlayCommandRouter()

    private var teachingCommands: DemoOverlayCommands? = null

    fun setTeachingCommands(commands: DemoOverlayCommands?) { teachingCommands = commands }

    internal var onUrgentInteraction: ((DemoUrgentInteraction) -> Boolean)? = null

    val window: AgentFloatingWindow by lazy {
        AgentFloatingWindow(appContext).apply {
            onSendMessage = { text ->
                val teaching = teachingCommands
                // A teaching rejection belongs to this input; never retry it in ordinary chat.
                if (teaching != null) teaching.onSend(text) else commandRouter.send(text)
            }
            onStopAgent = { teachingCommands?.let { it.onStop() } ?: commandRouter.stop() }
            onOpenApp = { teachingCommands?.let { it.onOpenApp() } ?: commandRouter.openApp() }
            onHide = { teachingCommands?.let { it.onHide() } ?: commandRouter.hide() }
            onDraftChanged = { value -> teachingCommands?.let { it.onDraftChanged(value) } ?: commandRouter.draftChanged(value) }
            onUrgentInteraction = { event ->
                this@DemoOverlayController.onUrgentInteraction?.invoke(event) == true
            }
        }
    }

    fun bindCommands(owner: Any, commands: DemoOverlayCommands) {
        commandRouter.bind(owner, commands)
    }

    fun setFallbackCommands(commands: DemoOverlayCommands) {
        commandRouter.setFallback(commands)
    }

    fun unbindCommands(owner: Any) {
        commandRouter.unbind(owner)
    }
}
