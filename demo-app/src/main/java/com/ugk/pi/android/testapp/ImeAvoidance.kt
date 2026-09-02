package com.ugk.pi.android.testapp

/**
 * Pure IME avoidance math for the expanded Agent overlay window.
 *
 * Works only in screen pixel integers so the shift policy stays testable
 * without Android.
 */
object ImeAvoidance {

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
    ): Int {
        val overlap = windowTop + windowHeight - imeTop
        if (overlap <= 0) return windowTop
        return maxOf(minY, windowTop - overlap - margin)
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
