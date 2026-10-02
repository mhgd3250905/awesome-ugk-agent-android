package com.ugk.pi.android.testapp

import com.ugk.pi.attention.UrgentPresentationBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [DemoUrgentInteractionLedger], the bookkeeping behind
 * `DemoUrgentInteractionDispatcher.submit()`.
 *
 * The defect this rule fixes: an accepted action whose event was later dropped -
 * because the screen operation interlock took over while it waited
 * (`drain()`'s first branch called `cancelPending()`), or because the user
 * pressed stop - left its `presentationId` in the remembered set while the queue
 * was emptied. Every later tap on that same urgent screen was then refused at
 * the deduplication check, with no log, no retry and no way to clear it short of
 * 32 further events pushing the id out of the set. The overlay had already told
 * the user the action did not go through.
 *
 * Reverting [DemoUrgentInteractionLedger.discardAll] to `pending.clear()` - the
 * shape before this file - turns `droppedActionsBecomeTappableAgain` red, and
 * removing the release in [DemoUrgentInteractionLedger.discard] turns
 * `queuedAndDeliveredIdsAreRefusedWhileDroppedOnesAreNot` red. Both were measured.
 */
class DemoUrgentInteractionLedgerTest {
    @Test
    fun queuedAndDeliveredIdsAreRefusedWhileDroppedOnesAreNot() {
        val ledger = DemoUrgentInteractionLedger()
        val queued = event("presentation-queued")
        val delivered = event("presentation-delivered")
        val dropped = event("presentation-dropped")
        listOf(queued, delivered, dropped).forEach { ledger.reserve(it) }
        assertEquals(3, ledger.pendingCount())

        assertFalse(
            "a queued id must not start a second run",
            ledger.isReservable(event("presentation-queued"))
        )

        ledger.deliver(delivered)
        assertFalse(
            "a delivered id stays consumed",
            ledger.isReservable(event("presentation-delivered"))
        )

        ledger.discard(dropped)
        assertTrue("a dropped id must be tappable again", ledger.isReservable(event("presentation-dropped")))
        ledger.reserve(event("presentation-dropped"))
        // queued + the re-reserved dropped one; the delivered one left the queue.
        assertEquals(2, ledger.pendingCount())
    }

    @Test
    fun droppedActionsBecomeTappableAgain() {
        val ledger = DemoUrgentInteractionLedger()
        val first = event("presentation-stop-1")
        val second = event("presentation-stop-2")
        ledger.reserve(first)
        ledger.reserve(second)

        // The stop button: everything still waiting behind the turn is thrown away.
        ledger.discardAll()

        assertFalse(ledger.hasPending())
        assertTrue("stop must not lock the first screen out of the demo forever", ledger.isReservable(first))
        assertTrue("nor the second", ledger.isReservable(second))
        ledger.reserve(first)
        ledger.reserve(second)
        assertEquals(2, ledger.pendingCount())
    }

    /**
     * `submit()` probes reservability *before* its own session and timer guards,
     * so a probe must not consume a slot in the bounded memory. A first draft of
     * the ledger accepted and then discarded, which - with the remembered set full -
     * evicted an older delivered screen's id on a submit that never happened.
     */
    @Test
    fun probingReservabilityDoesNotConsumeTheBoundedMemory() {
        val ledger = DemoUrgentInteractionLedger(maxPending = 8, maxRemembered = 3)
        val delivered = listOf(event("presentation-probe-1"), event("presentation-probe-2"), event("presentation-probe-3"))
        delivered.forEach { event ->
            ledger.reserve(event)
            ledger.deliver(event)
        }

        val probe = event("presentation-probe-new")
        repeat(3) {
            assertTrue("the probe must say the new screen is reservable", ledger.isReservable(probe))
        }
        delivered.forEach { event ->
            assertFalse(
                "probing must not push ${event.binding.presentationId} out of the remembered set",
                ledger.isReservable(event)
            )
        }

        ledger.reserve(probe)
        assertTrue(
            "the real reservation evicts the oldest delivered id, and nothing before it did",
            ledger.isReservable(delivered.first())
        )
    }

    @Test
    fun pendingCapacityIsBoundedAndFreesAfterDelivery() {
        val ledger = DemoUrgentInteractionLedger()
        val events = (1..6).map { index -> event("presentation-cap-$index") }

        // submit() probes and then reserves one event at a time, so the test
        // models the same order rather than probing the whole batch first.
        val admitted = mutableListOf<DemoUrgentInteraction>()
        events.forEach { candidate ->
            if (ledger.isReservable(candidate)) {
                ledger.reserve(candidate)
                admitted += candidate
            }
        }
        assertEquals(4, admitted.size)
        assertEquals(
            listOf("presentation-cap-1", "presentation-cap-2", "presentation-cap-3", "presentation-cap-4"),
            admitted.map { it.binding.presentationId }
        )

        val head = ledger.next()!!
        ledger.deliver(head)
        assertEquals(3, ledger.pendingCount())
        assertTrue("a freed slot admits the next tap", ledger.isReservable(events.last()))
    }

    @Test
    fun rememberedIdsRollOverAtTheBound() {
        val ledger = DemoUrgentInteractionLedger(maxPending = 64, maxRemembered = 3)
        val events = (1..4).map { index -> event("presentation-roll-$index") }
        events.forEach { event ->
            ledger.reserve(event)
            ledger.deliver(event)
        }

        // The three ids still inside the bound stay refused; a refusal changes
        // nothing, so they are probed before the one reservation that mutates.
        assertFalse("second delivered id is still remembered", ledger.isReservable(events[1]))
        assertFalse("third delivered id is still remembered", ledger.isReservable(events[2]))
        assertFalse("fourth delivered id is still remembered", ledger.isReservable(events[3]))
        assertTrue("the oldest delivered id rolled out of the bound", ledger.isReservable(events.first()))
    }

    @Test
    fun impossibleTransitionsFailLoudly() {
        val ledger = DemoUrgentInteractionLedger()
        val unqueued = event("presentation-never-queued")

        assertTrue(
            "discarding something not queued must not be silently ignored",
            runCatching { ledger.discard(unqueued) }.exceptionOrNull() is IllegalStateException
        )
        ledger.reserve(unqueued)
        assertTrue(
            "reserving twice must not be silently ignored either",
            runCatching { ledger.reserve(unqueued) }.exceptionOrNull() is IllegalStateException
        )
    }

    private fun event(presentationId: String) = DemoUrgentInteraction(
        binding = UrgentPresentationBinding(presentationId = presentationId, sessionId = "session-1"),
        conversationId = "conversation-1",
        title = "提醒",
        kind = DemoUrgentInteraction.Kind.BUTTON,
        controlId = "ok",
        controlLabel = "好的"
    )
}
