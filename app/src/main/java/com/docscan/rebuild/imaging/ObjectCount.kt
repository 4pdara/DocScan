package com.docscan.rebuild.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Basic counting (1.3.5): the user marks one of the things to count (a pipe end, a coin, a box) and everything that
 * looks like it is found. The size and the sensitivity can be changed when the guess is off. The marked thing's size comes from the edges around the tap; the others are where a patch
 * of that size matches the marked one well (normalised correlation, so lighting does not matter), at least one size
 * apart. The user can then add or remove marks; this only proposes them.
 */
object ObjectCount {
    /** Found centres in page fractions, and the size of one thing (a share of the picture's short side). */
    data class Result(val centres: List<Pair<Float, Float>>, val size: Float)

    /** [size]: the thing's size as a share of the picture's short side, or null to measure it; [sensitivity] 0..1. */
    fun count(rgba: Mat, x: Float, y: Float, size: Float? = null, sensitivity: Float = .5f): Result {
        val work = 900.0; val f = min(1.0, work / max(rgba.cols(), rgba.rows()))
        val small = Mat(); val gray = Mat(); val result = Mat(); val dilated = Mat()
        try {
            Imgproc.resize(rgba, small, Size(max(1.0, rgba.cols() * f), max(1.0, rgba.rows() * f)), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, if (small.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            val w = gray.cols(); val h = gray.rows(); val short = min(w, h)
            // Too small to look for anything: the marked point is all there is.
            if (short < 24) return Result(listOf(x to y), size ?: .1f)
            val cx = (x * w).roundToInt().coerceIn(0, w - 1); val cy = (y * h).roundToInt().coerceIn(0, h - 1)
            val r = size?.let { (it * short / 2).roundToInt() }?.coerceIn(4, short / 4) ?: radius(gray, cx, cy)
            val side = 2 * r + 1
            // The template is the square around the tap; near the picture's edge it is moved inside, and the tap
            // keeps its place in it, so every match is reported where the tapped point would be.
            val x0 = (cx - r).coerceIn(0, w - side); val y0 = (cy - r).coerceIn(0, h - side)
            val dx = cx - x0; val dy = cy - y0
            val template = gray.submat(Rect(x0, y0, side, side))
            try { Imgproc.matchTemplate(gray, template, result, Imgproc.TM_CCOEFF_NORMED) } finally { template.release() }
            // Peaks: the best match within about half a size around, and a clear one (sensitivity .5 asks for .5).
            val k = max(3, r or 1).toDouble()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k, k))
            try { Imgproc.dilate(result, dilated, kernel) } finally { kernel.release() }
            val limit = (1f - sensitivity).coerceIn(.2f, .9f)
            val rw = result.cols(); val rh = result.rows()
            val score = FloatArray(rw * rh).also { result.get(0, 0, it) }; val top = FloatArray(rw * rh).also { dilated.get(0, 0, it) }
            val peaks = ArrayList<Triple<Int, Int, Float>>()
            for (i in score.indices) if (score[i] >= limit && score[i] >= top[i]) peaks += Triple(i % rw + dx, i / rw + dy, score[i])
            // Strongest first; none closer than about one size to another.
            val kept = ArrayList<Pair<Int, Int>>()
            for ((px, py, _) in peaks.sortedByDescending { it.third }) if (kept.none { hypot((it.first - px).toDouble(), (it.second - py).toDouble()) < r * 1.4 }) kept += px to py
            if (kept.none { hypot((it.first - cx).toDouble(), (it.second - cy).toDouble()) < r }) kept += cx to cy
            return Result(kept.map { (px, py) -> px / w.toFloat() to py / h.toFloat() }, 2f * r / short)
        } finally { listOf(small, gray, result, dilated).forEach(Mat::release) }
    }

    /**
     * Half the size of the thing at ([cx], [cy]): its outline is the farthest ring of edges around the tap that shows
     * in at least 11 of 16 directions (a pipe's bore is a ring too, but inside it). Without one, the nearest edges.
     */
    private fun radius(gray: Mat, cx: Int, cy: Int): Int {
        val w = gray.cols(); val h = gray.rows(); val limit = min(w, h) / 4
        val grad = Mat(); val gx = Mat(); val gy = Mat()
        try {
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0); Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1); Core.magnitude(gx, gy, grad)
            val g = FloatArray(w * h).also { grad.get(0, 0, it) }
            val rays = ArrayList<List<Int>>(); val first = ArrayList<Int>()
            for (k in 0 until 16) {
                val a = 2 * Math.PI * k / 16; val dx = kotlin.math.cos(a); val dy = kotlin.math.sin(a)
                val profile = ArrayList<Float>()
                for (s in 0..limit) {
                    val px = (cx + dx * s).roundToInt(); val py = (cy + dy * s).roundToInt()
                    if (px !in 0 until w || py !in 0 until h) break
                    profile += g[py * w + px]
                }
                // Edges along the ray: local maxima of the gradient that are clearly strong.
                val strong = (profile.maxOrNull() ?: 0f) * .2f
                val edges = (3 until profile.size - 1).filter { s -> profile[s] >= 40f && profile[s] >= strong && profile[s] >= profile[s - 1] && profile[s] >= profile[s + 1] }
                rays += edges; edges.firstOrNull()?.let { first += it }
            }
            // Rings of edges seen all around; the thing's outline is the outermost of them close to the first (beyond
            // about twice the first ring are the things next to it).
            val rings = (3..limit).filter { r -> rays.count { e -> e.any { it >= r * .85 && it <= r * 1.15 } } >= 11 }
            val ring = rings.firstOrNull()?.let { inner -> rings.filter { it <= inner * 1.9 }.maxOrNull() }
            val near = first.sorted().let { if (it.isEmpty()) 12 else it[it.size / 2] }
            return (ring ?: near).coerceIn(4, limit)
        } finally { grad.release(); gx.release(); gy.release() }
    }
}
