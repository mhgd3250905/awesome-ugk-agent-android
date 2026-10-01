package com.ugk.pi.system.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualComparisonFrameTest {
    private val target = ScreenVisualTarget(0.4, 0.4, 0.6, 0.6)
    private fun frame(pixels: IntArray = IntArray(10000) { 0xffffff }, bar: Int = 5) =
        VisualComparisonFrame(100, 100, pixels, bar)

    @Test fun identicalFrameMatchesWithoutClockOrAgeExpiry() {
        assertTrue(frame().matches(frame(), target))
    }
    @Test fun smallReplacementInsideTargetDoesNotMatch() {
        val pixels = IntArray(10000) { 0xffffff }
        for (x in 45..48) pixels[45 * 100 + x] = 0
        assertFalse(frame().matches(frame(pixels), target))
    }
    @Test fun pageChangeOutsideTargetDoesNotMatch() {
        val pixels = IntArray(10000) { 0xffffff }
        for (y in 70..90) for (x in 70..90) pixels[y * 100 + x] = 0
        assertFalse(frame().matches(frame(pixels), target))
    }
    @Test fun statusClockChangeIsAllowedUnlessTargeted() {
        val pixels = IntArray(10000) { 0xffffff }
        for (x in 5..15) pixels[100 + x] = 0
        assertTrue(frame().matches(frame(pixels), target))
        assertFalse(frame().matches(frame(pixels), ScreenVisualTarget(0.0, 0.0, 0.2, 0.05)))
    }
    @Test fun tinyColorNoiseIsAllowed() {
        assertTrue(frame().matches(frame(IntArray(10000) { 0xf0f0f0 }), target))
    }
    @Test fun changedDimensionsOrInsetsDoNotMatch() {
        assertFalse(frame().matches(VisualComparisonFrame(50, 200, IntArray(10000)), target))
        assertFalse(frame().matches(frame(bar = 0), target))
    }
}
