package com.docscan.android.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Local paper normalization on a small background map. Originals and color marks remain intact. */
object DocumentFilters {
    fun apply(pixels: IntArray, width: Int, height: Int, filter: ScanFilter, strength: Int) {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(strength in 0..100)
        require(filter == ScanFilter.CLEAN_COLOR || filter == ScanFilter.CLEAN_TEXT)
        if (strength == 0) return
        val step = max(1, max(width, height) / 256)
        val gw = (width + step - 1) / step; val gh = (height + step - 1) / step
        val background = IntArray(gw * gh)
        for (y in 0 until height) for (x in 0 until width) {
            val p = pixels[y * width + x]
            val luma = luminance(p)
            val cell = y / step * gw + x / step
            if (luma > background[cell]) background[cell] = luma
        }
        // Remove narrow ink strokes from the background estimate without processing a full-size kernel.
        val dilated = IntArray(background.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var light = 0
            for (dy in -2..2) for (dx in -2..2) {
                light = max(light, background[(y + dy).coerceIn(0, gh - 1) * gw + (x + dx).coerceIn(0, gw - 1)])
            }
            dilated[y * gw + x] = light
        }
        val stride = gw + 1
        val integral = IntArray(stride * (gh + 1))
        for (y in 0 until gh) {
            var row = 0
            for (x in 0 until gw) {
                row += dilated[y * gw + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        for (y in 0 until gh) for (x in 0 until gw) {
            val x0 = max(0, x - 5); val y0 = max(0, y - 5)
            val x1 = min(gw, x + 6); val y1 = min(gh, y + 6)
            background[y * gw + x] = ((integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                integral[y1 * stride + x0] + integral[y0 * stride + x0]) / ((x1 - x0) * (y1 - y0))).coerceAtLeast(32)
        }
        val columns = IntArray(width) { (it / step).coerceAtMost(gw - 1) }
        val weights = FloatArray(width) { (it.toFloat() / step - columns[it]).coerceIn(0f, 1f) }
        for (y in 0 until height) {
            val row = (y / step).coerceAtMost(gh - 1)
            val nextRow = min(row + 1, gh - 1)
            val fy = (y.toFloat() / step - row).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val index = y * width + x; val p = pixels[index]
                val column = columns[x]; val next = min(column + 1, gw - 1); val fx = weights[x]
                val top = background[row * gw + column] * (1 - fx) + background[row * gw + next] * fx
                val bottom = background[nextRow * gw + column] * (1 - fx) + background[nextRow * gw + next] * fx
                val bg = top * (1 - fy) + bottom * fy
                val red = p ushr 16 and 255; val green = p ushr 8 and 255; val blue = p and 255
                fun mix(original: Int, cleaned: Int) = ((original * (100 - strength) + cleaned * strength + 50) / 100).coerceIn(0, 255)
                val alpha = p and -0x1000000
                if (filter == ScanFilter.CLEAN_TEXT) {
                    // Continuous tones retain faint ink; this is deliberately not a hard binary threshold.
                    val luma = luminance(p)
                    val cleaned = (255f - max(0f, bg - luma) * 2.6f).roundToInt().coerceIn(0, 255)
                    val value = mix(luma, cleaned)
                    pixels[index] = alpha or (value shl 16) or (value shl 8) or value
                } else {
                    val gain = 255f / bg
                    fun clean(value: Int) = max(value, (value * gain).roundToInt()).coerceAtMost(255)
                    pixels[index] = alpha or (mix(red, clean(red)) shl 16) or (mix(green, clean(green)) shl 8) or mix(blue, clean(blue))
                }
            }
        }
    }

    private fun luminance(pixel: Int) = ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) ushr 8

}
