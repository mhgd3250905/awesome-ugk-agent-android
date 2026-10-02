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
 * shape before this file - turns the `droppedActionsBecomeTappableAgain` case
 * red, which is the discriminator for the whole rule.
 */
class DemoUrgentInteractionLedgerTest {
    @Test
    fun queuedAndDeliveredIdsAreRefusedWhileDroppedOnesAreNot() {
        val ledger = DemoUrgentInteractionLedger()
        val queued = event("presentation-queued")
        val delivered = event("presentation-delivered")
        val dropped = event("presentation-dropped")

        assertTrue(ledger.accept(queued))
        assertTrue(ledger.accept(delivered))
        assertTrue(ledger.accept(dropped))
        assertEquals(3, ledger.pendingCount())

        assertFalse("a queued id must not start a second run", ledger.accept(event("presentation-queued")))

        ledger.deliver(delivered)
        assertFalse("a delivered id stays consumed", ledger.accept(event("presentation-delivered")))

        ledger.discard(dropped)
        assertTrue("a dropped id must be tappable again", ledger.accept(event("presentation-dropped")))
        // queued + the re-accepted dropped one; the delivered one left the queue.
        assertEquals(2, ledger.pendingCount())
    }

    @Test
    fun droppedActionsBecomeTappableAgain() {
        val ledger = DemoUrgentInteractionLedger()
        val first = event("presentation-stop-1")
        val second = event("presentation-stop-2")
        assertTrue(ledger.accept(first))
        assertTrue(ledger.accept(second))

        // The stop button: everything still waiting behind the turn is thrown away.
        ledger.discardAll()

        assertFalse(ledger.hasPending())
        assertTrue("stop must not lock the first screen out of the demo forever", ledger.accept(first))
        assertTrue("nor the second", ledger.accept(second))
        assertEquals(2, ledger.pendingCount())
    }

    @Test
    fun pendingCapacityIsBoundedAndFreesAfterDelivery() {
        val ledger = DemoUrgentInteractionLedger()
        val events = (1..6).map { index -> event("presentation-cap-$index") }
        val accepted = events.map { ledger.accept(it) }

        assertEquals(listOf(true, true, true, true, false, false), accepted)

        val head = ledger.next()!!
        ledger.deliver(head)
        assertEquals(3, ledger.pendingCount())
        assertTrue("a freed slot admits the next tap", ledger.accept(events.last()))
    }

    @Test
    fun rememberedIdsRollOverAtTheBound() {
        val ledger = DemoUrgentInteractionLedger(maxPending = 64, maxRemembered = 3)
        val events = (1..4).map { index -> event("presentation-roll-$index") }
        events.forEach { event ->
            assertTrue(ledger.accept(event))
            ledger.deliver(event)
        }

        // The three ids still inside the bound stay refused; a refusal changes
        // nothing, so they are probed before the one acceptance that mutates the
        // set again.
        assertFalse("second delivered id is still remembered", ledger.accept(events[1]))
        assertFalse("third delivered id is still remembered", ledger.accept(events[2]))
        assertFalse("fourth delivered id is still remembered", ledger.accept(events[3]))
        assertTrue("the oldest delivered id rolled out of the bound", ledger.accept(events.first()))
    }

    @Test
    fun discardingSomethingNotQueuedFailsLoudly() {
        val ledger = DemoUrgentInteractionLedger()
        val thrown = runCatching { ledger.discard(event("presentation-never-queued")) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalStateException)
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
