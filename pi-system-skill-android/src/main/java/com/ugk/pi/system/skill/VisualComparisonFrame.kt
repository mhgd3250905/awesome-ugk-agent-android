package com.ugk.pi.system.skill

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Bounded local pixel evidence; never sent back to the model. No age-based expiry. */
internal class VisualComparisonFrame(
    val width: Int,
    val height: Int,
    private val pixels: IntArray,
    private val statusBarHeight: Int = 0
) {
    fun matches(current: VisualComparisonFrame, target: ScreenVisualTarget): Boolean {
        if (width != current.width || height != current.height ||
            pixels.size != width * height || current.pixels.size != pixels.size ||
            statusBarHeight != current.statusBarHeight) return false
        fun regionMatches(left: Int, top: Int, right: Int, bottom: Int, tolerance: Double): Boolean {
            val area = (right - left) * (bottom - top)
            if (area <= 0) return false
            var changed = 0
            for (y in top until bottom) for (x in left until right) {
                val before = pixels[y * width + x]
                val after = current.pixels[y * width + x]
                if (abs((before and 255) - (after and 255)) > 24 ||
                    abs(((before ushr 8) and 255) - ((after ushr 8) and 255)) > 24 ||
                    abs(((before ushr 16) and 255) - ((after ushr 16) and 255)) > 24) {
                    changed++
                    if (changed > area * tolerance) return false
                }
            }
            return true
        }
        // Check the target strictly, including the status bar if it is actually targeted.
        val left = floor(target.left * width).toInt().coerceIn(0, width - 1)
        val top = floor(target.top * height).toInt().coerceIn(0, height - 1)
        val right = ceil(target.right * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(target.bottom * height).toInt().coerceIn(top + 1, height)
        if (!regionMatches(left, top, right, bottom, 0.005)) return false
        // Local tiles prevent a small replaced/moved control disappearing in a whole-page average.
        // Only the OS status bar is excluded from page checks (clock/battery updates).
        for (y in statusBarHeight until height step 32) {
            for (x in 0 until width step 32) {
                if (!regionMatches(x, y, minOf(x + 32, width), minOf(y + 32, height), 0.02)) return false
            }
        }
        return true
    }
}
