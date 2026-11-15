package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.BookCurve
import com.docscan.rebuild.model.Flattening
import com.docscan.rebuild.model.Treatment
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Automatic straightening of a curved page (a book page bending towards the spine) from its own text lines.
 *
 * Letters are linked into lines: each letter joins its nearest right neighbour of similar size at a similar height,
 * and a line then keeps going along its own direction, so it follows the text into the sharp curl next to the spine
 * (where letters turn and rise too steeply for the first rule). Every line k is modelled as y = c_k + d(x, y), where
 * d is a smooth vertical displacement over the cropped page: a tensor product of cubic B-splines, 16 across the page
 * (fine enough for the curl at the spine) by 6 down it, kept smooth by a second-difference penalty in both
 * directions. The per-line offsets c_k are removed by centring each line. Rendering moves each output row back to
 * where its text line really is. Pages saved with the first model (nine polynomial terms, up to 1.2.3) keep
 * rendering exactly as before. Measured on the owner's photograph of an open Hebrew book (docs/FLATTEN_1.2.4_HE.md).
 */
object PageFlattener {
    /** Terms of the first (polynomial) model; saved pages with nine numbers use it. */
    const val TERMS = 9
    /** B-splines across and down the page in the current model; it is saved as [ACROSS, DOWN, coefficients...]. */
    const val ACROSS = 16
    const val DOWN = 6
    private const val WORK_WIDTH = 1200
    /** No displacement larger than this share of the page height is applied, whatever the fit says. */
    private const val LIMIT = .08

    /** The text lines of a grey page, as points (x, y) in 0..1. */
    internal fun lines(gray: Mat): List<Array<DoubleArray>> {
        val w = gray.cols(); val h = gray.rows()
        val ink = Mat(); val labels = Mat(); val stats = Mat(); val centroids = Mat()
        try {
            Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 41, 12.0)
            val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
            if (n < 50) return emptyList()
            val s = IntArray(n * 5).also { stats.get(0, 0, it) }
            fun x(i: Int) = s[i * 5]
            fun y(i: Int) = s[i * 5 + 1]
            fun bw(i: Int) = s[i * 5 + 2]
            fun bh(i: Int) = s[i * 5 + 3]
            fun area(i: Int) = s[i * 5 + 4]
            var letters = (1 until n).filter { bh(it) >= max(5, h / 300) && bh(it) <= h / 12 && bw(it) <= w / 8 && area(it) >= 12 && bw(it) <= bh(it) * 4 }
            if (letters.size < 50) return emptyList()
            val median = letters.map { bh(it) }.sorted()[letters.size / 2]
            letters = letters.filter { bh(it) >= median * .45 } // vowel points, dots and specks
            val cx = DoubleArray(n) { x(it) + bw(it) / 2.0 }; val cy = DoubleArray(n) { y(it) + bh(it) / 2.0 }
            val cell = max(8, median * 2)
            val grid = HashMap<Long, MutableList<Int>>()
            fun key(gx: Int, gy: Int) = gx.toLong() shl 32 or (gy.toLong() and 0xffffffffL)
            for (i in letters) grid.getOrPut(key((cx[i] / cell).toInt(), (cy[i] / cell).toInt())) { ArrayList() }.add(i)
            val next = HashMap<Int, Int>(); val claimed = HashMap<Int, Pair<Double, Int>>()
            for (i in letters) {
                val reach = 1.6 * bh(i); val gx = (cx[i] / cell).toInt(); val gy = (cy[i] / cell).toInt()
                var best = Double.MAX_VALUE; var bestJ = -1
                for (dx in 0..(reach / cell).toInt() + 1) for (dy in -1..1) grid[key(gx + dx, gy + dy)]?.forEach { j ->
                    val gap = x(j) - (x(i) + bw(i))
                    if (j == i || cx[j] <= cx[i] || gap > reach || gap < -bw(i) * .3) return@forEach
                    val ratio = bh(j).toDouble() / bh(i); if (ratio < .5 || ratio > 2) return@forEach
                    if (abs(cy[j] - cy[i]) > .45 * max(bh(i), bh(j))) return@forEach
                    val score = gap + 3 * abs(cy[j] - cy[i])
                    if (score < best) { best = score; bestJ = j }
                }
                if (bestJ >= 0) {
                    val previous = claimed[bestJ]
                    if (previous == null || previous.first > best) { previous?.let { next.remove(it.second) }; claimed[bestJ] = best to i; next[i] = bestJ }
                }
            }
            val targets = HashSet(next.values)
            val chains = ArrayList<ArrayList<Int>>()
            for (start in next.keys) {
                if (start in targets) continue
                val chain = arrayListOf(start)
                while (chain.size < 5000) chain.add(next[chain.last()] ?: break)
                if (chain.size >= 8) chains += chain
            }
            // Where the page curls into the spine the letters turn and the line rises steeply: each line keeps going
            // along its own direction (the slope of its last letters), at either end, through letters no line has.
            val used = HashSet<Int>().also { u -> chains.forEach { u.addAll(it) } }
            fun step(chain: List<Int>, forward: Boolean): Int {
                val tail = if (forward) chain.takeLast(6) else chain.take(6)
                val mx = tail.sumOf { cx[it] } / tail.size; val my = tail.sumOf { cy[it] } / tail.size
                val sxx = tail.sumOf { (cx[it] - mx) * (cx[it] - mx) }
                val slope = if (sxx < 1) 0.0 else (tail.sumOf { (cx[it] - mx) * (cy[it] - my) } / sxx).coerceIn(-1.0, 1.0)
                val end = if (forward) chain.last() else chain.first()
                val reach = 2.0 * bh(end); val gx = (cx[end] / cell).toInt(); val gy = (cy[end] / cell).toInt(); val span = (reach / cell).toInt() + 2
                var best = Double.MAX_VALUE; var bestJ = -1
                for (dx in -span..span) for (dy in -3..3) grid[key(gx + dx, gy + dy)]?.forEach { j ->
                    if (j in used) return@forEach
                    if (if (forward) cx[j] <= cx[end] else cx[j] >= cx[end]) return@forEach
                    val gap = if (forward) x(j) - (x(end) + bw(end)) else x(end) - (x(j) + bw(j))
                    if (gap > reach || gap < -bw(end) * .5) return@forEach
                    val ratio = bh(j).toDouble() / bh(end); if (ratio < .5 || ratio > 2) return@forEach
                    val predicted = cy[end] + slope * (cx[j] - cx[end])
                    if (abs(cy[j] - predicted) > .45 * max(bh(j), bh(end))) return@forEach
                    val score = abs(gap) + 3 * abs(cy[j] - predicted)
                    if (score < best) { best = score; bestJ = j }
                }
                return bestJ
            }
            for (chain in chains) for (forward in listOf(true, false)) while (chain.size < 5000) {
                val j = step(chain, forward); if (j < 0) break
                used += j; if (forward) chain.add(j) else chain.add(0, j)
            }
            val out = ArrayList<Array<DoubleArray>>()
            for (chain in chains) {
                val xs = chain.map { cx[it] }; if (xs.max() - xs.min() < w * .12) continue
                // Letter centres wander with ascenders and descenders: a running median along the line steadies them.
                val ys = chain.map { cy[it] }
                out.add(Array(chain.size) { t ->
                    val window = ys.subList(max(0, t - 5), min(ys.size, t + 6)).sorted()
                    doubleArrayOf(xs[t] / w, window[window.size / 2] / h)
                })
            }
            return out
        } finally { ink.release(); labels.release(); stats.release(); centroids.release() }
    }

    /** Uniform cubic B-spline with [k] functions on 0..1: the first of the four that are non-zero, and their weights. */
    private fun spline(t0: Double, k: Int, weights: DoubleArray): Int {
        val n = k - 3; val t = t0.coerceIn(0.0, 1.0) * n; val i = t.toInt().coerceIn(0, n - 1); val f = t - i
        val f2 = f * f; val f3 = f2 * f
        weights[0] = (1 - f) * (1 - f) * (1 - f) / 6; weights[1] = (3 * f3 - 6 * f2 + 4) / 6
        weights[2] = (-3 * f3 + 3 * f2 + 3 * f + 1) / 6; weights[3] = f3 / 6
        return i
    }

    /** The displacement model's row of basis values at (x, y), ACROSS x DOWN long. */
    private fun surfaceRow(x: Double, y: Double, out: DoubleArray, wu: DoubleArray, wv: DoubleArray) {
        java.util.Arrays.fill(out, 0.0)
        val iu = spline(x, ACROSS, wu); val iv = spline(y, DOWN, wv)
        for (a in 0..3) for (b in 0..3) out[(iu + a) * DOWN + iv + b] = wu[a] * wv[b]
    }

    /** Solves the normal equations (Gaussian elimination with partial pivoting); null when singular. */
    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val m = Array(n) { r -> matrix[r].copyOf(n + 1).also { it[n] = rhs[r] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(m[it][col]) } ?: return null
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until n) if (r != col) { val f = m[r][col] / m[col][col]; if (f != 0.0) for (c in col..n) m[r][c] -= f * m[col][c] }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Least squares for the displacement with each line's own height removed, smoothed in both directions. */
    private fun fit(lines: List<Array<DoubleArray>>): DoubleArray? {
        val n = ACROSS * DOWN
        val ata = Array(n) { DoubleArray(n) }; val atb = DoubleArray(n); var count = 0
        val wu = DoubleArray(4); val wv = DoubleArray(4); val b = DoubleArray(n)
        for (line in lines) {
            val rows = line.map { p -> DoubleArray(n).also { surfaceRow(p[0], p[1], it, wu, wv) } }
            val mean = DoubleArray(n) { k -> rows.sumOf { it[k] } / rows.size }; val meanY = line.sumOf { it[1] } / line.size
            val active = (0 until n).filter { mean[it] != 0.0 }.toIntArray()
            for ((t, r) in rows.withIndex()) {
                for (k in active) b[k] = r[k] - mean[k]
                val target = line[t][1] - meanY
                for (a in active) { val ba = b[a]; if (ba == 0.0) continue; atb[a] += ba * target; val ra = ata[a]; for (c in active) ra[c] += ba * b[c] }
                count++
            }
        }
        if (count == 0) return null
        // Second differences across and down the page: the surface bends smoothly, and stays plain where there is no text.
        val smooth = 3e-5 * count
        fun penalise(i0: Int, i1: Int, i2: Int) {
            val idx = intArrayOf(i0, i1, i2); val w = doubleArrayOf(1.0, -2.0, 1.0)
            for (p in 0..2) for (q in 0..2) ata[idx[p]][idx[q]] += smooth * w[p] * w[q]
        }
        for (j in 0 until DOWN) for (i in 1 until ACROSS - 1) penalise((i - 1) * DOWN + j, i * DOWN + j, (i + 1) * DOWN + j)
        for (i in 0 until ACROSS) for (j in 1 until DOWN - 1) penalise(i * DOWN + j - 1, i * DOWN + j, i * DOWN + j + 1)
        for (k in 0 until n) ata[k][k] += 1e-6 * count
        return solve(ata, atb)
    }

    private fun predicted(line: Array<DoubleArray>, coef: DoubleArray): DoubleArray {
        val n = ACROSS * DOWN; val row = DoubleArray(n); val wu = DoubleArray(4); val wv = DoubleArray(4)
        return DoubleArray(line.size) { t -> surfaceRow(line[t][0], line[t][1], row, wu, wv); var d = 0.0; for (k in 0 until n) d += coef[k] * row[k]; d }
    }

    private fun lineError(line: Array<DoubleArray>, coef: DoubleArray): Double {
        val predicted = predicted(line, coef)
        val mp = predicted.average(); val my = line.sumOf { it[1] } / line.size
        return sqrt(line.indices.sumOf { val r = (line[it][1] - my) - (predicted[it] - mp); r * r } / line.size)
    }

    /** Fit result, also for tests: the coefficients when the page is curved enough and the fit is reliable. */
    data class Estimate(val coefficients: FloatArray?, val lines: Int, val bend: Double, val rms: Double)

    fun estimate(gray: Mat): Estimate {
        val scaled = Mat()
        try {
            val scale = WORK_WIDTH.toDouble() / gray.cols()
            Imgproc.resize(gray, scaled, Size(WORK_WIDTH.toDouble(), (gray.rows() * scale).roundToInt().coerceAtLeast(1).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            var found = lines(scaled)
            if (found.size < 10) return Estimate(null, found.size, 0.0, 0.0)
            var coef = fit(found) ?: return Estimate(null, found.size, 0.0, 0.0)
            // Robust passes: a heading, a figure caption or a misread chain should not bend the whole page.
            repeat(2) {
                val errors = found.map { lineError(it, coef) }
                val limit = max(3 * errors.sorted()[errors.size / 2], .0015)
                val kept = found.filterIndexed { i, _ -> errors[i] <= limit }
                if (kept.size >= 10) { found = kept; coef = fit(found) ?: return Estimate(null, found.size, 0.0, 0.0) }
            }
            val kept = found.map { lineError(it, coef) }
            val rms = sqrt(kept.sumOf { it * it } / kept.size)
            val bends = found.map { line -> predicted(line, coef).let { it.max() - it.min() } }.sorted()
            val bend = bends[(bends.size * .9).toInt().coerceAtMost(bends.size - 1)]
            // A flat page needs nothing; a poor fit (no real text lines, a photo, a table) is not trusted.
            val use = rms <= .004 && bend >= .004 && coef.all { it.isFinite() && abs(it) < 1 }
            val saved = FloatArray(2 + coef.size).also { it[0] = ACROSS.toFloat(); it[1] = DOWN.toFloat(); for (k in coef.indices) it[2 + k] = coef[k].toFloat() }
            return Estimate(if (use) saved else null, found.size, bend, rms)
        } finally { scaled.release() }
    }

    /** Estimate for a page as cropped by [recipe] (geometry only: no straightening, curve, filter, rotation or marks). */
    fun estimate(file: File, recipe: EditRecipe): FloatArray? {
        val plain = recipe.copy(flatten = emptyList(), curvature = BookCurve(), treatment = Treatment.ORIGINAL, turns = 0, brightness = 0f, contrast = 1f,
            watermark = null, stamp = null, footer = null, signatures = emptyList())
        val bitmap = ImagePipeline.render(file, plain, 1800)
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return estimate(gray).coefficients
        } finally { bitmap.recycle(); rgba.release(); gray.release() }
    }

    /** Whether [coef] is a saved straightening: nine numbers of the first model, or [ACROSS, DOWN, coefficients...]. */
    fun valid(coef: List<Float>) = Flattening.valid(coef)

    /**
     * Moves every output row back to where its text line is, in strips to bound memory. Nine numbers are the first
     * (polynomial) model, rendered exactly as before; otherwise the B-spline surface.
     */
    fun apply(image: Mat, coef: List<Float>) {
        if (!valid(coef)) return
        val c = coef.toFloatArray()
        val surface = c.size != TERMS
        val across = if (surface) c[0].toInt() else 0; val down = if (surface) c[1].toInt() else 0
        val source = image.clone(); val mapX = Mat(); val mapY = Mat(); val result = Mat()
        try {
            val width = image.cols(); val height = image.rows(); val strip = 64
            // The surface: the four non-zero B-splines of every column, and each spline's mean across the row.
            val wu = DoubleArray(4); val wv = DoubleArray(4)
            val first = IntArray(width); val weights = DoubleArray(width * 4); val mean = DoubleArray(across)
            if (surface) for (x in 0 until width) {
                val i = spline(x.toDouble() / (width - 1).coerceAtLeast(1), across, wu); first[x] = i
                for (a in 0..3) { weights[x * 4 + a] = wu[a]; mean[i + a] += wu[a] / width }
            }
            val rowCoef = DoubleArray(across)
            for (start in 0 until height step strip) {
                val rows = min(strip, height - start)
                val xs = FloatArray(width * rows); val ys = FloatArray(width * rows)
                for (r in 0 until rows) {
                    val yn = (start + r).toDouble() / (height - 1).coerceAtLeast(1)
                    if (surface) {
                        // Down the page the surface is fixed for the row: one coefficient per spline across it.
                        val iv = spline(yn, down, wv)
                        for (i in 0 until across) { var sum = 0.0; for (b in 0..3) sum += c[2 + i * down + iv + b] * wv[b]; rowCoef[i] = sum }
                        var rowMean = 0.0; for (i in 0 until across) rowMean += rowCoef[i] * mean[i]
                        for (x in 0 until width) {
                            val i = r * width + x; xs[i] = x.toFloat(); val f = first[x]
                            var d = -rowMean; for (a in 0..3) d += weights[x * 4 + a] * rowCoef[f + a]
                            ys[i] = ((yn + d.coerceIn(-LIMIT, LIMIT)) * (height - 1)).toFloat()
                        }
                    } else {
                        // Per row the first model's displacement is a cubic in u: A u + B (u^2 - 1/3) + C u^3.
                        val v = 2 * yn - 1
                        val a = c[0] + c[1] * v + c[2] * v * v; val b = c[3] + c[4] * v + c[5] * v * v; val cc = c[6] + c[7] * v + c[8] * v * v
                        for (x in 0 until width) {
                            val i = r * width + x; xs[i] = x.toFloat()
                            val u = 2.0 * x / (width - 1).coerceAtLeast(1) - 1
                            val d = (a * u + b * (u * u - 1.0 / 3) + cc * u * u * u).coerceIn(-LIMIT, LIMIT)
                            ys[i] = ((yn + d) * (height - 1)).toFloat()
                        }
                    }
                }
                mapX.create(rows, width, CvType.CV_32FC1); mapY.create(rows, width, CvType.CV_32FC1)
                mapX.put(0, 0, xs); mapY.put(0, 0, ys)
                Imgproc.remap(source, result, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                val target = image.rowRange(start, start + rows)
                try { result.copyTo(target) } finally { target.release() }
            }
        } finally { source.release(); mapX.release(); mapY.release(); result.release() }
    }
}
