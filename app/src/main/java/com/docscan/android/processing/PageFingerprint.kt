package com.docscan.android.processing

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Coarse, spatially averaged page texture. No OCR or persisted content. */
data class PageFingerprint(val cells: List<Float>) {
    fun distance(other: PageFingerprint): Float = cells.zip(other.cells).sumOf { (a, b) -> abs(a - b).toDouble() }.toFloat() / cells.size
    fun differsFrom(other: PageFingerprint): Boolean = distance(other) > CONTENT_DISTANCE &&
        cells.zip(other.cells).count { (a, b) -> abs(a - b) > 8f } > cells.size / 4

    companion object {
        const val CONTENT_DISTANCE = 10f
        fun from(image: GrayImage, quad: CropQuad): PageFingerprint {
            val p = quad.points
            // Nearest-pixel samples alias thin letters as the detected corners move by a pixel.
            // Box averages keep that harmless jitter from restarting capture or inventing a new page.
            val stride = image.width + 1
            val integral = LongArray(stride * (image.height + 1))
            for (y in 0 until image.height) {
                var row = 0L
                for (x in 0 until image.width) {
                    row += image.pixels[y * image.width + x]
                    integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
                }
            }
            val radius = (minOf(image.width, image.height) / 32).coerceAtLeast(1)
            val cells = List(64) { cell ->
                var sum = 0f
                for (sy in 0..5) for (sx in 0..5) {
                    // Leave the outer paper edge out of the content signature.
                    val u = .05f + .9f * (cell % 8 + (sx + .5f) / 6) / 8
                    val v = .05f + .9f * (cell / 8 + (sy + .5f) / 6) / 8
                    val leftX = p[0].x + (p[3].x - p[0].x) * v
                    val rightX = p[1].x + (p[2].x - p[1].x) * v
                    val leftY = p[0].y + (p[3].y - p[0].y) * v
                    val rightY = p[1].y + (p[2].y - p[1].y) * v
                    val x = ((leftX + (rightX - leftX) * u) * (image.width - 1)).roundToInt().coerceIn(0, image.width - 1)
                    val y = ((leftY + (rightY - leftY) * u) * (image.height - 1)).roundToInt().coerceIn(0, image.height - 1)
                    val x0 = (x - radius).coerceAtLeast(0); val x1 = (x + radius + 1).coerceAtMost(image.width)
                    val y0 = (y - radius).coerceAtLeast(0); val y1 = (y + radius + 1).coerceAtMost(image.height)
                    sum += (integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                        integral[y1 * stride + x0] + integral[y0 * stride + x0]).toFloat() / ((x1 - x0) * (y1 - y0))
                }
                sum / 36
            }
            val mean = cells.average().toFloat()
            val deviation = sqrt(cells.sumOf { ((it - mean) * (it - mean)).toDouble() } / cells.size).toFloat()
            val scale = 32f / deviation.coerceAtLeast(12f)
            return PageFingerprint(cells.map { (it - mean) * scale })
        }
    }
}
