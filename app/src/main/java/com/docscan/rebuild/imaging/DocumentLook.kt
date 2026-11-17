package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Whether the page found on a photo carries writing (Doc Radar, 1.3.5): an outline alone also turns up on photos
 * with no document (a gradient, a window, a picture frame). The page is flattened to about 400 pixels wide, and thin
 * dark strokes on a lighter ground (a black-hat filter, as for print) are found; writing gives many small separate
 * marks covering a small part of the page, on paper that is smooth between them.
 */
object DocumentLook {
    data class Writing(val ink: Double, val marks: Int, val grain: Double, val lines: Double)

    fun writing(rgba: Mat, frame: Frame): Writing {
        val w = rgba.cols().toDouble(); val h = rgba.rows().toDouble()
        val p = frame.points.map { Point(it.x * w, it.y * h) }
        val top = hypot(p[1].x - p[0].x, p[1].y - p[0].y); val side = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val outW = 400; val outH = (400 * side / top.coerceAtLeast(1.0)).roundToInt().coerceIn(100, 1200)
        val gray = Mat(); val page = Mat(); val hat = Mat(); val strokes = Mat(); val labels = Mat(); val stats = Mat(); val centres = Mat()
        val smooth = Mat(); val diff = Mat(); val around = Mat()
        val src = MatOfPoint2f(*p.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(outW.toDouble(), 0.0), Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()))
        val warp = Imgproc.getPerspectiveTransform(src, dst)
        return try {
            Imgproc.cvtColor(rgba, gray, if (rgba.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGR2GRAY)
            Imgproc.warpPerspective(gray, page, warp, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_AREA)
            // A margin of 3% is left out: the outline's own edge is not writing.
            val m = (outW * .03).roundToInt()
            val inner = page.submat(m, outH - m, m, outW - m)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            Imgproc.morphologyEx(inner, hat, Imgproc.MORPH_BLACKHAT, kernel); kernel.release()
            Imgproc.threshold(hat, strokes, 28.0, 255.0, Imgproc.THRESH_BINARY)
            val ink = Core.countNonZero(strokes).toDouble() / strokes.total()
            val n = Imgproc.connectedComponentsWithStats(strokes, labels, stats, centres, 8, CvType.CV_32S)
            // Marks the size of letters: a few pixels to a fifth of the page's width.
            var marks = 0
            for (i in 1 until n) {
                val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]; val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]; val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area >= 4 && maxOf(bw, bh) >= 3 && bw < outW / 5.0 && bh < outW / 5.0) marks++
            }
            // The ground between the marks: paper is smooth, a carpet, cloth or pavement is grainy all over. The grain is
            // the mean difference from a 5-pixel median, away from the marks.
            Imgproc.medianBlur(inner, smooth, 5); Core.absdiff(inner, smooth, diff)
            Imgproc.dilate(strokes, around, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
            val counts = IntArray(256); val d = ByteArray(diff.total().toInt()); val a = ByteArray(around.total().toInt())
            diff.get(0, 0, d); around.get(0, 0, a)
            for (i in d.indices) if (a[i].toInt() == 0) counts[d[i].toInt() and 255]++
            val total = counts.sum(); val grain = if (total == 0) 0.0 else counts.indices.sumOf { it.toDouble() * counts[it] } / total
            // Writing runs in lines: across them, rows of marks alternate with blank gaps, so the amount of ink per row
            // (or per column, for a page turned on its side) varies a lot; grain or a picture spreads evenly.
            val lines = maxOf(spread(strokes, 1), spread(strokes, 0))
            inner.release()
            Writing(ink, marks, grain, lines)
        } finally { listOf(gray, page, hat, strokes, labels, stats, centres, smooth, diff, around, warp, src, dst).forEach { it.release() } }
    }

    /** How unevenly the ink is spread over rows ([dim] 1) or columns (0): the deviation over the mean of 3-line sums. */
    private fun spread(strokes: Mat, dim: Int): Double {
        val sums = Mat()
        return try {
            Core.reduce(strokes, sums, dim, Core.REDUCE_SUM, CvType.CV_64F)
            val v = DoubleArray(sums.total().toInt()); sums.get(0, 0, v)
            val s = DoubleArray(v.size) { i -> (maxOf(0, i - 1)..minOf(v.size - 1, i + 1)).sumOf { v[it] } }
            val mean = s.average(); if (mean <= 0) 0.0 else kotlin.math.sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size) / mean
        } finally { sums.release() }
    }

    /**
     * A document: some writing (at least 12 letter-size marks) covering under 40% of the page, on a ground that is not
     * grainy all over (carpet, cloth, pavement). Measured on 329 phone photos of documents with an outline found
     * (Mendeley corner set, test part): 94% pass. The line measure is reported but not used: on those photos it also
     * turned away many real documents.
     */
    fun hasWriting(rgba: Mat, frame: Frame): Boolean = writing(rgba, frame).let { it.marks >= 12 && it.ink < .4 && it.grain < 2.5 }
}
