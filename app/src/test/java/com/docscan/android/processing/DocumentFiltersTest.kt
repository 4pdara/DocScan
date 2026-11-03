package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test

class DocumentFiltersTest {
    private fun gray(value: Int) = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    @Test fun zeroStrengthPreservesAllChannelsExactly() {
        val original = intArrayOf(0x7F203040, 0xFFBBAA99.toInt(), 0xFFFFFFFF.toInt(), 0)
        for (filter in listOf(ScanFilter.CLEAN_COLOR, ScanFilter.CLEAN_TEXT)) {
            val output = original.copyOf(); DocumentFilters.apply(output, 2, 2, filter, 0)
            assertArrayEquals(original, output)
        }
    }

    @Test fun cleaningReducesUnevenPaperLightingAndRetainsInkAndColor() {
        val w = 320; val h = 240
        val original = IntArray(w * h) { gray(125 + it % w * 100 / w) }
        for (y in 80..160) for (x in 80..240) if (y % 18 < 3) original[y * w + x] = gray(25)
        original[120 * w + 160] = 0xFFBB2020.toInt()
        val output = original.copyOf(); DocumentFilters.apply(output, w, h, ScanFilter.CLEAN_COLOR, 100)
        val left = 40 * w + 40; val right = 40 * w + 280
        val before = (original[right] and 255) - (original[left] and 255)
        val after = (output[right] and 255) - (output[left] and 255)
        assertTrue("Lighting difference should decrease: $before -> $after", after < before / 2)
        assertTrue(output[90 * w + 160] and 255 < 65)
        val stamp = output[120 * w + 160]
        assertTrue((stamp ushr 16 and 255) > (stamp ushr 8 and 255) * 3)
    }

    @Test fun textEnhancementKeepsFaintInkInsteadOfThresholdingItAway() {
        val w = 120; val h = 120; val output = IntArray(w * h) { gray(200) }
        for (y in 45..48) for (x in 20..100) output[y * w + x] = gray(180)
        DocumentFilters.apply(output, w, h, ScanFilter.CLEAN_TEXT, 100)
        assertTrue(output[46 * w + 60] and 255 < 230)
        assertTrue(output[20 * w + 60] and 255 > 245)
    }

    @Test fun strengthControlsCleanupAndPreservesAlpha() {
        val source = IntArray(120 * 120) { i -> (gray(130 + i % 120 / 2) and 0xFFFFFF) or (0x7F shl 24) }
        val half = source.copyOf(); val full = source.copyOf()
        DocumentFilters.apply(half, 120, 120, ScanFilter.CLEAN_COLOR, 50)
        DocumentFilters.apply(full, 120, 120, ScanFilter.CLEAN_COLOR, 100)
        val at = 30 * 120 + 30
        assertTrue((half[at] and 255) > (source[at] and 255))
        assertTrue((half[at] and 255) < (full[at] and 255))
        assertEquals(0x7F, full[at] ushr 24)
    }
}
