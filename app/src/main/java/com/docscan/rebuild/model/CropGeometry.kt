package com.docscan.rebuild.model

import kotlin.math.*

/** Metric rectification of a flat rectangle, assuming square pixels and a centred principal point.
 * Edge lengths alone measure the foreshortened photograph, not the page's proportions.
 * Ambiguous one-axis views use a conservative focal estimate; an explicit ratio is always available.
 */
object CropGeometry {
    data class Dimensions(val width: Int, val height: Int) {
        val aspect: Float get() = width.toFloat() / height
        fun rotated(turns: Int) = if (turns.mod(2) == 0) this else Dimensions(height, width)
    }
    fun dimensions(frame: Frame, width: Int, height: Int, aspectOverride: Float? = null, focalToDiagonal: Double? = null): Dimensions {
        require(frame.valid() && width >= 2 && height >= 2)
        require(aspectOverride == null || aspectOverride.isFinite() && aspectOverride in .1f..10f)
        if (frame == Frame.FULL && aspectOverride == null) return Dimensions(width, height)
        val p = frame.points.map { doubleArrayOf(it.x * (width - 1.0), it.y * (height - 1.0)) }
        fun length(a: Int, b: Int) = hypot(p[a][0] - p[b][0], p[a][1] - p[b][1]) + 1.0
        val horizontal = max(length(0, 1), length(3, 2))
        val vertical = max(length(0, 3), length(1, 2))
        val ratio = aspectOverride?.toDouble() ?: metricAspect(p, width, height, focalToDiagonal) ?: (horizontal / vertical)
        // Keep the detail along both observed axes; never silently shrink a full-resolution export.
        val outputWidth = max(horizontal, vertical * ratio).roundToInt().coerceAtLeast(2)
        val outputHeight = max(vertical, horizontal / ratio).roundToInt().coerceAtLeast(2)
        return Dimensions(outputWidth, outputHeight)
    }

    private fun metricAspect(p: List<DoubleArray>, width: Int, height: Int, focalToDiagonal: Double?): Double? {
        val dx1 = p[1][0] - p[2][0]; val dx2 = p[3][0] - p[2][0]
        val dy1 = p[1][1] - p[2][1]; val dy2 = p[3][1] - p[2][1]
        val sx = p[0][0] - p[1][0] + p[2][0] - p[3][0]
        val sy = p[0][1] - p[1][1] + p[2][1] - p[3][1]
        val determinant = dx1 * dy2 - dx2 * dy1
        if (abs(determinant) < 1e-8) return null
        // Homography columns from the unit square to the photographed quadrilateral.
        val g = (sx * dy2 - dx2 * sy) / determinant
        val h = (dx1 * sy - sx * dy1) / determinant
        val cx = (width - 1.0) / 2; val cy = (height - 1.0) / 2
        val ux = p[1][0] - p[0][0] + g * (p[1][0] - cx)
        val uy = p[1][1] - p[0][1] + g * (p[1][1] - cy)
        val vx = p[3][0] - p[0][0] + h * (p[3][0] - cx)
        val vy = p[3][1] - p[0][1] + h * (p[3][1] - cy)
        val longEdge = max(width, height).toDouble()
        val calibrated = focalToDiagonal?.takeIf { it.isFinite() && it > 0 }?.let { it * hypot(width.toDouble(), height.toDouble()) }
        val inferredSquared = if (abs(g * h) > 1e-8) -(ux * vx + uy * vy) / (g * h) else Double.NaN
        val focalSquared = calibrated?.pow(2) ?: inferredSquared.takeIf {
            it.isFinite() && it in (.35 * longEdge).pow(2)..(5 * longEdge).pow(2)
        } ?: (1.2 * longEdge).pow(2)
        val ratio = sqrt((ux * ux + uy * uy + focalSquared * g * g) / (vx * vx + vy * vy + focalSquared * h * h))
        return ratio.takeIf { it.isFinite() && it in .1..10.0 }
    }
}
