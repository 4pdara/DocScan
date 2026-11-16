package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import com.docscan.rebuild.model.PageEdgeCurves
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Curved top and bottom edges of a page (1.3.0). A book page bows: its top and bottom edges curve between the
 * corners while its sides stay straight. Cropping it with straight sides leaves a wedge of table or cover above or
 * below and bends what is near the edge. Here each edge is found across the page as the outermost clear edge with the
 * page's own paper just inside it (print lies inside the paper, so a heading is not taken for the edge), fitted with a
 * smooth curve that passes through the corners, and kept only when the bow is clear and the fit is good.
 *
 * Saved as [PageEdgeCurves]: for each edge three coefficients of d(u) = u(1-u)(a + b·u + c·u²), the edge's offset at
 * u (0 = left corner, 1 = right) as a share of the page height in the corrected page; positive is downwards. The page
 * is then mapped from a surface ruled between the two curves ([warp]), through the corners' perspective.
 */
object PageEdges {
    private const val SAMPLES = 20
    /** The search reaches this share of the page height outside and inside each straight edge. */
    private const val BAND = .08
    /** A bow under this share of the page height is left alone; the straight crop is as good. */
    private const val CLEAR = .004
    /** A book page's edge bows less than this share of its height; more is a misread (a heading, a shadow). */
    private const val MOST = .035
    private const val WORK = 1600.0

    /** Curves for [frame] on [rgba] (any size, RGBA), or null when neither edge clearly bows. */
    fun estimate(rgba: Mat, frame: Frame): PageEdgeCurves? {
        if (frame == Frame.FULL || !frame.valid()) return null
        val scale = min(1.0, WORK / max(rgba.cols(), rgba.rows()))
        val work = Mat(); val rgb = Mat(); val band = Mat(); val plain = Mat(); val lab8 = Mat(); val labF = Mat()
        val gyc = Mat(); val homography = Mat(); val from = MatOfPoint2f(); val to = MatOfPoint2f()
        try {
            Imgproc.resize(rgba, work, Size(rgba.cols() * scale, rgba.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(work, rgb, if (work.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_GRAY2RGB)
            val q = frame.points.map { Point(it.x * (rgb.cols() - 1.0), it.y * (rgb.rows() - 1.0)) }
            val pw = ((hypot(q[1].x - q[0].x, q[1].y - q[0].y) + hypot(q[2].x - q[3].x, q[2].y - q[3].y)) / 2).roundToInt()
            val ph = ((hypot(q[3].x - q[0].x, q[3].y - q[0].y) + hypot(q[2].x - q[1].x, q[2].y - q[1].y)) / 2).roundToInt()
            if (pw < 80 || ph < 80) return null
            val m = max(8, (ph * BAND).roundToInt())
            from.fromList(q)
            to.fromList(listOf(Point(0.0, m.toDouble()), Point(pw - 1.0, m.toDouble()), Point(pw - 1.0, m + ph - 1.0), Point(0.0, m + ph - 1.0)))
            Imgproc.getPerspectiveTransform(from, to).copyTo(homography)
            Imgproc.warpPerspective(rgb, band, homography, Size(pw.toDouble(), (ph + 2 * m).toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            // Print-free surfaces: two median passes erase text and thin lines.
            val k = max(5, (min(pw, ph) * .012).toInt() or 1)
            Imgproc.medianBlur(band, plain, k); Imgproc.medianBlur(plain, plain, k)
            Imgproc.cvtColor(plain, lab8, Imgproc.COLOR_RGB2Lab)
            lab8.convertTo(labF, CvType.CV_32FC3)
            val w = band.cols(); val h = band.rows()
            val lab = FloatArray(w * h * 3).also { labF.get(0, 0, it) }
            // Vertical colour gradient (all three channels).
            val gy = FloatArray(w * h)
            for (y in 1 until h - 1) for (x in 0 until w) {
                var sum = 0f
                for (c in 0..2) { val d = (lab[((y + 1) * w + x) * 3 + c] - lab[((y - 1) * w + x) * 3 + c]) / 2f; sum += d * d }
                gy[y * w + x] = sqrt(sum)
            }
            // The page's paper: the middle half of the page.
            val paper = FloatArray(3) { c ->
                val values = ArrayList<Float>()
                for (y in m + ph / 4 until m + 3 * ph / 4 step 3) for (x in pw / 4 until 3 * pw / 4 step 3) values += lab[(y * w + x) * 3 + c]
                values.sort(); values[values.size / 2]
            }
            fun far(x: Int, y: Int): Float {
                val i = (y * w + x) * 3
                val dl = (lab[i] - paper[0]) * .5f; val da = lab[i + 1] - paper[1]; val db = lab[i + 2] - paper[2]
                return sqrt(dl * dl + da * da + db * db)
            }
            // Only a paper page bows like this; a cover or a picture printed to its edges is stiff, and its "paper"
            // colour would mean nothing. Most of the page's middle must be one light paper colour.
            var even = 0; var seen = 0
            for (y in m + ph / 4 until m + 3 * ph / 4 step 3) for (x in pw / 4 until 3 * pw / 4 step 3) { seen++; if (far(x, y) < 12f) even++ }
            if (paper[0] < 130f || even < .5 * seen) return null
            // Dark print on the unsmoothed page: an edge that moves inwards may only cut away what holds none.
            val grayBand = Mat(); val ink: ByteArray
            try { Imgproc.cvtColor(band, grayBand, Imgproc.COLOR_RGB2GRAY); ink = ByteArray(w * h).also { grayBand.get(0, 0, it) } } finally { grayBand.release() }
            val paperGray = run {
                val values = ArrayList<Int>()
                for (y in m + ph / 4 until m + 3 * ph / 4 step 3) for (x in pw / 4 until 3 * pw / 4 step 3) values += ink[y * w + x].toInt() and 255
                values.sort(); values[values.size * 3 / 4]
            }
            fun cutsPrint(c: DoubleArray, y0: Int, inward: Int): Boolean {
                var dark = 0; var total = 0
                for (x in 0 until w step 2) {
                    val d = model(c, x / (w - 1.0))
                    if (d * inward <= 1) continue
                    val from = y0 + (if (inward > 0) 2 else -2); val to = (y0 + d).roundToInt() - (if (inward > 0) 2 else -2)
                    // Print is thin and dark on paper: dark in the photo while the print-free image there is still paper.
                    // A strip of dark cover or table is dark in both, and may be cut away.
                    for (y in min(from, to)..max(from, to)) { if (y !in 0 until h) continue; total++; if ((ink[y * w + x].toInt() and 255) < paperGray - 60 && far(x, y) < 24f) dark++ }
                }
                return total > 0 && dark > .015 * total
            }
            val top = edge(w, h, m, m, 1, gy, ::far)?.takeIf { !cutsPrint(it, m, 1) }
            val bottom = edge(w, h, m, m + ph - 1, -1, gy, ::far)?.takeIf { !cutsPrint(it, m + ph - 1, -1) }
            val a = top?.let { c -> c.map { (it / ph).toFloat() } } ?: listOf(0f, 0f, 0f)
            val b = bottom?.let { c -> c.map { (it / ph).toFloat() } } ?: listOf(0f, 0f, 0f)
            val curves = PageEdgeCurves(a + b)
            return curves.takeIf { it.bows() }
        } finally {
            listOf(work, rgb, band, plain, lab8, labF, gyc, homography).forEach(Mat::release); from.release(); to.release()
        }
    }

    /**
     * One edge: per sample column the outermost strong edge with paper just inside, then a robust fit of
     * u(1-u)(a + b·u + c·u²) in pixels, or null when the evidence is too thin or the bow too small or too large.
     */
    private fun edge(w: Int, h: Int, m: Int, y0: Int, inward: Int, gy: FloatArray, far: (Int, Int) -> Float): DoubleArray? {
        val us = DoubleArray(SAMPLES) { (it + .5) / SAMPLES }
        val found = DoubleArray(SAMPLES); val ok = BooleanArray(SAMPLES)
        for (s in 0 until SAMPLES) {
            val x0 = (us[s] * (w - 1)).roundToInt()
            val lo = max(9, y0 - m); val hi = min(h - 10, y0 + m)
            if (hi <= lo) continue
            val score = FloatArray(hi - lo)
            var best = 0f
            for (y in lo until hi) {
                var g = 0f; var inside = 0f; var n = 0
                for (x in max(0, x0 - 3)..min(w - 1, x0 + 3)) {
                    g += gy[y * w + x]
                    for (d in 3..8) { inside += far(x, y + inward * d); n++ }
                }
                g /= 7f; inside /= n
                val v = g * ((24f - inside) / 12f).coerceIn(0f, 1f)
                score[y - lo] = v; best = max(best, v)
            }
            if (best < 3f) continue
            // The outermost strong response (scanning from outside the page inwards), at its local peak.
            var k = if (inward > 0) score.indices.first { score[it] >= .5f * best } else score.indices.last { score[it] >= .5f * best }
            while (k + inward in score.indices && score[k + inward] > score[k]) k += inward
            found[s] = (lo + k - y0).toDouble(); ok[s] = true
        }
        var keep = ok.copyOf()
        var coef: DoubleArray? = null
        repeat(3) {
            if (keep.count { it } < 12) return null
            coef = fit(us, found, keep) ?: return null
            val c = coef!!
            val residual = DoubleArray(SAMPLES) { abs(model(c, us[it]) - found[it]) }
            val sorted = (0 until SAMPLES).filter { keep[it] }.map { residual[it] }.sorted()
            val limit = max(2.0, 2.5 * sorted[sorted.size / 2])
            keep = BooleanArray(SAMPLES) { keep[it] && residual[it] <= limit }
        }
        val c = coef ?: return null
        if (keep.count { it } < 12) return null
        val ph = (h - 2 * m).toDouble()
        val sorted = (0 until SAMPLES).filter { keep[it] }.map { abs(model(c, us[it]) - found[it]) }.sorted()
        if (sorted[sorted.size / 2] > .004 * ph) return null
        val peak = (0..40).maxOf { abs(model(c, it / 40.0)) }
        if (peak < CLEAR * ph || peak > MOST * ph) return null
        return c
    }

    internal fun model(c: DoubleArray, u: Double) = u * (1 - u) * (c[0] + c[1] * u + c[2] * u * u)

    private fun fit(us: DoubleArray, d: DoubleArray, keep: BooleanArray): DoubleArray? {
        val ata = Array(3) { DoubleArray(3) }; val atb = DoubleArray(3)
        for (i in us.indices) if (keep[i]) {
            val u = us[i]; val base = u * (1 - u); val row = doubleArrayOf(base, base * u, base * u * u)
            for (r in 0..2) { atb[r] += row[r] * d[i]; for (s in 0..2) ata[r][s] += row[r] * row[s] }
        }
        for (r in 0..2) ata[r][r] += 1e-9
        return solve3(ata, atb)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { r -> doubleArrayOf(a[r][0], a[r][1], a[r][2], b[r]) }
        for (col in 0..2) {
            val pivot = (col..2).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[pivot]; m[pivot] = t
            for (r in 0..2) if (r != col) { val f = m[r][col] / m[col][col]; for (c in col..3) m[r][c] -= f * m[col][c] }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    /**
     * Maps the page from [source] into [width]×[height]: output (x, y) is the point at height v = y/(height-1)
     * between the top and bottom curves at u = x/(width-1), taken back through the corners' perspective ([toSource]
     * maps the corrected page, [inset] pixels larger on every side as for the straight crop, into the photo).
     * Worked in strips of rows to bound memory.
     */
    fun warp(source: Mat, toSource: Mat, width: Int, height: Int, inset: Double, curves: PageEdgeCurves, out: Mat) {
        val hm = DoubleArray(9).also { toSource.get(0, 0, it) }
        val a = curves.top.map { it.toDouble() }.toDoubleArray(); val b = curves.bottom.map { it.toDouble() }.toDoubleArray()
        val span = height - 1 + 2 * inset
        val dt = DoubleArray(width); val db = DoubleArray(width)
        for (x in 0 until width) { val u = x / (width - 1.0); dt[x] = model(a, u) * span; db[x] = model(b, u) * span }
        out.create(height, width, source.type())
        val strip = 64
        val mapX = Mat(); val mapY = Mat(); val piece = Mat()
        try {
            var y0 = 0
            while (y0 < height) {
                val rows = min(strip, height - y0)
                val xs = FloatArray(rows * width); val ys = FloatArray(rows * width)
                for (r in 0 until rows) {
                    val y = y0 + r; val v = y / (height - 1.0)
                    for (x in 0 until width) {
                        val px = x.toDouble(); val py = y + (1 - v) * dt[x] + v * db[x]
                        val zx = hm[0] * px + hm[1] * py + hm[2]; val zy = hm[3] * px + hm[4] * py + hm[5]; val zw = hm[6] * px + hm[7] * py + hm[8]
                        xs[r * width + x] = (zx / zw).toFloat(); ys[r * width + x] = (zy / zw).toFloat()
                    }
                }
                mapX.create(rows, width, CvType.CV_32FC1); mapY.create(rows, width, CvType.CV_32FC1)
                mapX.put(0, 0, xs); mapY.put(0, 0, ys)
                Imgproc.remap(source, piece, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                piece.copyTo(out.rowRange(y0, y0 + rows))
                y0 += rows
            }
        } finally { mapX.release(); mapY.release(); piece.release() }
    }
}
