package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The floating transcript reuses its child views in place, so the first render
 * of a conversation (and every render that adds a message) must attach views to
 * a group that does not hold them yet.
 */
@RunWith(AndroidJUnit4::class)
class AgentOverlayTranscriptRenderInstrumentedTest {

    @Test
    fun firstRenderWithMessagesAttachesEveryMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = AgentOverlayTranscriptView(context)

        val changed = view.render(
            snapshot(
                AgentOverlayMessage("m1", "user", "第一条"),
                AgentOverlayMessage("m2", "assistant", "第二条")
            ),
            assistantPreview = null
        )

        assertEquals(2, view.childCount)
        assertEquals(true, changed)
    }

    @Test
    fun renderAfterOneMessageWasAddedKeepsChildOrderAlignedWithContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = AgentOverlayTranscriptView(context)
        view.render(snapshot(AgentOverlayMessage("m1", "user", "第一条")), null)

        view.render(
            snapshot(
                AgentOverlayMessage("m1", "user", "第一条"),
                AgentOverlayMessage("m2", "assistant", "第二条")
            ),
            null
        )

        assertEquals(2, view.childCount)
        assertEquals("overlay-message:m1", view.getChildAt(0).tag)
        assertEquals("overlay-message:m2", view.getChildAt(1).tag)
    }

    private fun snapshot(vararg messages: AgentOverlayMessage) = AgentOverlaySnapshot(
        title = "Agent",
        statusLabel = "空闲",
        conversationId = "conversation_1",
        runId = "run_1",
        messages = messages.toList()
    )
}
