package com.docscan.android.processing

import kotlin.math.abs
import kotlin.math.roundToInt

/** Coarse page texture, centered to ignore uniform exposure changes. No OCR or persisted content. */
data class PageFingerprint(val cells: List<Float>) {
    fun distance(other: PageFingerprint): Float = cells.zip(other.cells).sumOf { (a, b) -> abs(a - b).toDouble() }.toFloat() / cells.size
    fun differsFrom(other: PageFingerprint): Boolean = distance(other) > 6f &&
        cells.zip(other.cells).count { (a, b) -> abs(a - b) > 8f } > cells.size / 4

    companion object {
        fun from(image: GrayImage, quad: CropQuad): PageFingerprint {
            val p = quad.points
            val cells = List(144) { cell ->
                var sum = 0f
                for (sy in 0..3) for (sx in 0..3) {
                    val u = (cell % 12 + (sx + .5f) / 4) / 12
                    val v = (cell / 12 + (sy + .5f) / 4) / 12
                    val leftX = p[0].x + (p[3].x - p[0].x) * v
                    val rightX = p[1].x + (p[2].x - p[1].x) * v
                    val leftY = p[0].y + (p[3].y - p[0].y) * v
                    val rightY = p[1].y + (p[2].y - p[1].y) * v
                    val x = ((leftX + (rightX - leftX) * u) * (image.width - 1)).roundToInt().coerceIn(0, image.width - 1)
                    val y = ((leftY + (rightY - leftY) * u) * (image.height - 1)).roundToInt().coerceIn(0, image.height - 1)
                    sum += image.pixels[y * image.width + x]
                }
                sum / 16
            }
            val mean = cells.average().toFloat()
            return PageFingerprint(cells.map { it - mean })
        }
    }
}
