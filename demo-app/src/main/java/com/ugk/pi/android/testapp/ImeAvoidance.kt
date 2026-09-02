package com.ugk.pi.android.testapp

/**
 * Pure IME avoidance math for the expanded Agent overlay window.
 *
 * Works only in screen pixel integers so the shift policy stays testable
 * without Android.
 */
object ImeAvoidance {

    /** Avoidance outcome: the target window top and the target window height. */
    data class Decision(val targetY: Int, val targetHeight: Int)

    /**
     * Target window top that keeps the window bottom [margin] px above the
     * IME, or [windowTop] itself when the IME does not overlap the window.
     *
     * @param imeTop screen-space top edge of the IME, or [Int.MAX_VALUE]
     * when no IME is visible.
     * @param minY lower clamp bound for the window top; the shift never
     * pushes the window below it even if the IME still overlaps.
     */
    fun targetY(
        windowTop: Int,
        windowHeight: Int,
        imeTop: Int,
        minY: Int,
        margin: Int
    ): Int = avoidanceDecision(
        windowTop = windowTop,
        windowHeight = windowHeight,
        imeTop = imeTop,
        minY = minY,
        margin = margin,
        minHeight = 0,
        maxHeight = Int.MAX_VALUE
    ).targetY

    /**
     * Combined (y, height) avoidance decision.
     *
     * A plain overlap is solved by shifting the window up. When the required
     * shift is clamped by [minY] and the window bottom would still sit under
     * the IME, the height is compressed instead so the window bottom lands
     * [margin] px above the IME. The compressed height stays within
     * [minHeight]..[maxHeight] and never grows beyond [windowHeight] (the
     * shell guarantees [minHeight] <= [windowHeight]; an inverted pair would
     * let [minHeight] win and grow the window); when even [minHeight] does
     * not fit between [minY] and the IME (tiny screens or landscape), the
     * residual overlap is accepted as best effort.
     *
     * @param imeTop screen-space top edge of the IME, or [Int.MAX_VALUE]
     * when no IME is visible.
     */
    fun avoidanceDecision(
        windowTop: Int,
        windowHeight: Int,
        imeTop: Int,
        minY: Int,
        margin: Int,
        minHeight: Int,
        maxHeight: Int
    ): Decision {
        val overlap = windowTop + windowHeight - imeTop
        if (overlap <= 0) return Decision(windowTop, windowHeight)
        val targetY = maxOf(minY, windowTop - overlap - margin)
        if (targetY + windowHeight <= imeTop) return Decision(targetY, windowHeight)
        val compressedHeight = imeTop - margin - targetY
        val targetHeight = maxOf(
            minHeight,
            minOf(compressedHeight, windowHeight, maxHeight)
        )
        return Decision(targetY, targetHeight)
    }

    /**
     * Maps the IME bottom inset dispatched to the overlay window into the
     * IME top edge expressed in the parent space in which the overlay's y
     * is set. The window root sits at offset (0, 0) inside the window, so
     * the inset counts upward from the window's bottom edge. An inset of 0
     * maps to the window bottom, which [targetY] then treats as no overlap.
     */
    fun imeTopParent(windowTop: Int, windowHeight: Int, imeBottomInset: Int): Int =
        windowTop + windowHeight - imeBottomInset
}
