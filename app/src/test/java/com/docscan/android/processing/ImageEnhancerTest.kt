package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ImageEnhancerTest {
    @Test fun neutralAdjustmentsAndZeroStrengthFiltersPreserveEveryBit() {
        val random = Random(12); val input = IntArray(17 * 13) { random.nextInt() }
        val adjusted = input.copyOf()
        ImageEnhancer.apply(adjusted, 17, 13, ImageAdjustments())
        assertArrayEquals(input, adjusted)
        for (filter in ProDocumentFilters.supported) {
            val output = input.copyOf(); ProDocumentFilters.apply(output, 17, 13, filter, 0)
            assertArrayEquals(input, output)
        }
    }

    @Test fun rollingLocalMeanMatchesIndependentClippedWindowCalculation() {
        val random = Random(44)
        for ((w, h) in listOf(1 to 1, 2 to 3, 17 to 11)) for (radius in listOf(1, 3, 20)) {
            val source = IntArray(w * h) { random.nextInt() }
            val map = LocalLuminance(source, w, h)
            map.forEachMean(radius) { index, actual ->
                val x = index % w; val y = index / w
                val samples = (maxOf(0, y - radius)..minOf(h - 1, y + radius)).flatMap { sy ->
                    (maxOf(0, x - radius)..minOf(w - 1, x + radius)).map { sx -> luminance(source[sy * w + sx]) }
                }
                assertEquals(samples.average().toFloat(), actual, .001f)
            }
        }
    }

    @Test fun sharpeningRaisesSoftEdgeContrastButLeavesFlatAreasAndTinyNoiseAlone() {
        val ramp = intArrayOf(60, 60, 60, 80, 120, 160, 180, 180, 180)
        val pixels = IntArray(81) { val v = ramp[it % 9]; 0x66000000 or (v shl 16) or (v shl 8) or v }
        ImageEnhancer.apply(pixels, 9, 9, ImageAdjustments(sharpness = 100))
        assertTrue((pixels[4 * 9 + 5] and 255) - (pixels[4 * 9 + 3] and 255) > 80)
        assertEquals(60, pixels[0] and 255)
        assertTrue(pixels.all { it ushr 24 == 0x66 })
        val noise = IntArray(64) { val v = 200 + it % 3 - 1; -0x1000000 or (v shl 16) or (v shl 8) or v }
        val original = noise.copyOf(); ImageEnhancer.apply(noise, 8, 8, ImageAdjustments(sharpness = 100))
        assertArrayEquals(original, noise)
    }

    @Test fun brightnessContrastAndSaturationAreIndependentAndKeepAlpha() {
        val input = intArrayOf(0x664064a0, 0x66282828, 0x66c8c8c8)
        val bright = input.copyOf(); ImageEnhancer.apply(bright, 3, 1, ImageAdjustments(brightness = 25))
        assertTrue(luminance(bright[0]) > luminance(input[0]))
        val contrast = input.copyOf(); ImageEnhancer.apply(contrast, 3, 1, ImageAdjustments(contrast = 30))
        assertTrue((contrast[2] and 255) - (contrast[1] and 255) > 160)
        val gray = input.copyOf(); ImageEnhancer.apply(gray, 3, 1, ImageAdjustments(saturation = -100))
        assertTrue(gray.all { (it ushr 16 and 255) == (it ushr 8 and 255) && (it and 255) == (it ushr 8 and 255) })
        assertTrue((bright + contrast + gray).all { it ushr 24 == 0x66 })
    }

    @Test fun adaptiveBlackAndWhiteSeparatesInkFromUnevenPaperLighting() {
        val w = 160; val h = 80
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val value = 130 + x * 100 / (w - 1) - if (x in 10..149 && y in listOf(16, 17, 32, 33, 48, 49)) 25 else 0
            0x77000000 or (value shl 16) or (value shl 8) or value
        }
        ProDocumentFilters.apply(pixels, w, h, ScanFilter.ADAPTIVE_MONO, 70)
        for (x in listOf(15, 80, 140)) {
            assertEquals(0, pixels[32 * w + x] and 255)
            assertEquals(255, pixels[25 * w + x] and 255)
        }
        assertTrue(pixels.all { it ushr 24 == 0x77 && (it and 255) in listOf(0, 255) })
    }

    @Test fun blackboardCreatesDarkChalkOnLightPaperAndLightenRetainsBlackAndWhite() {
        val w = 80; val h = 80
        val board = IntArray(w * h) { i -> val v = if (i % w in 35..38 && i / w in 10..69) 225 else 45; -0x1000000 or (v shl 16) or (v shl 8) or v }
        ProDocumentFilters.apply(board, w, h, ScanFilter.BLACKBOARD, 100)
        assertTrue(luminance(board[40 * w + 36]) < 60)
        assertTrue(luminance(board[40 * w + 10]) > 230)
        val light = intArrayOf(-0x1000000, -1, 0xff787878.toInt())
        ProDocumentFilters.apply(light, 3, 1, ScanFilter.LIGHTEN, 100)
        assertEquals(-0x1000000, light[0]); assertEquals(-1, light[1]); assertTrue((light[2] and 255) > 120)
    }

    @Test fun invalidSettingsAreRejectedBeforeChangingAnImage() {
        assertThrows(IllegalArgumentException::class.java) { ImageAdjustments(sharpness = 101) }
        assertThrows(IllegalArgumentException::class.java) { ImageAdjustments(brightness = -101) }
    }
}
