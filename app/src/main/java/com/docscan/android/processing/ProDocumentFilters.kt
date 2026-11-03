package com.docscan.android.processing

import kotlin.math.pow
import kotlin.math.roundToInt

object ProDocumentFilters {
    val supported = setOf(ScanFilter.LIGHTEN, ScanFilter.VIVID, ScanFilter.ADAPTIVE_MONO, ScanFilter.BLACKBOARD)

    fun apply(pixels: IntArray, width: Int, height: Int, filter: ScanFilter, strength: Int) {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(filter in supported && strength in 0..100)
        if (strength == 0) return
        when (filter) {
            ScanFilter.ADAPTIVE_MONO -> {
                val map = LocalLuminance(pixels, width, height)
                val radius = (minOf(width, height) / 35).coerceIn(3, 96)
                val offset = 12f - strength * .1f
                map.forEachMean(radius) { i, mean ->
                    val value = if ((map.values[i].toInt() and 255) < mean - offset) 0 else 255
                    pixels[i] = (pixels[i] and -0x1000000) or (value shl 16) or (value shl 8) or value
                }
            }
            ScanFilter.BLACKBOARD -> {
                for (i in pixels.indices) {
                    val value = 255 - luminance(pixels[i])
                    pixels[i] = (pixels[i] and -0x1000000) or (value shl 16) or (value shl 8) or value
                }
                DocumentFilters.apply(pixels, width, height, ScanFilter.CLEAN_TEXT, strength)
            }
            ScanFilter.LIGHTEN, ScanFilter.VIVID -> {
                val light = IntArray(256) { (255 * (it / 255.0).pow(.7)).roundToInt() }
                for (i in pixels.indices) {
                    val p = pixels[i]; val luma = luminance(p)
                    fun channel(shift: Int): Int {
                        val original = p ushr shift and 255
                        val processed = if (filter == ScanFilter.LIGHTEN) light[original] else
                            ((luma + (original - luma) * 1.35f - 128) * 1.12f + 132).roundToInt().coerceIn(0, 255)
                        return (original * (100 - strength) + processed * strength + 50) / 100
                    }
                    pixels[i] = (p and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
                }
            }
            else -> error("Unsupported document filter")
        }
    }
}
