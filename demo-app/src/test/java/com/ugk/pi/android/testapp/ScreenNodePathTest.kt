package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ScreenPerformActionTool resolves nodeId strings produced by
 * screen_read_ui_tree. A malformed path must resolve to nothing: silently
 * coercing it to window 0 or dropping segments would target an unrelated
 * node while reporting the requested one.
 */
class ScreenNodePathTest {

    @Test
    fun parsesRootOnlyNodeIds() {
        assertEquals(ScreenNodePath(0, emptyList()), parseScreenNodePath("0"))
        assertEquals(ScreenNodePath(3, emptyList()), parseScreenNodePath("3"))
    }

    @Test
    fun parsesChildPaths() {
        assertEquals(ScreenNodePath(0, listOf(1, 2)), parseScreenNodePath("0.1.2"))
        assertEquals(ScreenNodePath(2, listOf(0, 5, 11)), parseScreenNodePath("2.0.5.11"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(ScreenNodePath(0, listOf(1)), parseScreenNodePath(" 0.1 "))
    }

    @Test
    fun rejectsMalformedNodeIds() {
        assertNull(parseScreenNodePath(""))
        assertNull(parseScreenNodePath("   "))
        assertNull(parseScreenNodePath("abc"))
        assertNull(parseScreenNodePath("0.1.bad.2"))
        assertNull(parseScreenNodePath("0..1"))
        assertNull(parseScreenNodePath("0.1.2.99999x"))
    }

    @Test
    fun rejectsNegativeSegments() {
        assertNull(parseScreenNodePath("-1.2"))
        assertNull(parseScreenNodePath("0.-1"))
    }
}
