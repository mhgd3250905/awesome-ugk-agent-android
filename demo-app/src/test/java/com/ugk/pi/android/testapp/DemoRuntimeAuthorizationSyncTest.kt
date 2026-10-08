package com.ugk.pi.android.testapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability plugins add `show_user_confirmation_dialog` only when confirmation is not
 * bypassed, and that decision runs once, inside `AgentRuntime`'s build. The protected Tools
 * read the same stored preference live, on every call. So a runtime built while the switch was
 * on holds protected Tools that answer with the missing-confirmation wording, which tells the
 * model to call a Tool it was never given - and before this rule existed, no resume corrected
 * it, so the dead end lasted until the process restarted.
 *
 * The cure must not be worse than the disease: `rebuildRuntime` starts with
 * `stopAgent(clearQueuedMessages = true)`, which cancels pending urgent interactions,
 * discards queued follow-up messages and stops the in-flight turn. None of that belongs to
 * authorization, so a mismatch detected while something is running stays pending and is
 * applied at the next run start instead. `MainActivity.syncAuthorizationMode()` is the only
 * caller of this decision; it is split out because the decision itself is three booleans and
 * the interruption it refuses is not observable from a JVM test.
 */
class DemoRuntimeAuthorizationSyncTest {

    /**
     * Control: a matching preference and a matching built runtime rebuild nothing.
     *
     * The `inFlight` sweep here carries no judgment - with `applied == current` the function
     * returns false before the flag can matter. It is kept as the exhaustive corner of the
     * truth table, not as a pin; the load-bearing arms are
     * aMismatchNeverStopsATurnThatIsAlreadyRunning and the two idle-rebuild cases.
     */
    @Test
    fun anAppliedModeThatMatchesThePreferenceRebuildsNothing() {
        listOf(true, false).forEach { mode ->
            listOf(false, true).forEach { inFlight ->
                assertFalse(
                    "mode=$mode inFlight=$inFlight",
                    DemoRuntimeAuthorizationSync.shouldRebuild(
                        runtimeExists = true,
                        applied = mode,
                        current = mode,
                        runInFlight = inFlight
                    )
                )
            }
        }
    }

    /** Control: with no runtime there is nothing to correct; the CREATE branch owns it. */
    @Test
    fun nothingIsRebuiltBeforeARuntimeExists() {
        assertFalse(
            DemoRuntimeAuthorizationSync.shouldRebuild(
                runtimeExists = false,
                applied = null,
                current = false,
                runInFlight = false
            )
        )
    }

    @Test
    fun turningFullAuthorizationOffWhileIdleRebuildsTheStrandedRuntime() {
        assertTrue(
            "a runtime registered without the dialog Tool cannot answer a protected Tool once " +
                "confirmation is required again",
            DemoRuntimeAuthorizationSync.shouldRebuild(
                runtimeExists = true,
                applied = true,
                current = false,
                runInFlight = false
            )
        )
    }

    @Test
    fun turningFullAuthorizationOnWhileIdleAlsoResyncs() {
        assertTrue(
            DemoRuntimeAuthorizationSync.shouldRebuild(
                runtimeExists = true,
                applied = false,
                current = true,
                runInFlight = false
            )
        )
    }

    /**
     * The direction the first attempt of this fix got wrong: it folded the preference into the
     * provider-config identity, so every authorization toggle reached REBUILD, and REBUILD
     * calls `stopAgent(clearQueuedMessages = true)` - killing the user's current turn and
     * throwing away queued messages that have nothing to do with authorization.
     */
    @Test
    fun aMismatchNeverStopsATurnThatIsAlreadyRunning() {
        assertFalse(
            "a toggle pressed mid-turn must not destroy the turn, the queued follow-ups or a " +
                "waiting timer; it is applied by the next run start instead",
            DemoRuntimeAuthorizationSync.shouldRebuild(
                runtimeExists = true,
                applied = true,
                current = false,
                runInFlight = true
            )
        )
    }

    /** A runtime whose mode was never recorded is treated as a mismatch, not as a match. */
    @Test
    fun anUnrecordedModeIsCorrectedWhenIdleAndLeftAloneWhileRunning() {
        assertTrue(
            DemoRuntimeAuthorizationSync.shouldRebuild(
                runtimeExists = true,
                applied = null,
                current = true,
                runInFlight = false
            )
        )
        assertFalse(
            DemoRuntimeAuthorizationSync.shouldRebuild(
                runtimeExists = true,
                applied = null,
                current = true,
                runInFlight = true
            )
        )
    }
}
