package com.docscan.android.processing

import kotlin.math.pow
import kotlin.math.roundToInt

/** Local paper white balance and continuous ink contrast. No binary threshold or generated pixels. */
object AutoDocumentFilter {
    private val tone = IntArray(1025) { (255.0 * (it / 1024.0).pow(1.6)).roundToInt() }

    fun apply(pixels: IntArray, width: Int, height: Int, strength: Int) {
        require(width > 0 && height > 0 && pixels.size == width * height && strength in 0..100)
        if (strength == 0) return
        // Estimate illumination at bounded resolution; the image and its ink stay full size.
        val step = ((maxOf(width, height) + 127) / 128).coerceAtLeast(1)
        val gw = (width + step - 1) / step; val gh = (height + step - 1) / step
        val paper = IntArray(gw * gh)
        val light = IntArray(paper.size) { -1 }
        for (y in 0 until height) for (x in 0 until width) {
            val p = pixels[y * width + x]
            val luma = luminance(p)
            val cell = y / step * gw + x / step
            if (luma > light[cell]) { light[cell] = luma; paper[cell] = p }
        }
        // Expand paper estimates over narrow strokes and dots, then smooth each color
        // channel. A single luminance gain cannot remove yellow/brown paper tint.
        val expanded = IntArray(paper.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var best = 0; var bestLight = -1
            for (dy in -2..2) for (dx in -2..2) {
                val i = (y + dy).coerceIn(0, gh - 1) * gw + (x + dx).coerceIn(0, gw - 1)
                if (light[i] > bestLight) { bestLight = light[i]; best = paper[i] }
            }
            expanded[y * gw + x] = best
        }
        val channels = Array(3) { channel ->
            val shift = 16 - channel * 8
            val horizontal = FloatArray(paper.size)
            for (y in 0 until gh) for (x in 0 until gw) {
                var sum = 0
                for (dx in -3..3) sum += expanded[y * gw + (x + dx).coerceIn(0, gw - 1)] ushr shift and 255
                horizontal[y * gw + x] = sum / 7f
            }
            FloatArray(paper.size) { i ->
                val x = i % gw; val y = i / gw
                var sum = 0f
                for (dy in -3..3) sum += horizontal[(y + dy).coerceIn(0, gh - 1) * gw + x]
                (sum / 7f).coerceAtLeast(32f)
            }
        }
        val xs = IntArray(width) { (it / step).coerceAtMost(gw - 1) }
        val fx = FloatArray(width) { it.toFloat() / step - xs[it] }
        for (y in 0 until height) {
            val row = (y / step).coerceAtMost(gh - 1); val nextRow = minOf(row + 1, gh - 1)
            val fy = y.toFloat() / step - row
            for (x in 0 until width) {
                val i = y * width + x; val p = pixels[i]
                val col = xs[x]; val nextCol = minOf(col + 1, gw - 1)
                var output = p and -0x1000000
                for (channel in 0..2) {
                    val map = channels[channel]
                    val top = map[row * gw + col] * (1 - fx[x]) + map[row * gw + nextCol] * fx[x]
                    val bottom = map[nextRow * gw + col] * (1 - fx[x]) + map[nextRow * gw + nextCol] * fx[x]
                    val background = top * (1 - fy) + bottom * fy
                    val shift = 16 - channel * 8; val original = p ushr shift and 255
                    val ratio = (original * 1024f / background).roundToInt().coerceIn(0, 1024)
                    val value = (original * (100 - strength) + tone[ratio] * strength + 50) / 100
                    output = output or (value shl shift)
                }
                pixels[i] = output
            }
        }
    }

    private fun luminance(p: Int) = ((p ushr 16 and 255) * 77 + (p ushr 8 and 255) * 150 + (p and 255) * 29) ushr 8
}
