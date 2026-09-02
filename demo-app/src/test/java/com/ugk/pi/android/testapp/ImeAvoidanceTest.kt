package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeAvoidanceTest {

    @Test
    fun noOverlapKeepsWindowTop() {
        assertEquals(
            100,
            ImeAvoidance.targetY(
                windowTop = 100,
                windowHeight = 400,
                imeTop = 600,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun hiddenImeSentinelKeepsWindowTop() {
        assertEquals(
            100,
            ImeAvoidance.targetY(
                windowTop = 100,
                windowHeight = 400,
                imeTop = Int.MAX_VALUE,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun touchingImeBoundaryDoesNotMove() {
        // window bottom == imeTop means zero overlap.
        assertEquals(
            500,
            ImeAvoidance.targetY(
                windowTop = 500,
                windowHeight = 200,
                imeTop = 700,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun partialOverlapShiftsByOverlapPlusMargin() {
        // bottom = 900, imeTop = 700 -> overlap 200 -> shift 200 + 8.
        assertEquals(
            292,
            ImeAvoidance.targetY(
                windowTop = 500,
                windowHeight = 400,
                imeTop = 700,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun shiftNeverGoesBelowMinY() {
        // 200 - (300 + 8) would be negative; the clamp bound wins.
        assertEquals(
            48,
            ImeAvoidance.targetY(
                windowTop = 200,
                windowHeight = 400,
                imeTop = 300,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun fullyCoveredWindowLandsAboveIme() {
        // windowTop itself is below imeTop; the result keeps the bottom at
        // imeTop - margin.
        assertEquals(
            592,
            ImeAvoidance.targetY(
                windowTop = 1400,
                windowHeight = 600,
                imeTop = 1200,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun tallCoveredWindowStillClampsToMinY() {
        assertEquals(
            48,
            ImeAvoidance.targetY(
                windowTop = 1000,
                windowHeight = 2000,
                imeTop = 1200,
                minY = 48,
                margin = 8
            )
        )
    }

    @Test
    fun marginAddsClearanceAboveIme() {
        // overlap = 200 in both cases; only the margin differs.
        assertEquals(
            292,
            ImeAvoidance.targetY(
                windowTop = 500,
                windowHeight = 400,
                imeTop = 700,
                minY = 48,
                margin = 8
            )
        )
        assertEquals(
            300,
            ImeAvoidance.targetY(
                windowTop = 500,
                windowHeight = 400,
                imeTop = 700,
                minY = 48,
                margin = 0
            )
        )
    }

    @Test
    fun imeTopParentMapsWindowInsetIntoParentSpace() {
        // Window occupies [500, 900] in parent space; 100 px of its bottom
        // sit under the IME, so the IME top edge is at parent y = 800.
        assertEquals(
            800,
            ImeAvoidance.imeTopParent(
                windowTop = 500,
                windowHeight = 400,
                imeBottomInset = 100
            )
        )
    }

    @Test
    fun zeroInsetMapsToWindowBottomAndYieldsNoMovement() {
        // A zero inset means the IME does not overlap the window: the
        // mapped imeTop equals the window bottom and targetY keeps the
        // current position.
        val imeTop = ImeAvoidance.imeTopParent(
            windowTop = 500,
            windowHeight = 400,
            imeBottomInset = 0
        )
        assertEquals(900, imeTop)
        assertEquals(500, ImeAvoidance.targetY(500, 400, imeTop, 48, 8))
    }

    @Test
    fun fullCoverInsetKeepsCoordinatesSelfConsistent() {
        // The whole window sits below the IME top edge: the inset equals
        // the window height and the mapping round-trips to windowTop.
        val imeTop = ImeAvoidance.imeTopParent(
            windowTop = 1400,
            windowHeight = 600,
            imeBottomInset = 600
        )
        assertEquals(1400, imeTop)
        assertEquals(792, ImeAvoidance.targetY(1400, 600, imeTop, 48, 8))
    }

    @Test
    fun insetToTargetRoundTripLandsAboveImeWithMargin() {
        val imeTop = ImeAvoidance.imeTopParent(
            windowTop = 1000,
            windowHeight = 500,
            imeBottomInset = 260
        )
        assertEquals(1240, imeTop)
        // Shifted bottom must sit at imeTop - margin.
        val targetY = ImeAvoidance.targetY(1000, 500, imeTop, 48, 8)
        assertEquals(1240 - 8, targetY + 500)
    }
}
