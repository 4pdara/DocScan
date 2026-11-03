package com.docscan.android.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object PerspectiveCrop {
    fun apply(source: Bitmap, quad: CropQuad, maxEdge: Int, format: PageFormat = PageFormat.NATURAL): Bitmap {
        require(quad.isValid())
        val p = quad.points.map { CropPoint(it.x * source.width, it.y * source.height) }
        fun distance(a: Int, b: Int) = hypot(p[a].x - p[b].x, p[a].y - p[b].y)
        var width = max(distance(0, 1), distance(3, 2))
        var height = max(distance(0, 3), distance(1, 2))
        format.aspect(width > height)?.let { aspect ->
            // Fit the chosen ratio inside the measured dimensions; never add invented resolution.
            if (width / height > aspect) width = height * aspect else height = width / aspect
        }
        val scale = minOf(1f, maxEdge / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val matrix = Matrix()
        check(matrix.setPolyToPoly(p.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0,
            floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4))
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { result ->
            Canvas(result).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
    }
}
