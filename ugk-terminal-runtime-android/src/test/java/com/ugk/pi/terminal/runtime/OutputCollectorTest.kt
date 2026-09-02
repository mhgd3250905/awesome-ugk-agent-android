package com.ugk.pi.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte cap of [OutputCollector] can split a multi-byte UTF-8 code point,
 * either inside the chunk that reaches the limit or right before a later
 * chunk that is dropped whole. text() must trim the dangling partial code
 * point while truncated keeps reporting the data loss.
 */
class OutputCollectorTest {
    @Test
    fun truncatingInsideAnEmojiKeepsOnlyCompleteCodePoints() {
        val collector = OutputCollector(limitBytes = 4)

        // 'a' 'b' then a 4-byte emoji then 'c' 'd'.
        collector.append("ab\ud83d\ude00cd".toByteArray(Charsets.UTF_8), 9)

        assertTrue(collector.truncated)
        assertEquals("ab", collector.text())
        assertFalse(collector.text().contains('\uFFFD'))
    }

    @Test
    fun emojiCompletingExactlyAtTheLimitSurvivesALaterDroppedChunk() {
        val collector = OutputCollector(limitBytes = 6)
        val chunk = "ab\ud83d\ude00".toByteArray(Charsets.UTF_8)

        collector.append(chunk, chunk.size)
        assertFalse(collector.truncated)
        collector.append("x".toByteArray(Charsets.UTF_8), 1)

        assertTrue(collector.truncated)
        assertEquals("ab\ud83d\ude00", collector.text())
    }

    @Test
    fun straddlingAnEmojiAcrossAppendCallsDropsOnlyThePartialCodePoint() {
        val collector = OutputCollector(limitBytes = 5)

        val chunk = "ab\ud83d\ude00cd".toByteArray(Charsets.UTF_8)
        collector.append(chunk, chunk.size)

        assertTrue(collector.truncated)
        assertEquals("ab", collector.text())
    }

    @Test
    fun truncationBetweenTwoMultiByteCharactersKeepsTheCompleteOne() {
        val collector = OutputCollector(limitBytes = 7)

        val chunk = "ab\ud83d\ude00ef".toByteArray(Charsets.UTF_8)
        collector.append(chunk, chunk.size)

        assertTrue(collector.truncated)
        assertEquals("ab\ud83d\ude00e", collector.text())
    }

    @Test
    fun splittingATwoByteCharacterDropsTheWholePartialSequence() {
        val collector = OutputCollector(limitBytes = 3)

        val chunk = "abé".toByteArray(Charsets.UTF_8)
        collector.append(chunk, chunk.size)

        assertTrue(collector.truncated)
        assertEquals("ab", collector.text())
    }

    @Test
    fun untruncatedUtf8OutputPassesThroughExactly() {
        val collector = OutputCollector(limitBytes = 64)
        val text = "héllo \ud83d\ude00 终端"

        val bytes = text.toByteArray(Charsets.UTF_8)
        collector.append(bytes, bytes.size)

        assertFalse(collector.truncated)
        assertEquals(text, collector.text())
    }

    @Test
    fun asciiTruncationKeepsExactlyTheLimitBytes() {
        val collector = OutputCollector(limitBytes = 4)

        val bytes = "abcdef".toByteArray(Charsets.US_ASCII)
        collector.append(bytes, bytes.size)

        assertTrue(collector.truncated)
        assertEquals("abcd", collector.text())
    }

    @Test
    fun completeUtf8PrefixLengthHandlesBoundaryEdges() {
        // Empty capture.
        assertEquals(0, OutputCollector.completeUtf8PrefixLength(ByteArray(0)))
        // Pure ASCII ends on a boundary.
        assertEquals(4, OutputCollector.completeUtf8PrefixLength(byteArrayOf(0x61, 0x62, 0x63, 0x64)))
        // Complete 4-byte emoji.
        assertEquals(
            4,
            OutputCollector.completeUtf8PrefixLength(
                byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte(), 0x80.toByte())
            )
        )
        // Lead byte alone announces 4 bytes but only 1 was captured.
        assertEquals(0, OutputCollector.completeUtf8PrefixLength(byteArrayOf(0xf0.toByte())))
        // Orphan continuation bytes at the buffer start cannot form a prefix.
        assertEquals(
            0,
            OutputCollector.completeUtf8PrefixLength(byteArrayOf(0x9f.toByte(), 0x98.toByte()))
        )
        // Complete 3-byte character.
        assertEquals(
            3,
            OutputCollector.completeUtf8PrefixLength(
                byteArrayOf(0xe4.toByte(), 0xb8.toByte(), 0xad.toByte())
            )
        )
    }
}
