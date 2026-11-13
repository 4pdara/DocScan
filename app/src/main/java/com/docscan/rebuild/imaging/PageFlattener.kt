package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.BookCurve
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
 * Letters are linked into lines: each letter joins its nearest right neighbour of similar size at a similar height.
 * Every line k is modelled as y = c_k + d(x, y), where d is a smooth vertical displacement over the cropped page,
 * d(x, y) = sum a_ij u^i v^j with u = 2x - 1, v = 2y - 1, i = 1..3, j = 0..2 (nine numbers). The per-line offsets
 * c_k are removed by centring each line, and a small ridge term keeps the fit steady where there is no text.
 * Rendering moves each output row back to where its text line really is. Prototyped on the owner's photograph of a
 * curved Hebrew book page (text-line bend 248 px down to 35 px with the first model) and on synthetic bends.
 */
object PageFlattener {
    const val TERMS = 9
    private const val WORK_WIDTH = 1200
    /** No displacement larger than this share of the page height is applied, whatever the fit says. */
    private const val LIMIT = .08

    private fun basis(x: Double, y: Double, out: DoubleArray) {
        val u = 2 * x - 1; val v = 2 * y - 1; var k = 0
        var ui = u
        for (i in 1..3) { var vj = 1.0; for (j in 0..2) { out[k++] = ui * vj; vj *= v }; ui *= u }
    }

    /** Displacement at (x, y) minus its mean along the row: rows keep their average height. */
    fun shift(coef: FloatArray, x: Double, y: Double, scratch: DoubleArray = DoubleArray(TERMS)): Double {
        basis(x, y, scratch)
        val v = 2 * y - 1; var d = 0.0; var rowMean = 0.0
        for (k in 0 until TERMS) d += coef[k] * scratch[k]
        // Mean over u in [-1, 1] of u^i: 0, 1/3, 0 for i = 1, 2, 3.
        var vj = 1.0; for (j in 0..2) { rowMean += coef[3 + j] * vj / 3.0; vj *= v }
        return (d - rowMean).coerceIn(-LIMIT, LIMIT)
    }

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
            val out = ArrayList<Array<DoubleArray>>()
            for (start in next.keys) {
                if (start in targets) continue
                val chain = arrayListOf(start)
                while (chain.size < 5000) chain.add(next[chain.last()] ?: break)
                if (chain.size < 8) continue
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

    private fun solve(lines: List<Array<DoubleArray>>, ridge: Double = 1e-4): DoubleArray? {
        val ata = Array(TERMS) { DoubleArray(TERMS) }; val atb = DoubleArray(TERMS); val b = DoubleArray(TERMS); var count = 0
        for (line in lines) {
            val rows = line.map { p -> DoubleArray(TERMS).also { basis(p[0], p[1], it) } }
            val mean = DoubleArray(TERMS) { k -> rows.sumOf { it[k] } / rows.size }; val meanY = line.sumOf { it[1] } / line.size
            for ((t, row) in rows.withIndex()) {
                for (k in 0 until TERMS) b[k] = row[k] - mean[k]
                val r = line[t][1] - meanY
                for (a in 0 until TERMS) { atb[a] += b[a] * r; for (c in 0 until TERMS) ata[a][c] += b[a] * b[c] }
                count++
            }
        }
        for (k in 0 until TERMS) ata[k][k] += ridge * count
        // Gaussian elimination with partial pivoting.
        val m = Array(TERMS) { r -> ata[r].copyOf(TERMS + 1).also { it[TERMS] = atb[r] } }
        for (col in 0 until TERMS) {
            val pivot = (col until TERMS).maxByOrNull { abs(m[it][col]) } ?: return null
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until TERMS) if (r != col) { val f = m[r][col] / m[col][col]; for (c in col..TERMS) m[r][c] -= f * m[col][c] }
        }
        return DoubleArray(TERMS) { m[it][TERMS] / m[it][it] }
    }

    private fun lineError(line: Array<DoubleArray>, coef: DoubleArray): Double {
        val scratch = DoubleArray(TERMS)
        val predicted = line.map { p -> basis(p[0], p[1], scratch); (0 until TERMS).sumOf { coef[it] * scratch[it] } }
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
            var coef = solve(found) ?: return Estimate(null, found.size, 0.0, 0.0)
            // One robust pass: a heading, a figure caption or a misread chain should not bend the whole page.
            val errors = found.map { lineError(it, coef) }
            val cut = errors.sorted()[(errors.size * .85).toInt().coerceAtMost(errors.size - 1)]
            found = found.filterIndexed { i, _ -> errors[i] <= cut }
            coef = solve(found) ?: return Estimate(null, found.size, 0.0, 0.0)
            val kept = found.map { lineError(it, coef) }
            val rms = sqrt(kept.sumOf { it * it } / kept.size)
            val scratch = DoubleArray(TERMS)
            val bends = found.map { line -> line.map { p -> basis(p[0], p[1], scratch); (0 until TERMS).sumOf { coef[it] * scratch[it] } }.let { it.max() - it.min() } }.sorted()
            val bend = bends[(bends.size * .9).toInt().coerceAtMost(bends.size - 1)]
            // A flat page needs nothing; a poor fit (no real text lines, a photo, a table) is not trusted.
            val use = rms <= .004 && bend >= .004
            return Estimate(if (use) FloatArray(TERMS) { coef[it].toFloat() } else null, found.size, bend, rms)
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

    /** Moves every output row back to where its text line is, in strips to bound memory. */
    fun apply(image: Mat, coef: List<Float>) {
        if (coef.size != TERMS) return
        val c = coef.toFloatArray()
        val source = image.clone(); val mapX = Mat(); val mapY = Mat(); val result = Mat()
        try {
            val width = image.cols(); val height = image.rows(); val strip = 64
            for (start in 0 until height step strip) {
                val rows = min(strip, height - start)
                val xs = FloatArray(width * rows); val ys = FloatArray(width * rows)
                for (r in 0 until rows) {
                    val yn = (start + r).toDouble() / (height - 1).coerceAtLeast(1)
                    // Per row the displacement is a cubic in u: A u + B (u^2 - 1/3) + C u^3.
                    val v = 2 * yn - 1
                    val a = c[0] + c[1] * v + c[2] * v * v; val b = c[3] + c[4] * v + c[5] * v * v; val cc = c[6] + c[7] * v + c[8] * v * v
                    for (x in 0 until width) {
                        val i = r * width + x; xs[i] = x.toFloat()
                        val u = 2.0 * x / (width - 1).coerceAtLeast(1) - 1
                        val d = (a * u + b * (u * u - 1.0 / 3) + cc * u * u * u).coerceIn(-LIMIT, LIMIT)
                        ys[i] = ((yn + d) * (height - 1)).toFloat()
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
