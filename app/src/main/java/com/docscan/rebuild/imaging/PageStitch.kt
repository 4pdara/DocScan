package com.docscan.rebuild.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Long scan (1.3.4): photos of one long page (a receipt, a scroll), taken top to bottom with some overlap, joined
 * into one tall picture. Each photo is first cropped and straightened like any page; the overlap is where the top
 * strip of a photo matches best inside the lower part of the one before it, and the two are blended across a short
 * band there. Photos that do not overlap are put one under the other.
 */
object PageStitch {
    /** Where [next] continues [previous]: rows of [previous] it starts at (scaled to [previous]), and the match score. */
    data class Join(val at: Int, val shift: Int, val score: Double)

    fun join(previous: Mat, next: Mat): Join? {
        val work = 480.0
        val fa = work / previous.cols(); val fb = work / next.cols()
        val a = Mat(); val b = Mat(); val ga = Mat(); val gb = Mat(); val result = Mat()
        try {
            Imgproc.resize(previous, a, Size(work, max(8.0, previous.rows() * fa)), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(next, b, Size(work, max(8.0, next.rows() * fb)), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(a, ga, if (a.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.cvtColor(b, gb, if (b.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            // The template: a strip from the next photo's top, without the outer 6% at the sides. A quarter of its height
            // first; when the photos overlap less than that, shorter strips (16%, then 10%) still fit inside the overlap.
            val margin = (work * .06).roundToInt()
            for (share in doubleArrayOf(.25, .16, .1)) {
                val th = max(16, (gb.rows() * share).roundToInt())
                if (th >= gb.rows() || th >= ga.rows()) continue
                val template = gb.submat(Rect(margin, 0, gb.cols() - 2 * margin, th))
                // Searched in the lower 80% of the previous photo, allowing a sideways shift up to the margin.
                val from = (ga.rows() * .2).roundToInt(); val area = ga.submat(Rect(0, from, ga.cols(), ga.rows() - from))
                try {
                    if (area.rows() < template.rows()) continue
                    Imgproc.matchTemplate(area, template, result, Imgproc.TM_CCOEFF_NORMED)
                    val best = Core.minMaxLoc(result)
                    if (best.maxVal < .55) continue
                    // A clear winner: anything else that matches nearly as well must be the same place.
                    val values = FloatArray(result.rows() * result.cols()).also { result.get(0, 0, it) }
                    val by = best.maxLoc.y.toInt(); val bx = best.maxLoc.x.toInt()
                    var second = -1.0
                    for (y in 0 until result.rows()) for (x in 0 until result.cols()) if (kotlin.math.abs(y - by) > th / 3) second = max(second, values[y * result.cols() + x].toDouble())
                    if (second > best.maxVal - .08) continue
                    return Join(((from + by) / fa).roundToInt(), ((bx - margin) / fa).roundToInt(), best.maxVal)
                } finally { template.release(); area.release() }
            }
            return null
        } finally { listOf(a, b, ga, gb, result).forEach(Mat::release) }
    }

    /** The photos (RGBA, any sizes) as one picture as wide as the first. */
    fun stitch(parts: List<Mat>): Mat {
        require(parts.isNotEmpty())
        val width = parts[0].cols()
        val scaled = parts.map { p -> if (p.cols() == width) p else Mat().also { Imgproc.resize(p, it, Size(width.toDouble(), (p.rows() * width.toDouble() / p.cols()).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) } }
        try {
            // Where each part starts in the result, and how far it is shifted sideways.
            val tops = IntArray(scaled.size); val shifts = IntArray(scaled.size)
            for (i in 1 until scaled.size) {
                val j = join(scaled[i - 1], scaled[i])
                tops[i] = tops[i - 1] + (j?.at ?: scaled[i - 1].rows()); shifts[i] = shifts[i - 1] + (j?.shift ?: 0)
            }
            val height = (scaled.indices).maxOf { tops[it] + scaled[it].rows() }
            val out = Mat(height, width, scaled[0].type(), org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0))
            for (i in scaled.indices) {
                val part = scaled[i]
                // Whole columns that stay inside the picture after the shift.
                val dx = shifts[i]; val x0 = max(0, dx); val x1 = min(width, dx + part.cols())
                if (x1 <= x0) continue
                val src = part.submat(Rect(x0 - dx, 0, x1 - x0, part.rows()))
                val dst = out.submat(Rect(x0, tops[i], x1 - x0, part.rows()))
                try {
                    if (i == 0) src.copyTo(dst) else {
                        // Blend over a band at the top of the overlap so the join does not show.
                        val overlap = (tops[i - 1] + scaled[i - 1].rows() - tops[i]).coerceIn(0, part.rows())
                        val band = min(overlap, max(8, part.rows() / 40))
                        if (band > 0) {
                            val s = src.submat(0, band, 0, src.cols()); val d = dst.submat(0, band, 0, dst.cols()); val mixed = Mat()
                            try {
                                for (r in 0 until band) {
                                    val w = (r + 1.0) / (band + 1.0)
                                    Core.addWeighted(s.row(r), w, d.row(r), 1 - w, 0.0, mixed); mixed.copyTo(d.row(r))
                                }
                            } finally { s.release(); d.release(); mixed.release() }
                        }
                        src.submat(band, src.rows(), 0, src.cols()).copyTo(dst.submat(band, dst.rows(), 0, dst.cols()))
                    }
                } finally { src.release(); dst.release() }
            }
            return out
        } finally { scaled.forEachIndexed { i, m -> if (m !== parts[i]) m.release() } }
    }
}
