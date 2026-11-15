package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import com.docscan.rebuild.model.Vertex
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Shared camera/still detector (1.2.1). All coordinates are normalized to the unrotated input.
 *
 * The photo is first made print-free (two median passes erase text, embossing and thin gold lines, so only
 * page, cover and table boundaries remain). A side of a candidate quad is backed where the colour steps across
 * it with a sharp edge, or where it runs along a dark valley (the spine of an open book). Candidates come from
 * contours, Otsu regions and combinations of long lines (the frame border included); the best ones are snapped
 * to the strongest nearby edge. Corners may lie outside the frame (a page cut by the frame): only the part of a
 * side in view is measured, and a side on the frame border counts only where the document visibly continues.
 * Then: an open book whose facing page runs off the frame is cut at the spine, and a strip of another material
 * along a side (page edges, a cloth spine, a band of table) is cut off. Measured on owner photographs and
 * CamScanner reference screenshots; see docs/DETECTION_1.2.1_HE.md.
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
    val step = DoubleArray(m); val valley = DoubleArray(m); val evidence = DoubleArray(m)
    val inner = FloatArray(m * 3); val outer = FloatArray(m * 3); val deep = FloatArray(m * 3)
    val insideOk = BooleanArray(m); val outsideOk = BooleanArray(m)
}

private class Scored(
    val q: List<Pt>, val full: List<Pt>, val support: DoubleArray, val ends: DoubleArray, val border: BooleanArray,
    val valleyish: BooleanArray, val nb: Int, val colour: FloatArray, val score: Double, val rim: Double = 0.0, var spine: Set<Int> = emptySet(),
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
        val support = DoubleArray(4); val ends = DoubleArray(4) { 1.0 }; val valleyish = BooleanArray(4); val deviation = DoubleArray(4)
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
                valleyish[i] = valley > 8 && step < 30
                if (!valleyish[i]) medianColour(sm.deep, { it in measured }, sm.m)?.let { deviation[i] = gap(it, rimColour) }
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
        return Scored(cq, q, support, ends, border, valleyish, nb, rimColour, total, rim)
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

    private fun contourQuads(image: Mat, mode: Int, out: MutableList<List<Pt>>) {
        val found = ArrayList<MatOfPoint>(); val hierarchy = Mat()
        try {
            Imgproc.findContours(image, found, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
            found.map { it to abs(Imgproc.contourArea(it)) }.filter { it.second >= w * h * .045 }.sortedByDescending { it.second }.take(25).forEach { (contour, _) ->
                val indices = MatOfInt(); val hull = MatOfPoint2f(); val approximation = MatOfPoint2f()
                try {
                    Imgproc.convexHull(contour, indices)
                    val points = contour.toArray(); val hullPoints = indices.toArray().map { points[it] }
                    hull.fromList(hullPoints)
                    val perimeter = Imgproc.arcLength(hull, true)
                    for (epsilon in doubleArrayOf(.015, .03, .05, .08)) {
                        Imgproc.approxPolyDP(hull, approximation, perimeter * epsilon, true)
                        if (approximation.total() == 4L) out += order(approximation.toList().map { Pt(it.x, it.y) })
                    }
                    // The hull's four extreme directions survive curved or bulging sides.
                    out += order(listOf(hullPoints.minBy { it.x + it.y }, hullPoints.maxBy { it.x - it.y }, hullPoints.maxBy { it.x + it.y }, hullPoints.minBy { it.x - it.y }).map { Pt(it.x, it.y) })
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

    private fun candidates(): List<List<Pt>> {
        val out = ArrayList<List<Pt>>()
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
        } finally {
            lab8.release(); channel.release(); edges.release(); canny.release(); valleys.release(); closed.release(); gray.release(); mask.release(); darkMat.release(); small.release()
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ refinement

    /** Each side's two ends move along its normal to where the evidence is best; the corners follow. */
    private fun refine(q: List<Pt>): List<Pt> {
        val lines = (0..3).map { arrayOf(q[it], q[(it + 1) % 4]) }.toMutableList()
        val step = max(1.5, s * .006)
        repeat(2) {
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
                var best: Triple<Double, Array<Pt>, Pair<Int, Int>>? = null
                for (da in -4..4 step 2) for (db in -4..4 step 2) trial(da, db)?.let { if (best == null || it.first > best!!.first) best = Triple(it.first, it.second, da to db) }
                val coarse = best ?: continue
                val (ba, bb) = coarse.third
                for (da in ba - 1..ba + 1) for (db in bb - 1..bb + 1) {
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
    private fun bestCut(q: List<Pt>, j: Int, from: Double, to: Double, maxTurn: Double, spine: Boolean, deepest: Double = 1.0): Cut? {
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
                val score = ev + .35 * (ca + cb)
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
                if (r.valleyish[j] && dist(q[j], q[(j + 1) % 4]) >= dist(q[(j + 1) % 4], q[(j + 2) % 4])) continue
                val cut = bestCut(q, j, .66, .97, 12.0, false, if (r.valleyish[j]) .88 else 1.0) ?: continue
                val sm = cut.samples
                val ok = (0 until sm.m).filter { sm.outsideOk[it] && sm.insideOk[it] }
                if (ok.size < 6) continue
                val p0 = q[j]; val p1 = q[(j + 1) % 4]
                val m0 = lerp(cut.a, p0, .5); val m1 = lerp(cut.b, p1, .5)
                val stripSamples = FloatArray(16 * 3)
                for (k in 0 until 16) { val p = lerp(m0, m1, .1 + .8 * k / 15); colour(stripSamples, k, p.x, p.y) }
                val strip = medianColour(stripSamples, { true }, 16) ?: continue
                val inner = medianColour(sm.deep, { it in ok }, sm.m) ?: continue
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
        val raw = candidates()
        lap("candidates")
        // A quick look (fewer samples, no colours) keeps the most promising candidates for the full measurement.
        val quick = ArrayList<Pair<List<Pt>, Double>>()
        for (q in raw) {
            if (!plausible(q)) continue
            if (!seen.add(q.flatMap { listOf((it.x / 6).roundToInt(), (it.y / 6).roundToInt()) })) continue
            quick += q to glance(q)
        }
        quick.sortedByDescending { it.second }.take(120).forEach { (q, _) -> score(q)?.let { scored += it } }
        lap("score")
        // Each of the best candidates is snapped to the strongest edges near its sides.
        val refined = scored.sortedByDescending { it.score }.take(12).mapNotNull { score(refine(it.full)) }
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
