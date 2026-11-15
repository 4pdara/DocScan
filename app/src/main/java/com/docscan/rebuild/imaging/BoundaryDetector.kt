package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import com.docscan.rebuild.model.Vertex
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Shared camera/still detector (1.2.3). All coordinates are normalized to the unrotated input.
 *
 * The photo is first made print-free (two median passes erase text, embossing and thin gold lines, so only
 * page, cover and table boundaries remain). A side of a candidate quad is backed where the colour steps across
 * it with a sharp edge, or where it runs along a dark valley (the spine of an open book). Candidates come from
 * contours, Otsu regions and combinations of long lines (the frame border included), and from regions of one
 * surface colour grown from seed points near the middle and stopped at edges (the paper of a page apart from the
 * layered page edges of the book block, its cover and a chair or table of almost the same brightness); the best
 * ones are snapped to the strongest nearby edge, a region's side looking further out where the page shades into
 * the spine. Corners may lie outside the frame (a page cut by the frame): only the part of a side in view is
 * measured, and a side on the frame border counts only where the document visibly continues. A spine side (a
 * valley with paper on both sides) does not count against the page's even rim colour. Then: an open book whose
 * facing page runs off the frame is cut at the spine, and a strip of another material along a side (page edges,
 * a cloth spine, a band of table) is cut off; along a dark line on a page's long side only a strip of another
 * colour is, at the innermost clear edge. Measured on owner photographs and CamScanner reference screenshots and
 * screen recordings; see docs/DETECTION_1.2.1_HE.md and docs/DETECTION_1.2.3_HE.md.
 */
object BoundaryDetector {
    private const val EDGE = 640
    /** Diagnostics for the desktop measurement harness; null in the app. */
    internal var trace: ((String) -> Unit)? = null

    fun detect(gray: Mat, rgba: Mat? = null): Frame? {
        val rgb = Mat(); val work = Mat()
        try {
            if (rgba != null) Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB) else Imgproc.cvtColor(gray, rgb, Imgproc.COLOR_GRAY2RGB)
            if (rgb.cols() < 40 || rgb.rows() < 40) return null
            val f = EDGE.toDouble() / max(rgb.cols(), rgb.rows())
            if (f < 1) Imgproc.resize(rgb, work, Size((rgb.cols() * f).roundToInt().toDouble(), (rgb.rows() * f).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            else rgb.copyTo(work)
            val started = System.nanoTime()
            val scene = Scene.of(work).also { it.trace = trace }
            trace?.invoke("prepare %.0f ms".format((System.nanoTime() - started) / 1e6))
            val quad = try { scene.find() } finally { scene.release() } ?: return null
            val frame = Frame(quad.map { Vertex((it.x / (scene.w - 1)).toFloat().coerceIn(0f, 1f), (it.y / (scene.h - 1)).toFloat().coerceIn(0f, 1f)) })
            return frame.takeIf { it.valid() }
        } finally { rgb.release(); work.release() }
    }
}

private class Pt(val x: Double, val y: Double)

private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
private fun dist(a: Pt, b: Pt) = hypot(b.x - a.x, b.y - a.y)
private fun clip01(v: Double) = v.coerceIn(0.0, 1.0)

private fun intersect(a1: Pt, a2: Pt, b1: Pt, b2: Pt): Pt? {
    val d = (a1.x - a2.x) * (b1.y - b2.y) - (a1.y - a2.y) * (b1.x - b2.x)
    if (abs(d) < 1e-9) return null
    val a = a1.x * a2.y - a1.y * a2.x; val b = b1.x * b2.y - b1.y * b2.x
    return Pt((a * (b1.x - b2.x) - (a1.x - a2.x) * b) / d, (a * (b1.y - b2.y) - (a1.y - a2.y) * b) / d)
}

private fun area(q: List<Pt>): Double = abs((0..3).sumOf { i -> val a = q[i]; val b = q[(i + 1) % 4]; a.x * b.y - a.y * b.x }) / 2

/** Corners in clockwise order starting top-left (image coordinates, y down). */
private fun order(raw: List<Pt>): List<Pt> {
    val cx = raw.sumOf { it.x } / 4; val cy = raw.sumOf { it.y } / 4
    val points = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
    val start = points.indices.minBy { points[it].x + points[it].y }
    return (0..3).map { points[(start + it) % 4] }
}

private fun angleBetween(a1: Pt, a2: Pt, b1: Pt, b2: Pt): Double {
    val d = abs(atan2(a2.y - a1.y, a2.x - a1.x) - atan2(b2.y - b1.y, b2.x - b1.x)) % PI
    return min(d, PI - d)
}

private fun median(values: DoubleArray, n: Int): Double {
    if (n == 0) return 0.0
    val sorted = values.copyOf(n); sorted.sort()
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}

/** Evidence sampled along one side. The outward normal points away from the quad for clockwise corners. */
private class Samples(val m: Int) {
    val px = DoubleArray(m); val py = DoubleArray(m)
    val step = DoubleArray(m); val valley = DoubleArray(m); val dip = DoubleArray(m); val evidence = DoubleArray(m)
    val inner = FloatArray(m * 3); val outer = FloatArray(m * 3); val deep = FloatArray(m * 3)
    val insideOk = BooleanArray(m); val outsideOk = BooleanArray(m)
}

private class Scored(
    val q: List<Pt>, val full: List<Pt>, val support: DoubleArray, val ends: DoubleArray, val border: BooleanArray,
    val valleyish: BooleanArray, val nb: Int, val colour: FloatArray, val score: Double, val rim: Double = 0.0, var spine: Set<Int> = emptySet(),
    val hollow: BooleanArray = BooleanArray(4),
)

private class Scene(
    val w: Int, val h: Int, private val plain: Mat,
    private val lab: FloatArray, private val gx: FloatArray, private val gy: FloatArray,
    private val lum: FloatArray, private val dark: FloatArray, private val fine: FloatArray,
) {
    val s = min(w, h).toDouble()
    private val o = max(4.0, s * .022)
    var trace: ((String) -> Unit)? = null
    private fun describe(r: Scored) = r.q.joinToString(" ") { "(%.0f,%.0f)".format(it.x, it.y) } + " s=%.3f sup=%s ends=%s nb=%d".format(r.score,
        r.support.joinToString(",") { "%.2f".format(it) }, r.ends.joinToString(",") { "%.2f".format(it) }, r.nb) + " rim=%.0f".format(r.rim)

    fun release() = plain.release()

    companion object {
        fun of(rgb: Mat): Scene {
            val w = rgb.cols(); val h = rgb.rows(); val s = min(w, h).toDouble()
            val k = max(5, (s * .023).toInt() or 1)
            val first = Mat(); val plain = Mat(); val lab8 = Mat(); val labF = Mat(); val g = Mat(); val l = Mat(); val v = Mat()
            val kv = max(9, (s * .045).toInt() or 1)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kv.toDouble(), kv.toDouble()))
            try {
                // Print-free image: text, embossing and thin decorations disappear, surfaces and their boundaries stay.
                Imgproc.medianBlur(rgb, first, k); Imgproc.medianBlur(first, plain, k)
                Imgproc.cvtColor(plain, lab8, Imgproc.COLOR_RGB2Lab)
                lab8.convertTo(labF, CvType.CV_32FC3)
                Imgproc.GaussianBlur(labF, labF, Size(0.0, 0.0), 1.0)
                val lab = FloatArray(w * h * 3).also { labF.get(0, 0, it) }
                Imgproc.Sobel(labF, g, CvType.CV_32F, 1, 0, 3, 1.0 / 8, 0.0)
                val gx = FloatArray(w * h * 3).also { g.get(0, 0, it) }
                Imgproc.Sobel(labF, g, CvType.CV_32F, 0, 1, 3, 1.0 / 8, 0.0)
                val gy = FloatArray(w * h * 3).also { g.get(0, 0, it) }
                Core.extractChannel(labF, l, 0)
                val lum = FloatArray(w * h).also { l.get(0, 0, it) }
                // Dark valleys narrower than the kernel: the shadow in the fold of an open book.
                Imgproc.morphologyEx(l, v, Imgproc.MORPH_BLACKHAT, kernel)
                val dark = FloatArray(w * h).also { v.get(0, 0, it) }
                // Thin dark lines on the lightly smoothed photo (a drawn or sharply shadowed spine).
                Imgproc.cvtColor(rgb, lab8, Imgproc.COLOR_RGB2Lab); Core.extractChannel(lab8, l, 0); l.convertTo(l, CvType.CV_32F)
                Imgproc.GaussianBlur(l, l, Size(0.0, 0.0), 1.0)
                val fine = FloatArray(w * h).also { l.get(0, 0, it) }
                return Scene(w, h, plain, lab, gx, gy, lum, dark, fine)
            } catch (e: Throwable) { plain.release(); throw e }
            finally { first.release(); lab8.release(); labF.release(); g.release(); l.release(); v.release(); kernel.release() }
        }
    }

    private fun index(x: Double, y: Double) = y.roundToInt().coerceIn(0, h - 1) * w + x.roundToInt().coerceIn(0, w - 1)
    private fun clamp(p: Pt) = Pt(p.x.coerceIn(0.0, w - 1.0), p.y.coerceIn(0.0, h - 1.0))
    private fun clamp(q: List<Pt>) = q.map { clamp(it) }
    private fun colour(target: FloatArray, at: Int, x: Double, y: Double) { val i = index(x, y) * 3; target[at * 3] = lab[i]; target[at * 3 + 1] = lab[i + 1]; target[at * 3 + 2] = lab[i + 2] }
    private fun gap(a: FloatArray, i: Int, b: FloatArray, j: Int): Double {
        val dl = a[i * 3] - b[j * 3]; val da = a[i * 3 + 1] - b[j * 3 + 1]; val db = a[i * 3 + 2] - b[j * 3 + 2]
        return sqrt((dl * dl + da * da + db * db).toDouble())
    }
    private fun gap(a: FloatArray, i: Int, c: FloatArray): Double {
        val dl = a[i * 3] - c[0]; val da = a[i * 3 + 1] - c[1]; val db = a[i * 3 + 2] - c[2]
        return sqrt((dl * dl + da * da + db * db).toDouble())
    }
    private fun gap(a: FloatArray, b: FloatArray) = gap(a, 0, b)

    /** Per-channel median of the colours at the selected samples. */
    private fun medianColour(colours: FloatArray, take: (Int) -> Boolean, m: Int): FloatArray? {
        val n = (0 until m).count(take)
        if (n == 0) return null
        val values = DoubleArray(n)
        return FloatArray(3) { c -> var j = 0; for (k in 0 until m) if (take(k)) values[j++] = colours[k * 3 + c].toDouble(); median(values, n).toFloat() }
    }

    fun samples(a: Pt, b: Pt, m: Int = 28): Samples {
        val out = Samples(m)
        val length = max(1e-6, dist(a, b))
        val ux = (b.x - a.x) / length; val uy = (b.y - a.y) / length
        val nx = uy; val ny = -ux
        for (k in 0 until m) {
            val t = .06 + .88 * k / (m - 1)
            val px = a.x + (b.x - a.x) * t; val py = a.y + (b.y - a.y) * t
            out.px[k] = px; out.py[k] = py
            // The outer patch moves closer when the side runs near the frame edge.
            var room = 1e9
            if (nx > 1e-6) room = min(room, (w - 1 - px) / nx)
            if (nx < -1e-6) room = min(room, -px / nx)
            if (ny > 1e-6) room = min(room, (h - 1 - py) / ny)
            if (ny < -1e-6) room = min(room, -py / ny)
            val oo = (room - 1).coerceIn(0.0, o)
            val ix = px - nx * o; val iy = py - ny * o
            out.insideOk[k] = ix >= 0 && ix <= w - 1 && iy >= 0 && iy <= h - 1
            out.outsideOk[k] = oo >= 3
            colour(out.inner, k, ix, iy); colour(out.outer, k, px + nx * oo, py + ny * oo); colour(out.deep, k, px - nx * o * 2, py - ny * o * 2)
            out.step[k] = gap(out.inner, k, out.outer, k)
            var g = 0.0
            for (d in -2..2) {
                val i = index(px + nx * d, py + ny * d) * 3
                var sum = 0.0
                for (c in 0..2) { val dn = gx[i + c] * nx + gy[i + c] * ny; sum += dn * dn }
                g = max(g, sqrt(sum))
            }
            var low = Double.MAX_VALUE
            for (d in -3..3) low = min(low, lum[index(px + nx * d, py + ny * d)].toDouble())
            out.valley[k] = (out.inner[k * 3] + out.outer[k * 3]) / 2.0 - low
            // Below both sides, not just below their mean: a step between two surfaces is no valley.
            out.dip[k] = min(out.inner[k * 3], out.outer[k * 3]) - low
            val edge = min(clip01(out.step[k] / 16), clip01(g / 5))
            val fold = clip01((out.valley[k] - 4) / 14) * clip01(g / 3)
            out.evidence[k] = max(edge, fold)
        }
        return out
    }

    private fun onBorder(a: Pt, b: Pt): Boolean {
        val z = s * .02
        return (a.x <= z && b.x <= z) || (a.x >= w - 1 - z && b.x >= w - 1 - z) || (a.y <= z && b.y <= z) || (a.y >= h - 1 - z && b.y >= h - 1 - z)
    }

    private fun plausible(q: List<Pt>): Boolean {
        val margin = .25
        if (q.any { it.x < -margin * w || it.x > (1 + margin) * w || it.y < -margin * h || it.y > (1 + margin) * h }) return false
        for (i in 0..3) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) <= 0) return false
        }
        // Interior angles of a photographed rectangle stay within 50..130 degrees.
        for (i in 0..3) {
            val a = q[(i + 3) % 4]; val b = q[i]; val c = q[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y; val v2x = c.x - b.x; val v2y = c.y - b.y
            val cosine = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-9)
            if (abs(cosine) > cos(Math.toRadians(40.0))) return false
        }
        val share = area(clamp(q)) / (w.toDouble() * h)
        if (share < .05 || share > .985) return false
        val lengths = (0..3).map { dist(q[it], q[(it + 1) % 4]) }
        if (lengths.min() < s * .15) return false
        return max(lengths[0], lengths[2]) <= 2.2 * min(lengths[0], lengths[2]) && max(lengths[1], lengths[3]) <= 2.2 * min(lengths[1], lengths[3])
    }

    /**
     * Sides are measured where they are in view; a side mostly outside the frame or lying on the frame border is
     * inferred, and backed only where the document's own colour continues up to the frame edge.
     */
    fun score(raw: List<Pt>): Scored? {
        val q = raw
        val sides = (0..3).map { samples(q[it], q[(it + 1) % 4]) }
        val seen = sides.map { sm -> BooleanArray(sm.m) { sm.px[it] >= 1 && sm.px[it] <= w - 2 && sm.py[it] >= 1 && sm.py[it] <= h - 2 } }
        val clamped = (0..3).map { clamp(q[it]) to clamp(q[(it + 1) % 4]) }
        val border = BooleanArray(4) { seen[it].count { v -> v } < .3 * seen[it].size || onBorder(clamped[it].first, clamped[it].second) }
        val real = (0..3).filter { !border[it] }
        if (real.size < 2) return null
        // The document's own colour, a little inside its real sides.
        val pool = ArrayList<Int>()
        for (i in real) for (k in 0 until sides[i].m) if (sides[i].insideOk[k] && seen[i][k]) pool += i * 1000 + k
        if (pool.size < 8) return null
        val values = DoubleArray(pool.size)
        val rimColour = FloatArray(3) { c -> pool.forEachIndexed { j, key -> values[j] = sides[key / 1000].deep[(key % 1000) * 3 + c].toDouble() }; median(values, pool.size).toFloat() }
        val support = DoubleArray(4); val ends = DoubleArray(4) { 1.0 }; val valleyish = BooleanArray(4); val deviation = DoubleArray(4); val hollow = BooleanArray(4)
        for (i in 0..3) {
            if (border[i]) {
                val (ca, cb) = clamped[i]
                support[i] = if (dist(ca, cb) < 2) .75 * .5 else {
                    val bs = samples(ca, cb)
                    .75 * (0 until bs.m).count { gap(bs.deep, it, rimColour) < 25 } / bs.m
                }
                continue
            }
            val sm = sides[i]; val view = seen[i]
            val ev = DoubleArray(sm.m) { if (sm.outsideOk[it]) sm.evidence[it] else if (gap(sm.deep, it, rimColour) < 25) .9 else 0.0 }
            val visible = (0 until sm.m).filter { view[it] }
            support[i] = if (visible.isEmpty()) .5 else visible.sumOf { ev[it] } / visible.size
            val quarter = max(3, sm.m / 4)
            val low = (0 until quarter).filter { view[it] }; val high = (sm.m - quarter until sm.m).filter { view[it] }
            ends[i] = min(if (low.size >= 2) low.sumOf { ev[it] } / low.size else 1.0, if (high.size >= 2) high.sumOf { ev[it] } / high.size else 1.0)
            val measured = (0 until sm.m).filter { view[it] && sm.insideOk[it] && sm.outsideOk[it] }
            if (measured.size >= 3) {
                val buffer = DoubleArray(measured.size)
                measured.forEachIndexed { j, k -> buffer[j] = sm.valley[k] }; val valley = median(buffer, measured.size)
                measured.forEachIndexed { j, k -> buffer[j] = sm.step[k] }; val step = median(buffer, measured.size)
                measured.forEachIndexed { j, k -> buffer[j] = sm.dip[k] }; val dip = median(buffer, measured.size)
                valleyish[i] = valley > 8 && step < 30
                hollow[i] = valleyish[i] && dip > 3
                // The spine of an open book: a dark valley with paper beyond it. The page shades towards it, so its rim
                // is darker than elsewhere without being another material.
                val inside = medianColour(sm.deep, { it in measured }, sm.m)
                val beyond = medianColour(sm.outer, { it in measured }, sm.m)
                val spine = valley > 8 && inside != null && beyond != null && gap(beyond, rimColour) < 25 &&
                    hypot(inside[1] - rimColour[1].toDouble(), inside[2] - rimColour[2].toDouble()) < 12 && abs(inside[0] - rimColour[0]) < 60
                if (!valleyish[i] && !spine) medianColour(sm.deep, { it in measured }, sm.m)?.let { deviation[i] = gap(it, rimColour) }
            }
        }
        val nb = border.count { it }
        val cq = clamp(q)
        val share = area(cq) / (w.toDouble() * h)
        val lengths = (0..3).map { dist(q[it], q[(it + 1) % 4]) }
        val unseen = real.sumOf { lengths[it] * (1 - seen[it].count { v -> v }.toDouble() / seen[it].size) } / lengths.sum()
        val rim = real.maxOf { deviation[it] }
        val overshoot = ends.sumOf { max(0.0, .5 - it) }
        val total = .55 * support.average() + .3 * sqrt(share) + .15 * real.minOf { support[it] } - .25 * ((rim - 22) / 40).coerceIn(0.0, 1.0) -
            .1 * nb - .2 * overshoot - .1 * unseen
        return Scored(cq, q, support, ends, border, valleyish, nb, rimColour, total, rim, hollow = hollow)
    }

    /** A cheap estimate of [score]: side evidence at a few points, sides on the frame border counted as half. */
    private fun glance(q: List<Pt>): Double {
        var support = 0.0
        for (i in 0..3) {
            val a = q[i]; val b = q[(i + 1) % 4]
            if (onBorder(clamp(a), clamp(b))) { support += .4; continue }
            val sm = samples(a, b, 10)
            var sum = 0.0; var n = 0
            for (k in 0 until sm.m) if (sm.px[k] >= 1 && sm.px[k] <= w - 2 && sm.py[k] >= 1 && sm.py[k] <= h - 2) { sum += if (sm.outsideOk[k]) sm.evidence[k] else .5; n++ }
            support += if (n == 0) .4 else sum / n
        }
        return .55 * support / 4 + .3 * sqrt(area(clamp(q)) / (w.toDouble() * h))
    }

    // ------------------------------------------------------------------------------------------------ candidates

    /** Quadrilaterals around large contours; [scale] maps the image's pixels to the scene's (for a smaller mask). */
    private fun contourQuads(image: Mat, mode: Int, out: MutableList<List<Pt>>, scale: Double = 1.0) {
        val found = ArrayList<MatOfPoint>(); val hierarchy = Mat()
        fun at(x: Double, y: Double) = if (scale == 1.0) Pt(x, y) else Pt((x + .5) * scale - .5, (y + .5) * scale - .5)
        try {
            Imgproc.findContours(image, found, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
            found.map { it to abs(Imgproc.contourArea(it)) }.filter { it.second >= image.cols() * image.rows() * .045 }.sortedByDescending { it.second }.take(25).forEach { (contour, _) ->
                val indices = MatOfInt(); val hull = MatOfPoint2f(); val approximation = MatOfPoint2f()
                try {
                    Imgproc.convexHull(contour, indices)
                    val points = contour.toArray(); val hullPoints = indices.toArray().map { points[it] }
                    hull.fromList(hullPoints)
                    val perimeter = Imgproc.arcLength(hull, true)
                    for (epsilon in doubleArrayOf(.015, .03, .05, .08)) {
                        Imgproc.approxPolyDP(hull, approximation, perimeter * epsilon, true)
                        if (approximation.total() == 4L) out += order(approximation.toList().map { at(it.x, it.y) })
                    }
                    // The hull's four extreme directions survive curved or bulging sides.
                    out += order(listOf(hullPoints.minBy { it.x + it.y }, hullPoints.maxBy { it.x - it.y }, hullPoints.maxBy { it.x + it.y }, hullPoints.minBy { it.x - it.y }).map { at(it.x, it.y) })
                } finally { indices.release(); hull.release(); approximation.release() }
            }
        } finally { found.forEach { it.release() }; hierarchy.release() }
    }

    private class Line(val a: Pt, val b: Pt, var length: Double, val theta: Double, val rho: Double, val border: Boolean = false)

    private fun lines(edges: Mat, valleys: Mat): List<Line> {
        val segments = ArrayList<DoubleArray>()
        val found = Mat()
        try {
            for ((image, threshold) in listOf(edges to .07, valleys to .06)) {
                Imgproc.HoughLinesP(image, found, 1.0, PI / 180, max(20, (s * threshold).toInt()), s * .12, s * .05)
                for (i in 0 until found.rows()) found.get(i, 0)?.takeIf { it.size >= 4 }?.let { segments += it }
            }
        } finally { found.release() }
        // Collinear segments merge into one line with their total length.
        val merged = ArrayList<Line>()
        segments.map { sg ->
            val theta = ((atan2(sg[3] - sg[1], sg[2] - sg[0]) % PI) + PI) % PI
            Triple(hypot(sg[2] - sg[0], sg[3] - sg[1]), theta, sg)
        }.sortedByDescending { it.first }.forEach { (length, theta, sg) ->
            val rho = sg[0] * -sin(theta) + sg[1] * cos(theta)
            val same = merged.firstOrNull { line ->
                var turn = abs(theta - line.theta); turn = min(turn, PI - turn)
                val r = if (abs(theta - line.theta) < PI / 2) rho else -rho
                turn < Math.toRadians(3.5) && abs(r - line.rho) < s * .02
            }
            if (same != null) same.length += length else merged += Line(Pt(sg[0], sg[1]), Pt(sg[2], sg[3]), length, theta, rho)
        }
        return merged
    }

    private fun lineQuads(found: List<Line>, out: MutableList<List<Pt>>, per: Int = 7) {
        fun yAt(l: Line, x: Double) = l.a.y + (l.b.y - l.a.y) * (x - l.a.x) / (l.b.x - l.a.x + 1e-9)
        fun xAt(l: Line, y: Double) = l.a.x + (l.b.x - l.a.x) * (y - l.a.y) / (l.b.y - l.a.y + 1e-9)
        val horizontal = found.filter { min(it.theta, PI - it.theta) < Math.toRadians(40.0) }.sortedByDescending { it.length }
        val vertical = found.filter { abs(it.theta - PI / 2) < Math.toRadians(40.0) }.sortedByDescending { it.length }
        val top = horizontal.filter { yAt(it, w / 2.0) < h * .6 }.take(per) + Line(Pt(0.0, 0.0), Pt(w - 1.0, 0.0), 0.0, 0.0, 0.0, true)
        val bottom = horizontal.filter { yAt(it, w / 2.0) > h * .4 }.take(per) + Line(Pt(0.0, h - 1.0), Pt(w - 1.0, h - 1.0), 0.0, 0.0, 0.0, true)
        val left = vertical.filter { xAt(it, h / 2.0) < w * .6 }.take(per) + Line(Pt(0.0, 0.0), Pt(0.0, h - 1.0), 0.0, PI / 2, 0.0, true)
        val right = vertical.filter { xAt(it, h / 2.0) > w * .4 }.take(per) + Line(Pt(w - 1.0, 0.0), Pt(w - 1.0, h - 1.0), 0.0, PI / 2, 0.0, true)
        for (t in top) for (b in bottom) {
            if (yAt(b, w / 2.0) - yAt(t, w / 2.0) < s * .25) continue
            for (l in left) for (r in right) {
                if (xAt(r, h / 2.0) - xAt(l, h / 2.0) < s * .25) continue
                if (listOf(t, b, l, r).count { it.border } > 2) continue
                val corners = listOf(intersect(t.a, t.b, l.a, l.b), intersect(t.a, t.b, r.a, r.b), intersect(b.a, b.b, r.a, r.b), intersect(b.a, b.b, l.a, l.b))
                if (corners.all { it != null }) out += corners.filterNotNull()
            }
        }
    }

    /** Squared colour distance with lightness counted at half weight (shading changes lightness first). */
    private fun near(colours: FloatArray, i: Int, c: FloatArray): Float {
        val dl = (colours[i * 3] - c[0]) * .5f; val da = colours[i * 3 + 1] - c[1]; val db = colours[i * 3 + 2] - c[2]
        return dl * dl + da * da + db * db
    }

    /**
     * Grows a region from the seed patch through neighbours of nearly the same colour: slow shading (towards the
     * spine of a book) is followed, a step to another surface is not.
     */
    private fun grow(gw: Int, gh: Int, colours: FloatArray, sx: Int, sy: Int, r: Int, seed: FloatArray, blocked: ByteArray, region: ByteArray, queue: IntArray) {
        java.util.Arrays.fill(region, 0)
        var head = 0; var tail = 0
        val total = 34f * 34f
        for (y in max(0, sy - r)..min(gh - 1, sy + r)) for (x in max(0, sx - r)..min(gw - 1, sx + r)) {
            val i = y * gw + x
            if (blocked[i].toInt() == 0 && near(colours, i, seed) < total) { region[i] = -1; queue[tail++] = i }
        }
        val here = FloatArray(3)
        while (head < tail) {
            val p = queue[head++]; val px = p % gw; val py = p / gw
            here[0] = colours[p * 3]; here[1] = colours[p * 3 + 1]; here[2] = colours[p * 3 + 2]
            for (d in 0..3) {
                val nx = px + (if (d == 0) 1 else if (d == 1) -1 else 0); val ny = py + (if (d == 2) 1 else if (d == 3) -1 else 0)
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                val n = ny * gw + nx
                if (region[n].toInt() != 0 || blocked[n].toInt() != 0) continue
                if (near(colours, n, here) < 3f * 3f && near(colours, n, seed) < total) { region[n] = -1; queue[tail++] = n }
            }
        }
    }

    /**
     * Regions of one surface colour, grown from a few seed points and stopped at edges: the paper of a page stays
     * apart from the page edges of the book block, a cover and a table or chair of almost the same brightness.
     * Worked out at half resolution (the region's outline is refined against the full-size evidence afterwards).
     */
    private fun surfaceQuads(edges: Mat, out: MutableList<List<Pt>>) {
        val gw = (w + 1) / 2; val gh = (h + 1) / 2
        val barrier = Mat(); val mask = Mat(gh, gw, CvType.CV_8UC1); val labels = Mat(); val thicken = Mat.ones(3, 3, CvType.CV_8U)
        val k = max(3, (s * .01).toInt() or 1).toDouble()
        val opening = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k, k))
        val full = ByteArray(w * h); val blocked = ByteArray(gw * gh); val bytes = ByteArray(gw * gh); val queue = IntArray(gw * gh)
        val colours = FloatArray(gw * gh * 3); val counts = IntArray(gw * gh)
        try {
            Imgproc.dilate(edges, barrier, thicken)
            barrier.get(0, 0, full)
            for (y in 0 until h) for (x in 0 until w) {
                val g = (y / 2) * gw + x / 2; val i = y * w + x
                colours[g * 3] += lab[i * 3]; colours[g * 3 + 1] += lab[i * 3 + 1]; colours[g * 3 + 2] += lab[i * 3 + 2]; counts[g]++
                if (full[i].toInt() != 0) blocked[g] = 1
            }
            for (g in 0 until gw * gh) { val n = counts[g].toFloat(); colours[g * 3] /= n; colours[g * 3 + 1] /= n; colours[g * 3 + 2] /= n }
            val r = max(2, (s * .03).toInt())
            val seeds = listOf(.5 to .5, .35 to .5, .65 to .5, .5 to .35, .5 to .65).map { (fx, fy) -> (fx * (gw - 1)).roundToInt() to (fy * (gh - 1)).roundToInt() }
            val values = DoubleArray((2 * r + 1) * (2 * r + 1))
            val done = ArrayList<FloatArray>()
            val one = IntArray(1)
            for ((sx, sy) in seeds) {
                val seed = FloatArray(3) { c ->
                    var n = 0
                    for (y in max(0, sy - r)..min(gh - 1, sy + r)) for (x in max(0, sx - r)..min(gw - 1, sx + r)) values[n++] = colours[(y * gw + x) * 3 + c].toDouble()
                    median(values, n).toFloat()
                }
                if (done.any { gap(it, seed) < 6 }) continue
                done += seed
                for (threshold in doubleArrayOf(14.0, 20.0, 0.0)) {
                    if (threshold > 0) {
                        val limit = (threshold * threshold).toFloat()
                        for (i in 0 until gw * gh) bytes[i] = if (blocked[i].toInt() == 0 && near(colours, i, seed) < limit) -1 else 0
                    } else grow(gw, gh, colours, sx, sy, r, seed, blocked, bytes, queue)
                    mask.put(0, 0, bytes)
                    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, opening)
                    Imgproc.connectedComponents(mask, labels, 4, CvType.CV_32S)
                    // The region the seed patch falls in (most of the patch, text and edges aside).
                    val votes = HashMap<Int, Int>()
                    for (y in max(0, sy - r)..min(gh - 1, sy + r) step 2) for (x in max(0, sx - r)..min(gw - 1, sx + r) step 2) {
                        labels.get(y, x, one); if (one[0] > 0) votes[one[0]] = (votes[one[0]] ?: 0) + 1
                    }
                    val label = votes.maxByOrNull { it.value }?.key ?: continue
                    Core.compare(labels, Scalar(label.toDouble()), mask, Core.CMP_EQ)
                    contourQuads(mask, Imgproc.RETR_EXTERNAL, out, 2.0)
                }
            }
        } finally { barrier.release(); mask.release(); labels.release(); opening.release(); thicken.release() }
    }

    /** Shapes from contours, regions and lines; surface regions kept apart (they are always measured). */
    private fun candidates(): Pair<List<List<Pt>>, List<List<Pt>>> {
        val out = ArrayList<List<Pt>>(); val regions = ArrayList<List<Pt>>()
        val lab8 = Mat(); val channel = Mat(); val edges = Mat.zeros(h, w, CvType.CV_8UC1); val canny = Mat(); val valleys = Mat(); val closed = Mat(); val gray = Mat(); val mask = Mat()
        val darkMat = Mat(h, w, CvType.CV_32FC1); val small = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            Imgproc.cvtColor(plain, lab8, Imgproc.COLOR_RGB2Lab)
            val thresholds = arrayOf(doubleArrayOf(20.0, 50.0), doubleArrayOf(12.0, 30.0), doubleArrayOf(12.0, 30.0))
            for (c in 0..2) {
                Core.extractChannel(lab8, channel, c)
                Imgproc.GaussianBlur(channel, channel, Size(0.0, 0.0), 1.2)
                Imgproc.Canny(channel, canny, thresholds[c][0], thresholds[c][1])
                Core.bitwise_or(edges, canny, edges)
            }
            darkMat.put(0, 0, dark)
            Imgproc.threshold(darkMat, darkMat, 12.0, 255.0, Imgproc.THRESH_BINARY)
            darkMat.convertTo(valleys, CvType.CV_8U)
            Imgproc.erode(valleys, valleys, small)
            for (d in doubleArrayOf(3.0, 7.0)) {
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(d, d))
                try { Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel); contourQuads(closed, Imgproc.RETR_LIST, out) } finally { kernel.release() }
            }
            Imgproc.cvtColor(plain, gray, Imgproc.COLOR_RGB2GRAY)
            for (inverse in listOf(false, true)) {
                Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_OTSU or if (inverse) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY)
                contourQuads(mask, Imgproc.RETR_EXTERNAL, out)
            }
            lineQuads(lines(edges, valleys), out)
            surfaceQuads(edges, regions)
        } finally {
            lab8.release(); channel.release(); edges.release(); canny.release(); valleys.release(); closed.release(); gray.release(); mask.release(); darkMat.release(); small.release()
        }
        return out to regions
    }

    // ------------------------------------------------------------------------------------------------ refinement

    /** Each side's two ends move along its normal to where the evidence is best; the corners follow. */
    private fun refine(q: List<Pt>, wide: Boolean = false): List<Pt> {
        val lines = (0..3).map { arrayOf(q[it], q[(it + 1) % 4]) }.toMutableList()
        val step = max(1.5, s * .006)
        repeat(2) { pass ->
            for (i in 0..3) {
                val a = lines[i][0]; val b = lines[i][1]
                if (onBorder(clamp(a), clamp(b))) continue
                val length = max(1e-6, dist(a, b)); val nx = (b.y - a.y) / length; val ny = -(b.x - a.x) / length
                val before = lines[(i + 3) % 4]; val after = lines[(i + 1) % 4]
                fun trial(da: Int, db: Int): Pair<Double, Array<Pt>>? {
                    val a2 = Pt(a.x + nx * da * step, a.y + ny * da * step); val b2 = Pt(b.x + nx * db * step, b.y + ny * db * step)
                    val p0 = intersect(before[0], before[1], a2, b2) ?: return null
                    val p1 = intersect(a2, b2, after[0], after[1]) ?: return null
                    if (dist(p0, p1) < s * .1) return null
                    val sm = samples(clamp(p0), clamp(p1))
                    val ok = (0 until sm.m).filter { sm.outsideOk[it] }
                    val value = if (ok.size >= 5) ok.sumOf { sm.evidence[it] } / ok.size else 0.0
                    return value - .004 * (abs(da) + abs(db)) to arrayOf(a2, b2)
                }
                // A coarse grid, then the neighbours of the best.
                // A region's side may stop short of the edge where the page shades into the spine: a side without
                // support looks further out (and a little in) on the first pass.
                val far = wide && pass == 0 && (trial(0, 0)?.first ?: 0.0) < .5
                val range = if (far) -6..38 else -4..4; val stride = if (far) 4 else 2; val near = stride / 2
                var best: Triple<Double, Array<Pt>, Pair<Int, Int>>? = null
                for (da in range step stride) for (db in range step stride) trial(da, db)?.let { if (best == null || it.first > best!!.first) best = Triple(it.first, it.second, da to db) }
                val coarse = best ?: continue
                val (ba, bb) = coarse.third
                for (da in ba - near..ba + near) for (db in bb - near..bb + near) {
                    if (da == ba && db == bb) continue
                    trial(da, db)?.let { if (it.first > best!!.first) best = Triple(it.first, it.second, da to db) }
                }
                lines[i] = best!!.second
            }
        }
        val corners = (0..3).map { j -> val p = lines[(j + 3) % 4]; val c = lines[j]; intersect(p[0], p[1], c[0], c[1]) }
        return if (corners.any { it == null }) q else corners.filterNotNull()
    }

    // ------------------------------------------------------------------------------------------------ selection

    private fun lineEvidence(a: Pt, b: Pt, spine: Boolean = false): Pair<Double, Samples> {
        val sm = samples(a, b)
        val ev = sm.evidence.copyOf()
        if (spine) {
            // A spine line runs the full height of the page; a printed rule stops at the margins.
            val length = max(1e-6, dist(a, b)); val nx = (b.y - a.y) / length; val ny = -(b.x - a.x) / length
            val o2 = max(3.0, s * .01)
            val line = DoubleArray(sm.m) { k ->
                val px = sm.px[k]; val py = sm.py[k]
                var low = Double.MAX_VALUE
                for (d in -2..2) low = min(low, fine[index(px + nx * d, py + ny * d)].toDouble())
                val base = (fine[index(px - nx * o2, py - ny * o2)] + fine[index(px + nx * o2, py + ny * o2)]) / 2.0
                clip01((base - low - 6) / 14)
            }
            val k = max(3, sm.m / 5)
            if ((0 until k).sumOf { line[it] } / k >= .5 && (sm.m - k until sm.m).sumOf { line[it] } / k >= .5) for (j in 0 until sm.m) ev[j] = max(ev[j], line[j])
        }
        val ok = (0 until sm.m).filter { sm.outsideOk[it] && sm.insideOk[it] }
        if (ok.size < 6) return 0.0 to sm
        return ok.sumOf { ev[it] } / ok.size to sm
    }

    private fun profile(a: Pt, b: Pt): DoubleArray {
        val sm = samples(a, b, 50)
        return DoubleArray(sm.m) { if (sm.outsideOk[it]) sm.evidence[it] else Double.NaN }
    }

    /** Support that runs up to position [u] along a side and stops there: a corner of the document. */
    private fun cornerDrop(profile: DoubleArray, u: Double, span: Double = .15): Double {
        val m = profile.size
        var bs = 0.0; var bn = 0; var asum = 0.0; var an = 0
        for (k in 0 until m) {
            val t = .06 + .88 * k / (m - 1); val v = profile[k]
            if (v.isNaN()) continue
            if (t > u - span && t <= u) { bs += v; bn++ } else if (t > u && t <= u + span) { asum += v; an++ }
        }
        if (bn < 3 || an < 3) return 0.0
        val before = bs / bn; val after = asum / an
        return if (before > .7 && after < .3) clip01((before - after - .1) / .5) else 0.0
    }

    private class Cut(val score: Double, val evidence: Double, val cornerA: Double, val cornerB: Double, val a: Pt, val b: Pt, val samples: Samples)

    /** The best line across the quad roughly parallel to side [j], its ends on the neighbouring sides at [from]..[to] of their length. */
    private fun bestCut(q: List<Pt>, j: Int, from: Double, to: Double, maxTurn: Double, spine: Boolean, deepest: Double = 1.0, innermost: Boolean = false): Cut? {
        val a0 = q[(j + 3) % 4]; val a1 = q[j]
        val b0 = q[(j + 2) % 4]; val b1 = q[(j + 1) % 4]
        // Roughly parallel to side j, or to the opposite side (a page seen from the side converges towards one end).
        fun turn(a: Pt, b: Pt) = min(angleBetween(a, b, a1, b1), angleBetween(a, b, b0, a0))
        val pa = profile(a0, a1); val pb = profile(b0, b1)
        val positions = generateSequence(from) { it + .02 }.takeWhile { it < to + 1e-9 }.toList()
        val cornersB = positions.map { cornerDrop(pb, it) }
        var best: Cut? = null
        for (u in positions) {
            val ca = cornerDrop(pa, u); val a = lerp(a0, a1, u)
            positions.forEachIndexed { vi, v ->
                val b = lerp(b0, b1, v)
                if (turn(a, b) > Math.toRadians(maxTurn) || (u + v) / 2 > deepest) return@forEachIndexed
                val (ev, sm) = lineEvidence(a, b, spine)
                val cb = cornersB[vi]
                // Innermost: the edge nearest the middle that is clearly an edge (the page itself, inside the
                // layered page edges of a book block).
                val score = if (innermost) (if (ev >= .6) 2 - (u + v) / 2 + .1 * ev else ev - 1) else ev + .35 * (ca + cb)
                if (best == null || score > best!!.score) best = Cut(score, ev, ca, cb, a, b, sm)
            }
        }
        return best
    }

    /**
     * The document ends before the frame border: an open book whose facing page runs off the frame (cut at the
     * spine), or a page whose top lies against a light surface reaching the frame edge. Paper only.
     */
    private fun splitBook(r: Scored): Scored? {
        if (r.nb != 1) return null
        val i = r.border.indexOfFirst { it }
        val paper = r.colour
        if (paper[0] < 140 || hypot(paper[1] - 128.0, paper[2] - 128.0) > 28) return null
        // The border side is side i: q[i] -> q[i+1]; positions here run from the far ends towards it.
        val q = r.q
        val cut = bestCut(q, i, .3, .96, 12.0, true) ?: return null
        if (!(cut.evidence >= .55 || (max(cut.cornerA, cut.cornerB) >= .8 && cut.evidence >= .25 && cut.score >= .6))) return null
        val sm = cut.samples
        val ok = (0 until sm.m).filter { sm.outsideOk[it] && sm.insideOk[it] }
        if (ok.size < 6) return null
        // Paper on both sides of the cut.
        val near = medianColour(sm.deep, { it in ok }, sm.m) ?: return null
        val length = max(1e-6, dist(cut.a, cut.b)); val nx = (cut.b.y - cut.a.y) / length; val ny = -(cut.b.x - cut.a.x) / length
        val farSamples = FloatArray(20 * 3)
        for (k in 0 until 20) { val t = .1 + .8 * k / 19; val p = lerp(cut.a, cut.b, t); colour(farSamples, k, p.x + nx * 2 * o, p.y + ny * 2 * o) }
        val far = medianColour(farSamples, { true }, 20) ?: return null
        if (gap(near, paper) > 30 || gap(far, paper) > 30) return null
        val page = q.toMutableList(); page[i] = cut.a; page[(i + 1) % 4] = cut.b
        val ordered = order(page)
        if (area(ordered) < .3 * area(q)) return null
        val result = score(ordered) ?: return null
        val middle = lerp(cut.a, cut.b, .5)
        result.spine = (0..3).filter { dist(lerp(result.q[it], result.q[(it + 1) % 4], .5), middle) < s * .03 }.toSet()
        return result
    }

    /**
     * The document ends before one of the quad's sides: a strip of another material along it (a book's page
     * edges, a cloth spine, a band of table), or both neighbouring sides stop at the same line.
     */
    private fun trimStrips(start: Scored): Scored {
        var r = start
        repeat(2) {
            val q = r.q
            var changed = false
            for (j in 0..3) {
                // A page's spine side is a shadow, not a strip of another material. A side that runs in a dark valley
                // is cut only across the page's short way (a band of table above a book): a spine runs along a page's
                // long side, and its shadow is not cut away.
                if (r.border[j] || j in r.spine) continue
                // A side in a real dark valley (darker than both of its sides; a plain step between two surfaces is not).
                val inValley = r.hollow[j]
                val alongValley = inValley && dist(q[j], q[(j + 1) % 4]) >= dist(q[(j + 1) % 4], q[(j + 2) % 4])
                val cut = bestCut(q, j, .66, .97, 12.0, false, if (inValley && !alongValley) .88 else 1.0, alongValley) ?: continue
                val sm = cut.samples
                val ok = (0 until sm.m).filter { sm.outsideOk[it] && sm.insideOk[it] }
                if (ok.size < 6) continue
                val p0 = q[j]; val p1 = q[(j + 1) % 4]
                val m0 = lerp(cut.a, p0, .5); val m1 = lerp(cut.b, p1, .5)
                val stripSamples = FloatArray(16 * 3)
                for (k in 0 until 16) { val p = lerp(m0, m1, .1 + .8 * k / 15); colour(stripSamples, k, p.x, p.y) }
                val strip = medianColour(stripSamples, { true }, 16) ?: continue
                val inner = medianColour(sm.deep, { it in ok }, sm.m) ?: continue
                // Along a dark valley on the page's long side only another material is cut (the page edges of the
                // book block, a cover); a strip of the page's own colour, only darker, is the shade of its spine.
                if (alongValley) {
                    val depths = doubleArrayOf(.25, .5, .75).map { f ->
                        val d0 = lerp(p0, cut.a, f); val d1 = lerp(p1, cut.b, f)
                        val at = FloatArray(16 * 3)
                        for (k in 0 until 16) { val p = lerp(d0, d1, .1 + .8 * k / 15); colour(at, k, p.x, p.y) }
                        medianColour(at, { true }, 16)!!
                    }
                    if (depths.all { hypot(it[1] - inner[1].toDouble(), it[2] - inner[2].toDouble()) < 10 }) continue
                }
                val buffer = DoubleArray(ok.size)
                ok.forEachIndexed { n, k -> buffer[n] = sm.valley[k] }; val valley = median(buffer, ok.size)
                ok.forEachIndexed { n, k -> buffer[n] = sm.step[k] }; val step = median(buffer, ok.size)
                // The strip is unlike the surface just inside the cut and unlike the rim along at least two of the
                // other sides (a page margin matches the margins elsewhere).
                val unlike = (1..3).count { d ->
                    val k = (j + d) % 4
                    if (r.border[k]) return@count false
                    val other = samples(q[k], q[(k + 1) % 4])
                    val rim = medianColour(other.deep, { other.insideOk[it] }, other.m) ?: return@count false
                    gap(strip, rim) >= 15
                }
                // ...and it runs the whole length of the side (a title plate in the middle of a cover is no strip).
                val along = (0 until 16).count { gap(stripSamples, it, inner) >= 15 } / 16.0
                val material = cut.evidence >= .6 && (valley < 8 || step >= 30) && gap(strip, inner) >= 15 && unlike >= 2 && along >= .75
                val corners = cut.cornerA >= .8 && cut.cornerB >= .8 && cut.evidence >= .3 && !r.valleyish[j]
                if (!material && !corners) continue
                val trimmed = q.toMutableList(); trimmed[j] = cut.a; trimmed[(j + 1) % 4] = cut.b
                val next = score(trimmed) ?: continue
                if (next.support[j] < .3 || next.score < r.score - .15) continue
                next.spine = r.spine
                trace?.invoke("trim side $j ev=%.2f corners=%.2f,%.2f -> ".format(cut.evidence, cut.cornerA, cut.cornerB) + describe(next))
                r = next; changed = true
                break
            }
            if (!changed) return r
        }
        return r
    }

    fun find(): List<Pt>? {
        val seen = HashSet<List<Int>>()
        val scored = ArrayList<Scored>()
        var clock = System.nanoTime()
        fun lap(name: String) { trace?.invoke("$name %.0f ms".format((System.nanoTime() - clock) / 1e6)); clock = System.nanoTime() }
        val (shapes, surfaces) = candidates()
        lap("candidates")
        fun key(q: List<Pt>) = q.flatMap { listOf((it.x / 6).roundToInt(), (it.y / 6).roundToInt()) }
        // A quick look (fewer samples, no colours) keeps the most promising candidates for the full measurement.
        val quick = ArrayList<Pair<List<Pt>, Double>>()
        for (q in shapes + surfaces) {
            if (!plausible(q)) continue
            if (!seen.add(key(q))) continue
            quick += q to glance(q)
        }
        quick.sortedByDescending { it.second }.take(120).forEach { (q, _) -> score(q)?.let { scored += it } }
        // Surface regions are few and always measured: a side may stop short of its edge (where the page shades
        // into the spine) until refinement looks further out.
        val distinct = HashSet<List<Int>>()
        val regions = surfaces.filter { plausible(it) && distinct.add(key(it)) }.mapNotNull { score(it) }.sortedByDescending { it.score }.take(2)
        lap("score")
        // Each of the best candidates is snapped to the strongest edges near its sides.
        val refined = scored.sortedByDescending { it.score }.take(12).mapNotNull { score(refine(it.full)) } + regions.mapNotNull { score(refine(it.full, true)) }
        lap("refine")
        var best = refined.maxByOrNull { it.score } ?: return null
        trace?.let { t -> t("${scored.size} candidates"); refined.sortedByDescending { it.score }.take(5).forEach { t("  " + describe(it)) } }
        splitBook(best)?.let { best = it; trace?.invoke("spine " + describe(it)) }
        lap("split")
        best = trimStrips(best)
        lap("trim")
        return best.q
    }
}
