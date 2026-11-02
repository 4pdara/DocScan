package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test

class PixelFiltersTest {
    @Test fun originalPreservesEveryChannel() {
        val input = 0x7F123456
        assertEquals(input, PixelFilters.apply(input, ScanFilter.ORIGINAL))
    }

    @Test fun grayscaleUsesLuminanceAndPreservesAlpha() {
        val result = PixelFilters.apply(0x7FFF0000, ScanFilter.GRAYSCALE)
        assertEquals(0x7F4C4C4C, result)
    }

    @Test fun monochromeSeparatesTextAndPaper() {
        assertEquals(0xFF000000.toInt(), PixelFilters.apply(0xFF646464.toInt(), ScanFilter.MONOCHROME))
        assertEquals(0xFFFFFFFF.toInt(), PixelFilters.apply(0xFFD0D0D0.toInt(), ScanFilter.MONOCHROME))
    }

    @Test fun enhancementClampsChannelsInsteadOfWrapping() {
        val result = PixelFilters.apply(0xFFFF00FF.toInt(), ScanFilter.ENHANCED)
        assertEquals(255, result ushr 16 and 255)
        assertEquals(255, result and 255)
        assertTrue((result ushr 8 and 255) < 10)
    }
}
