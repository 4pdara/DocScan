package com.docscan.android.processing

import kotlin.math.*

/** Fit all four actual image edges before warping; a Hough angle bin is only a starting point. */
internal object PageBoundary {
    private data class Line(val nx: Float, val ny: Float, val rho: Float)
    fun refine(image: GrayImage, quad: CropQuad, searchRadius: Int = 5): CropQuad {
        val points = quad.points.map { CropPoint(it.x * (image.width - 1), it.y * (image.height - 1)) }
        val lines = points.indices.map { fit(image, points[it], points[(it + 1) % 4], searchRadius) }
        val corners = points.indices.map { i ->
            val a = lines[(i + 3) % 4]; val b = lines[i]
            val determinant = a.nx * b.ny - a.ny * b.nx
            if (abs(determinant) < .12f) return quad
            // A subpixel inward offset excludes antialiased desk/cover pixels at the page boundary.
            val ar = a.rho + .65f; val br = b.rho + .65f
            CropPoint((ar * b.ny - a.ny * br) / determinant / (image.width - 1),
                (a.nx * br - ar * b.nx) / determinant / (image.height - 1))
        }
        val refined = CropQuad.from(corners)
        return refined.takeIf { it.isValid() && it.maxDistance(quad) <= .045f } ?: quad
    }

    private fun fit(image: GrayImage, a: CropPoint, b: CropPoint, searchRadius: Int): Line {
        val dx = b.x - a.x; val dy = b.y - a.y; val length = hypot(dx, dy)
        val nx = -dy / length; val ny = dx / length
        val original = Line(nx, ny, nx * a.x + ny * a.y)
        val samples = (length / 4).toInt().coerceIn(16, 64)
        val points = ArrayList<CropPoint>()
        for (i in 1..samples) {
            val t = .06f + .88f * i / (samples + 1)
            val x = a.x + dx * t; val y = a.y + dy * t
            var best: CropPoint? = null; var bestScore = 0f
            for (offset in -searchRadius..searchRadius) {
                val px = (x + nx * offset).roundToInt(); val py = (y + ny * offset).roundToInt()
                if (px !in 1 until image.width - 1 || py !in 1 until image.height - 1) continue
                val at = py * image.width + px
                val gx = image.pixels[at + 1] - image.pixels[at - 1]
                val gy = image.pixels[at + image.width] - image.pixels[at - image.width]
                val strength = hypot(gx.toFloat(), gy.toFloat())
                if (strength < 12 || abs(gx * nx + gy * ny) / strength < .7f) continue
                val score = strength / (1f + abs(offset) * .4f)
                if (score > bestScore) { bestScore = score; best = CropPoint(px.toFloat(), py.toFloat()) }
            }
            best?.let(points::add)
        }
        if (points.size < samples * .65f) return original
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        var xx = 0f; var xy = 0f; var yy = 0f
        for (p in points) { val x = p.x - cx; val y = p.y - cy; xx += x * x; xy += x * y; yy += y * y }
        val angle = .5f * atan2(2 * xy, xx - yy)
        var fittedX = -sin(angle); var fittedY = cos(angle)
        if (fittedX * nx + fittedY * ny < 0) { fittedX = -fittedX; fittedY = -fittedY }
        if (fittedX * nx + fittedY * ny < .99f) return original
        val rho = fittedX * cx + fittedY * cy
        if (points.sumOf { abs(fittedX * it.x + fittedY * it.y - rho).toDouble() } / points.size > 1.6) return original
        return Line(fittedX, fittedY, rho)
    }
}
