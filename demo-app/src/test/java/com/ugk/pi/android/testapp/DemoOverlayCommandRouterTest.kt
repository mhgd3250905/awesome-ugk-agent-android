package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoOverlayCommandRouterTest {

    @Test
    fun sendReturnsAndForwardsTheCurrentOwnerResult() {
        val router = DemoOverlayCommandRouter()
        val owner = Any()
        val received = mutableListOf<String>()

        router.bind(
            owner = owner,
            commands = DemoOverlayCommands(
                onSend = { text ->
                    received += text
                    text == "hello"
                },
                onStop = {},
                onOpenApp = {},
                onHide = {},
                onDraftChanged = {}
            )
        )

        assertTrue(router.send("hello"))
        assertFalse(router.send("reject"))
        assertEquals(listOf("hello", "reject"), received)

        router.unbind(owner)
        assertFalse(router.send("after-unbind"))
    }

    @Test
    fun forwardsEveryNonSendCommandToTheCurrentOwner() {
        val router = DemoOverlayCommandRouter()
        val calls = mutableListOf<String>()

        router.bind(
            owner = Any(),
            commands = DemoOverlayCommands(
                onSend = { true },
                onStop = { calls += "stop" },
                onOpenApp = { calls += "open" },
                onHide = { calls += "hide" },
                onDraftChanged = { value -> calls += "draft:$value" }
            )
        )

        router.stop()
        router.openApp()
        router.hide()
        router.draftChanged("draft")

        assertEquals(listOf("stop", "open", "hide", "draft:draft"), calls)
    }

    @Test
    fun draftChangesRemainRoutableUntilTheOwnerIsUnbound() {
        val router = DemoOverlayCommandRouter()
        val owner = Any()
        val drafts = mutableListOf<String>()

        router.bind(
            owner = owner,
            commands = commands(onSend = { false }).copy(
                onDraftChanged = { drafts += it }
            )
        )

        router.draftChanged("saved-by-hide")
        router.unbind(owner)
        router.draftChanged("ignored-after-unbind")

        assertEquals(listOf("saved-by-hide"), drafts)
    }

    @Test
    fun replacementRoutesCommandsOnlyToTheNewestOwner() {
        val router = DemoOverlayCommandRouter()
        val calls = mutableListOf<String>()

        router.bind(
            owner = Any(),
            commands = DemoOverlayCommands(
                onSend = { calls += "old-send"; false },
                onStop = { calls += "old-stop" },
                onOpenApp = {},
                onHide = {},
                onDraftChanged = {}
            )
        )
        router.bind(
            owner = Any(),
            commands = DemoOverlayCommands(
                onSend = { calls += "new-send"; true },
                onStop = { calls += "new-stop" },
                onOpenApp = {},
                onHide = {},
                onDraftChanged = {}
            )
        )

        assertEquals(true, router.send("hello"))
        router.stop()

        assertEquals(listOf("new-send", "new-stop"), calls)
    }

    @Test
    fun oldOwnerUnbindCannotClearAReplacementBinding() {
        val router = DemoOverlayCommandRouter()
        val oldOwner = EqualOwner("same")
        val newOwner = EqualOwner("same")

        router.bind(
            owner = oldOwner,
            commands = commands(onSend = { false })
        )
        router.bind(
            owner = newOwner,
            commands = commands(onSend = { true })
        )

        router.unbind(oldOwner)

        assertEquals(true, router.send("still-current"))
    }

    private fun commands(onSend: (String) -> Boolean): DemoOverlayCommands =
        DemoOverlayCommands(
            onSend = onSend,
            onStop = {},
            onOpenApp = {},
            onHide = {},
            onDraftChanged = {}
        )

    private data class EqualOwner(val value: String)
}
