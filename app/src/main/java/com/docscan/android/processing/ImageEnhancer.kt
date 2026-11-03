package com.docscan.android.processing

import kotlin.math.abs
import kotlin.math.roundToInt

data class ImageAdjustments(val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0, val sharpness: Int = 0) {
    init {
        require(brightness in -100..100 && contrast in -100..100 && saturation in -100..100)
        require(sharpness in 0..100)
    }
}

/** Original, bounded on-device processing. Sharpening improves existing edges, not missing detail. */
object ImageEnhancer {
    fun apply(pixels: IntArray, width: Int, height: Int, settings: ImageAdjustments) {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (settings.brightness != 0 || settings.contrast != 0 || settings.saturation != 0) {
            val brightness = settings.brightness * 1.28f
            val contrast = 1f + settings.contrast / if (settings.contrast < 0) 100f else 50f
            val saturation = 1f + settings.saturation / 100f
            for (i in pixels.indices) {
                val p = pixels[i]; val luma = luminance(p)
                fun channel(shift: Int): Int {
                    val value = luma + ((p ushr shift and 255) - luma) * saturation
                    return ((value - 128) * contrast + 128 + brightness).roundToInt().coerceIn(0, 255)
                }
                pixels[i] = (p and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        if (settings.sharpness == 0) return
        val map = LocalLuminance(pixels, width, height)
        val radius = (maxOf(width, height) / 1200).coerceIn(1, 3)
        val amount = settings.sharpness / 70f
        map.forEachMean(radius) { i, mean ->
            val detail = (map.values[i].toInt() and 255) - mean
            // Ignore small noise and cap halos around high-contrast text.
            val delta = if (abs(detail) <= 2f) 0 else
                ((detail - if (detail > 0) 2f else -2f) * amount).roundToInt().coerceIn(-32, 32)
            if (delta != 0) {
                val p = pixels[i]
                fun channel(shift: Int) = ((p ushr shift and 255) + delta).coerceIn(0, 255)
                pixels[i] = (p and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
    }
}

internal fun luminance(pixel: Int) = ((pixel ushr 16 and 255) * 77 + (pixel ushr 8 and 255) * 150 + (pixel and 255) * 29) ushr 8

/** One byte per source pixel and one column buffer; no full-resolution integral/blur image. */
internal class LocalLuminance(pixels: IntArray, val width: Int, val height: Int) {
    val values = ByteArray(pixels.size) { luminance(pixels[it]).toByte() }

    inline fun forEachMean(radius: Int, consume: (Int, Float) -> Unit) {
        val columns = IntArray(width)
        var top = 0; var bottom = -1
        for (y in 0 until height) {
            val nextBottom = minOf(height - 1, y + radius)
            while (bottom < nextBottom) {
                bottom++
                for (x in 0 until width) columns[x] += values[bottom * width + x].toInt() and 255
            }
            val nextTop = maxOf(0, y - radius)
            while (top < nextTop) {
                for (x in 0 until width) columns[x] -= values[top * width + x].toInt() and 255
                top++
            }
            var left = 0; var right = -1; var sum = 0
            for (x in 0 until width) {
                val nextRight = minOf(width - 1, x + radius)
                while (right < nextRight) { right++; sum += columns[right] }
                val nextLeft = maxOf(0, x - radius)
                while (left < nextLeft) { sum -= columns[left]; left++ }
                consume(y * width + x, sum.toFloat() / ((right - left + 1) * (bottom - top + 1)))
            }
        }
    }
}
