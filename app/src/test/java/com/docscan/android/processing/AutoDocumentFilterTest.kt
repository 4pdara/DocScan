package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AutoDocumentFilterTest {
    private fun rgb(r: Int, g: Int, b: Int) = 0x7F000000 or (r shl 16) or (g shl 8) or b
    private val w = 360
    private val h = 480
    private fun paper() = IntArray(w * h) { i ->
        val shade = (i / w) * 40 / h
        rgb(220 - shade, 203 - shade, 170 - shade)
    }

    @Test fun removesWarmPaperTintAndUnevenLightingWithoutBinarizingInkOrColor() {
        val source = paper()
        for (y in 150..155) for (x in 60..290) source[y * w + x] = rgb(65, 56, 43)
        val faint = 180 * w + 100
        source[faint] = rgb(196, 179, 146)
        val dot = 210 * w + 200
        source[dot] = rgb(75, 63, 48)
        val stamp = 240 * w + 200
        source[stamp] = rgb(170, 55, 65)
        val output = source.copyOf()
        AutoDocumentFilter.apply(output, w, h, 100)
        for (i in listOf(50 * w + 160, 400 * w + 160)) {
            val p = output[i]
            assertTrue("Paper should be light", (p and 255) > 240)
            assertTrue("Warm paper should become neutral", abs((p ushr 16 and 255) - (p and 255)) < 8)
        }
        assertTrue("Dark ink must stay dark", (output[152 * w + 120] ushr 16 and 255) < 80)
        assertTrue("Faint ink must not turn into white paper", (output[faint] and 255) < 250)
        assertTrue("Single-pixel dots must survive", (output[dot] ushr 16 and 255) < 100)
        assertTrue("Red stamps must retain color", (output[stamp] ushr 16 and 255) - (output[stamp] ushr 8 and 255) > 60)
        assertTrue(output.all { it ushr 24 == 0x7F })
    }

    @Test fun strengthIsReversibleAndIntermediateOutputIsContinuous() {
        val source = paper(); val none = source.copyOf(); val half = source.copyOf(); val full = source.copyOf()
        AutoDocumentFilter.apply(none, w, h, 0)
        AutoDocumentFilter.apply(half, w, h, 50)
        AutoDocumentFilter.apply(full, w, h, 100)
        assertArrayEquals(source, none)
        val i = 200 * w + 160
        assertTrue((half[i] and 255) > (source[i] and 255))
        assertTrue((half[i] and 255) < (full[i] and 255))
        val original = source.copyOf()
        AutoDocumentFilter.apply(full, w, h, 100)
        assertArrayEquals(original, source)
    }

    @Test fun smallAndBlackImagesDoNotOverflowOrChangeDimensions() {
        for (size in listOf(1 to 1, 2 to 3, 400 to 1)) {
            val pixels = IntArray(size.first * size.second) { 0xFF000000.toInt() }
            AutoDocumentFilter.apply(pixels, size.first, size.second, 100)
            assertTrue(pixels.all { it == 0xFF000000.toInt() })
        }
    }
}
