package com.ugk.pi.android.testapp

/** Commands owned by the Activity currently attached to the overlay. */
data class DemoOverlayCommands(
    val onSend: (String) -> Boolean,
    val onStop: () -> Unit,
    val onOpenApp: () -> Unit,
    val onHide: () -> Unit,
    val onDraftChanged: (String) -> Unit
)

/**
 * Routes overlay intents to the current owner without retaining Activity
 * callbacks in conversation state.
 */
class DemoOverlayCommandRouter {

    private data class Binding(
        val owner: Any,
        val commands: DemoOverlayCommands
    )

    private var binding: Binding? = null

    @Synchronized
    fun bind(owner: Any, commands: DemoOverlayCommands) {
        binding = Binding(owner, commands)
    }

    @Synchronized
    fun unbind(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    fun send(text: String): Boolean = commands()?.onSend?.invoke(text) == true

    fun stop() {
        commands()?.onStop?.invoke()
    }

    fun openApp() {
        commands()?.onOpenApp?.invoke()
    }

    fun hide() {
        commands()?.onHide?.invoke()
    }

    fun draftChanged(value: String) {
        commands()?.onDraftChanged?.invoke(value)
    }

    @Synchronized
    private fun commands(): DemoOverlayCommands? = binding?.commands
}
