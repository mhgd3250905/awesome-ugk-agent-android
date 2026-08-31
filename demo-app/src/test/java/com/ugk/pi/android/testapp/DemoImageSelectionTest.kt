package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoImageSelectionTest {

    @Test
    fun resolveImageSelectionQuotaWhenUnderLimit() {
        val incoming = listOf("uri://1", "uri://2")
        val result = resolveImageSelectionQuota(currentCount = 1, incoming = incoming, maxLimit = 4)

        assertEquals(listOf("uri://1", "uri://2"), result.accepted)
        assertEquals(0, result.ignoredCount)
        assertFalse(result.isOverQuota)
    }

    @Test
    fun resolveImageSelectionQuotaWhenExactlyReachingLimit() {
        val incoming = listOf("uri://1", "uri://2")
        val result = resolveImageSelectionQuota(currentCount = 2, incoming = incoming, maxLimit = 4)

        assertEquals(listOf("uri://1", "uri://2"), result.accepted)
        assertEquals(0, result.ignoredCount)
        assertFalse(result.isOverQuota)
    }

    @Test
    fun resolveImageSelectionQuotaWhenExceedingLimit() {
        val incoming = listOf("uri://1", "uri://2", "uri://3")
        val result = resolveImageSelectionQuota(currentCount = 2, incoming = incoming, maxLimit = 4)

        assertEquals(listOf("uri://1", "uri://2"), result.accepted)
        assertEquals(1, result.ignoredCount)
        assertTrue(result.isOverQuota)
    }

    @Test
    fun resolveImageSelectionQuotaWhenAlreadyFull() {
        val incoming = listOf("uri://1", "uri://2")
        val result = resolveImageSelectionQuota(currentCount = 4, incoming = incoming, maxLimit = 4)

        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.ignoredCount)
        assertTrue(result.isOverQuota)
    }

    @Test
    fun resolveImageSelectionQuotaWithEmptyIncoming() {
        val incoming = emptyList<String>()
        val result = resolveImageSelectionQuota(currentCount = 2, incoming = incoming, maxLimit = 4)

        assertTrue(result.accepted.isEmpty())
        assertEquals(0, result.ignoredCount)
        assertFalse(result.isOverQuota)
    }

    @Test
    fun resolveDefaultImagePromptTextForSingleAndMultiple() {
        assertEquals("请分析并识别这张图片", resolveDefaultImagePromptText(0))
        assertEquals("请分析并识别这张图片", resolveDefaultImagePromptText(1))
        assertEquals("请分析并识别这些图片", resolveDefaultImagePromptText(2))
        assertEquals("请分析并识别这些图片", resolveDefaultImagePromptText(3))
        assertEquals("请分析并识别这些图片", resolveDefaultImagePromptText(4))
    }

    @Test
    fun calculateInSampleSizeKeepsSmallImagesAndHandlesInvalidBounds() {
        assertEquals(1, calculateInSampleSize(100, 80, 100, 100))
        assertEquals(1, calculateInSampleSize(0, 200, 100, 100))
        assertEquals(1, calculateInSampleSize(200, 200, 0, 100))
    }

    @Test
    fun calculateInSampleSizeUsesPowerOfTwoAtExactBoundaries() {
        assertEquals(2, calculateInSampleSize(399, 399, 100, 100))
        assertEquals(4, calculateInSampleSize(400, 400, 100, 100))
        // Sampling by the longest side also bounds a very wide source whose
        // short side is smaller than the thumbnail target.
        assertEquals(64, calculateInSampleSize(10_000, 100, 100, 100))
    }

    @Test
    fun calculateBitmapTargetSizeScalesWithoutUpscalingAndStaysWithinHardLimit() {
        assertEquals(BitmapTargetSize(256, 192), calculateBitmapTargetSize(4000, 3000, 256))
        assertEquals(BitmapTargetSize(192, 384), calculateBitmapTargetSize(300, 600, 384))
        assertEquals(BitmapTargetSize(128, 96), calculateBitmapTargetSize(128, 96, 256))
        assertEquals(BitmapTargetSize(0, 0), calculateBitmapTargetSize(0, 100, 256))
    }
}
