package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import com.docscan.rebuild.model.Vertex
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Shared camera/still detector. All coordinates are normalized to the unrotated input.
 *
 * Candidates come from structure edges (print erased by morphology), Otsu regions, regions that differ
 * from the colours along the frame border, convex-hull extremes (curved sides) and long-line combinations.
 * Each side must be backed by an edge or a colour step; a side on the frame border counts only where the
 * document visibly continues past the frame. A distinct object resting inside the winner (a book on a
 * tray, a page within its cover rim) replaces it. Measured on owner photographs and CamScanner reference
 * screenshots; see docs/DETECTION_0.2.0_HE.md.
 */
object BoundaryDetector {
    private class Scored(val frame: Frame, val score: Double, val support: DoubleArray)

    fun detect(gray: Mat, rgba: Mat? = null): Frame? {
        val w = gray.cols(); val h = gray.rows(); val side = min(w, h).toDouble()
        val edges = Mat.zeros(gray.size(), CvType.CV_8UC1); val work = Mat(); val blurred = Mat(); val supported = Mat()
        val rgb = Mat(); val labMat = Mat(); val plainMat = Mat(); val hierarchy = Mat()
        val k = max(5, (side * .022).toInt() or 1)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
        val candidates = mutableListOf<Frame>()
        try {
            // Structure edges: closing erases dark print on paper, opening erases light print on covers.
            fun edgeChannel(channel: Mat) {
                for (op in intArrayOf(Imgproc.MORPH_CLOSE, Imgproc.MORPH_OPEN)) {
                    Imgproc.morphologyEx(channel, work, op, kernel)
                    Imgproc.GaussianBlur(work, blurred, Size(5.0, 5.0), 0.0)
                    for (high in doubleArrayOf(40.0, 100.0)) {
                        Imgproc.Canny(blurred, work, high * .4, high)
                        Core.bitwise_or(edges, work, edges)
                    }
                }
            }
            edgeChannel(gray)
            if (rgba != null) {
                val channel = Mat()
                try { for (i in 0..2) { Core.extractChannel(rgba, channel, i); edgeChannel(channel) } } finally { channel.release() }
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            } else Imgproc.cvtColor(gray, rgb, Imgproc.COLOR_GRAY2RGB)
            val small = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            try { Imgproc.dilate(edges, supported, small) } finally { small.release() }
            val support = ByteArray(w * h).also { supported.get(0, 0, it) }
            Imgproc.cvtColor(rgb, labMat, Imgproc.COLOR_RGB2Lab)
            Imgproc.GaussianBlur(labMat, labMat, Size(0.0, 0.0), 1.6)
            labMat.convertTo(labMat, CvType.CV_32FC3)
            val lab = FloatArray(w * h * 3).also { labMat.get(0, 0, it) }
            Imgproc.morphologyEx(rgb, plainMat, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.cvtColor(plainMat, plainMat, Imgproc.COLOR_RGB2Lab)
            Imgproc.GaussianBlur(plainMat, plainMat, Size(0.0, 0.0), 2.0)
            plainMat.convertTo(plainMat, CvType.CV_32FC3)
            val plain = FloatArray(w * h * 3).also { plainMat.get(0, 0, it) }
            fun distance(data: FloatArray, a: Int, b: Int): Double {
                val dl = data[a * 3] - data[b * 3]; val da = data[a * 3 + 1] - data[b * 3 + 1]; val db = data[a * 3 + 2] - data[b * 3 + 2]
                return sqrt((dl * dl + da * da + db * db).toDouble())
            }

            val margin = .06f
            fun ordered(raw: List<Point>) {
                if (raw.size != 4) return
                val cx = raw.map { it.x }.average(); val cy = raw.map { it.y }.average()
                val points = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
                val start = points.indices.minBy { points[it].x + points[it].y }
                val normalized = (0..3).map { val p = points[(start + it) % 4]; Vertex((p.x / (w - 1)).toFloat(), (p.y / (h - 1)).toFloat()) }
                // Corners may lie slightly beyond the frame when a cover fills the view; they are clamped to it.
                if (normalized.any { it.x !in -margin..1 + margin || it.y !in -margin..1 + margin }) return
                val frame = Frame(normalized.map { Vertex(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) })
                if (frame.valid() && frame.area() in .055f.. .985f) candidates += frame
            }
            fun fromContours(image: Mat, mode: Int) {
                val found = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.findContours(image, found, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
                    found.filter { abs(Imgproc.contourArea(it)) >= gray.total() * .045 }.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(30).forEach { contour ->
                        val hullIndices = MatOfInt(); val hull = MatOfPoint2f(); val approximation = MatOfPoint2f()
                        try {
                            Imgproc.convexHull(contour, hullIndices)
                            val points = contour.toArray(); val hullPoints = hullIndices.toArray().map { points[it] }
                            hull.fromList(hullPoints)
                            for (epsilon in doubleArrayOf(.012, .025, .045, .07, .1)) {
                                Imgproc.approxPolyDP(hull, approximation, Imgproc.arcLength(hull, true) * epsilon, true)
                                if (approximation.total() == 4L) ordered(approximation.toList())
                            }
                            // The hull's four extreme directions survive curved or bulging page sides.
                            ordered(listOf(hullPoints.minBy { it.x + it.y }, hullPoints.maxBy { it.x - it.y }, hullPoints.maxBy { it.x + it.y }, hullPoints.minBy { it.x - it.y }))
                        } finally { hullIndices.release(); hull.release(); approximation.release() }
                    }
                } finally { found.forEach { it.release() } }
            }
            for (diameter in doubleArrayOf(3.0, 9.0)) {
                val closing = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(diameter, diameter))
                try { Imgproc.morphologyEx(edges, work, Imgproc.MORPH_CLOSE, closing); fromContours(work, Imgproc.RETR_LIST) } finally { closing.release() }
            }
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            for (inverse in listOf(false, true)) {
                Imgproc.threshold(blurred, work, 0.0, 255.0, Imgproc.THRESH_OTSU or if (inverse) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY)
                fromContours(work, Imgproc.RETR_EXTERNAL)
            }
            val surface = backgroundRegions(lab, w, h, side, work, ::fromContours)
            lineCandidates(edges, w, h, side, ::ordered)

            // Side support: a structure edge or a colour step across the side. On the frame border, support
            // only where the strip inside matches the document's interior (the document continues past the frame).
            val patch = Mat()
            fun interior(frame: Frame): FloatArray {
                val from = MatOfPoint2f(*frame.points.map { Point(it.x * (w - 1.0), it.y * (h - 1.0)) }.toTypedArray())
                val to = MatOfPoint2f(Point(0.0, 0.0), Point(31.0, 0.0), Point(31.0, 31.0), Point(0.0, 31.0))
                val transform = Imgproc.getPerspectiveTransform(from, to)
                try {
                    Imgproc.warpPerspective(labMat, patch, transform, Size(32.0, 32.0))
                    val values = FloatArray(32 * 32 * 3).also { patch.get(0, 0, it) }
                    return FloatArray(3) { c -> (8 until 24).flatMap { y -> (8 until 24).map { x -> values[(y * 32 + x) * 3 + c] } }.sorted()[128] }
                } finally { from.release(); to.release(); transform.release() }
            }
            fun sideSupport(a: Vertex, b: Vertex, inside: FloatArray): Pair<Double, Double> {
                val ax = a.x * (w - 1.0); val ay = a.y * (h - 1.0); val bx = b.x * (w - 1.0); val by = b.y * (h - 1.0)
                val length = hypot(bx - ax, by - ay); val nx = (by - ay) / length; val ny = -(bx - ax) / length
                val off = max(3.0, side * .012); val deep = off * 3; val edgeZone = side * .02
                var hits = 0.0; var border = 0; var borderOk = 0
                for (s in 2..38) {
                    val t = s / 40.0; val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t
                    if (x < edgeZone || y < edgeZone || x > w - 1 - edgeZone || y > h - 1 - edgeZone) {
                        border++
                        val ix = (x - nx * deep).roundToInt().coerceIn(0, w - 1); val iy = (y - ny * deep).roundToInt().coerceIn(0, h - 1)
                        val i = (iy * w + ix) * 3
                        val dl = lab[i] - inside[0]; val da = lab[i + 1] - inside[1]; val db = lab[i + 2] - inside[2]
                        if (sqrt((dl * dl + da * da + db * db).toDouble()) < 22) borderOk++
                        continue
                    }
                    val xi = x.roundToInt(); val yi = y.roundToInt()
                    val edge = support[yi * w + xi].toInt() != 0
                    val ix = (x - nx * off).roundToInt(); val iy = (y - ny * off).roundToInt(); val ox = (x + nx * off).roundToInt(); val oy = (y + ny * off).roundToInt()
                    val step = ox in 0 until w && oy in 0 until h && ix in 0 until w && iy in 0 until h && distance(lab, iy * w + ix, oy * w + ox) > 14
                    if (edge || step) hits++
                }
                return (hits + borderOk) / 37.0 to border / 37.0
            }
            val valid = mutableListOf<Scored>()
            try {
                candidates.distinctBy { f -> f.points.map { (it.x * 100).roundToInt() to (it.y * 100).roundToInt() } }.forEach { frame ->
                    val inside = interior(frame)
                    val sides = frame.points.indices.map { i -> sideSupport(frame.points[i], frame.points[(i + 1) % 4], inside) }
                    val sup = DoubleArray(4) { sides[it].first }
                    // At most two sides may rest on the frame border: a whole-frame quad is not a detection.
                    if (sides.count { it.second > .6 } > 2 || sup.min() < .3 || sup.average() < .55) return@forEach
                    valid += Scored(frame, sqrt(frame.area().toDouble()) * .65 + sup.average() * .35, sup)
                }
            } finally { patch.release() }
            var best = valid.maxByOrNull { it.score } ?: return null

            // A separate object shows a clear colour step across its whole boundary in the print-free image.
            fun distinctObject(frame: Frame): Boolean {
                val fractions = frame.points.indices.map { i ->
                    val a = frame.points[i]; val b = frame.points[(i + 1) % 4]
                    val ax = a.x * (w - 1.0); val ay = a.y * (h - 1.0); val bx = b.x * (w - 1.0); val by = b.y * (h - 1.0)
                    val length = hypot(bx - ax, by - ay); val nx = (by - ay) / length; val ny = -(bx - ax) / length
                    val off = max(4.0, side * .02); var hits = 0; var n = 0
                    for (s in 2..38) {
                        val t = s / 40.0; val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t
                        val ix = (x - nx * off).roundToInt(); val iy = (y - ny * off).roundToInt(); val ox = (x + nx * off).roundToInt(); val oy = (y + ny * off).roundToInt()
                        if (ox !in 0 until w || oy !in 0 until h || ix !in 0 until w || iy !in 0 until h) continue
                        n++; if (distance(plain, iy * w + ix, oy * w + ox) > 20) hits++
                    }
                    if (n == 0) 0.0 else hits.toDouble() / n
                }
                return fractions.min() >= .5 && fractions.average() >= .7
            }
            // Only when the winner is the surface itself (a tray or desk whose colour appears along the frame
            // border) may an object inside it replace it; a label printed on a cover never does.
            val sw = 120; val sh = 160
            val smallPlain = Mat(); val outerMask = Mat(); val innerMask = Mat(); val grow = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            try {
                Imgproc.resize(plainMat, smallPlain, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val plainSmall = FloatArray(sw * sh * 3).also { smallPlain.get(0, 0, it) }
                fun mask(frame: Frame, target: Mat) {
                    target.create(sh, sw, CvType.CV_8UC1); target.setTo(Scalar(0.0))
                    val polygon = MatOfPoint(*frame.points.map { Point(it.x * (sw - 1.0), it.y * (sh - 1.0)) }.toTypedArray())
                    try { Imgproc.fillPoly(target, listOf(polygon), Scalar(1.0)) } finally { polygon.release() }
                }
                fun gap(x: FloatArray, y: FloatArray) = sqrt((0..2).sumOf { c -> ((x[c] - y[c]) * (x[c] - y[c])).toDouble() })
                // Print-free colour at the middle of a quad: the material the quad is mostly made of.
                fun centreColour(frame: Frame): FloatArray {
                    val from = MatOfPoint2f(*frame.points.map { Point(it.x * (w - 1.0), it.y * (h - 1.0)) }.toTypedArray())
                    val to = MatOfPoint2f(Point(0.0, 0.0), Point(31.0, 0.0), Point(31.0, 31.0), Point(0.0, 31.0))
                    val transform = Imgproc.getPerspectiveTransform(from, to); val centre = Mat()
                    try {
                        Imgproc.warpPerspective(plainMat, centre, transform, Size(32.0, 32.0))
                        val values = FloatArray(32 * 32 * 3).also { centre.get(0, 0, it) }
                        return FloatArray(3) { c -> (8 until 24).flatMap { y -> (8 until 24).map { x -> values[(y * 32 + x) * 3 + c] } }.sorted()[128] }
                    } finally { from.release(); to.release(); transform.release(); centre.release() }
                }
                fun restsOnSurface(outer: Frame, inner: Frame): Boolean {
                    mask(outer, outerMask); mask(inner, innerMask); Imgproc.dilate(innerMask, innerMask, grow)
                    val o = ByteArray(sw * sh).also { outerMask.get(0, 0, it) }; val i = ByteArray(sw * sh).also { innerMask.get(0, 0, it) }
                    val ring = (0 until sw * sh).filter { o[it].toInt() != 0 && i[it].toInt() == 0 }
                    if (ring.size < 20) return false
                    val colour = FloatArray(3) { c -> ring.map { plainSmall[it * 3 + c] }.sorted()[ring.size / 2] }
                    // The band around the object is the surface seen at the frame border (desk, tray, table)...
                    if (surface.none { gap(colour, it) <= 35 }) return false
                    // ...and not the winner's own material: a cover touching the frame border puts its colour among
                    // the border colours, but a panel on that cover is surrounded by the cover itself.
                    return gap(colour, centreColour(outer)) >= 25
                }
                repeat(3) {
                    val outer = MatOfPoint2f(*best.frame.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
                    try {
                        val nested = valid.filter { candidate ->
                            candidate !== best && candidate.frame.area() in best.frame.area() * .2f..best.frame.area() * .9f &&
                                candidate.support.min() >= .6 && candidate.support.average() >= .8 &&
                                candidate.frame.points.all { Imgproc.pointPolygonTest(outer, Point(it.x.toDouble(), it.y.toDouble()), true) >= -.01 } &&
                                // The surface shows on at least one side: a page whose bottom band of table was taken
                                // into a larger quad (owner's 0.2.1 photo of an open book on a wooden table).
                                candidate.frame.points.indices.count { i ->
                                    val a = candidate.frame.points[i]; val b = candidate.frame.points[(i + 1) % 4]
                                    Imgproc.pointPolygonTest(outer, Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0), true) >= .02
                                } >= 1 &&
                                restsOnSurface(best.frame, candidate.frame) && distinctObject(candidate.frame)
                        }
                        best = nested.maxByOrNull { it.score } ?: return best.frame
                    } finally { outer.release() }
                }
            } finally { smallPlain.release(); outerMask.release(); innerMask.release(); grow.release() }
            return best.frame
        } finally {
            edges.release(); work.release(); blurred.release(); supported.release(); rgb.release(); labMat.release(); plainMat.release(); hierarchy.release(); kernel.release()
        }
    }

    /** Regions that differ from the colours seen along the frame border (the table, tray or wall around the document). */
    private fun backgroundRegions(lab: FloatArray, w: Int, h: Int, side: Double, work: Mat, fromContours: (Mat, Int) -> Unit): List<FloatArray> {
        val band = max(3, (side * .03).toInt())
        val samples = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) if (y < band || y >= h - band || x < band || x >= w - band) samples += y * w + x
        val data = Mat(samples.size, 3, CvType.CV_32F); val labels = Mat(); val centers = Mat()
        try {
            data.put(0, 0, FloatArray(samples.size * 3) { lab[samples[it / 3] * 3 + it % 3] })
            val clusters = 3
            Core.kmeans(data, clusters, labels, TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 20, 1.0), 2, Core.KMEANS_PP_CENTERS, centers)
            val assigned = IntArray(samples.size).also { labels.get(0, 0, it) }
            val shares = DoubleArray(clusters).also { s -> assigned.forEach { s[it]++ } }.map { it / samples.size }
            val centre = FloatArray(clusters * 3).also { centers.get(0, 0, it) }
            val distances = ByteArray(w * h)
            for (keep in doubleArrayOf(.5, .25)) {
                val background = (0 until clusters).filter { shares[it] >= keep }.ifEmpty { listOf(shares.indices.maxBy { shares[it] }) }
                for (p in 0 until w * h) {
                    var nearest = Double.MAX_VALUE
                    for (c in background) {
                        val dl = lab[p * 3] - centre[c * 3]; val da = lab[p * 3 + 1] - centre[c * 3 + 1]; val db = lab[p * 3 + 2] - centre[c * 3 + 2]
                        nearest = min(nearest, sqrt((dl * dl + da * da + db * db).toDouble()))
                    }
                    distances[p] = (nearest * 2).coerceAtMost(255.0).toInt().toByte()
                }
                val distance = Mat(h, w, CvType.CV_8UC1)
                val close = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)); val open = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
                try {
                    distance.put(0, 0, distances)
                    val threshold = Imgproc.threshold(distance, work, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    if (threshold < 30) Imgproc.threshold(distance, work, 30.0, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.morphologyEx(work, work, Imgproc.MORPH_CLOSE, close)
                    Imgproc.morphologyEx(work, work, Imgproc.MORPH_OPEN, open)
                    fromContours(work, Imgproc.RETR_EXTERNAL)
                } finally { distance.release(); close.release(); open.release() }
            }
            return (0 until clusters).filter { shares[it] >= .1 }.map { c -> FloatArray(3) { centre[c * 3 + it] } }
        } finally { data.release(); labels.release(); centers.release() }
    }

    /** Four long boundaries even when glare, rounded corners, a spine or a curved page breaks a contour. */
    private fun lineCandidates(edges: Mat, w: Int, h: Int, side: Double, ordered: (List<Point>) -> Unit) {
        data class Line(val a: Point, val b: Point) { val length get() = hypot(b.x - a.x, b.y - a.y); val x get() = (a.x + b.x) / 2; val y get() = (a.y + b.y) / 2 }
        val lines = Mat()
        try {
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, max(25, (side * .07).toInt()), side * .15, side * .08)
            val segments = (0 until lines.rows()).mapNotNull { i -> lines.get(i, 0)?.takeIf { it.size >= 4 }?.let { Line(Point(it[0], it[1]), Point(it[2], it[3])) } }
                .sortedByDescending { it.length }.take(120)
            val horizontal = segments.filter { abs(it.b.x - it.a.x) > abs(it.b.y - it.a.y) * 1.1 }
            val vertical = segments.filter { abs(it.b.y - it.a.y) > abs(it.b.x - it.a.x) * 1.1 }
            // Distinct positions only: parallel print lines would otherwise crowd out the page edges.
            fun distinct(group: List<Line>, key: (Line) -> Double): List<Line> {
                val kept = mutableListOf<Line>()
                for (line in group) { if (kept.all { abs(key(line) - key(it)) > side * .04 }) kept += line; if (kept.size == 6) break }
                return kept
            }
            val tops = distinct(horizontal.filter { it.y < h * .55 }) { it.y }; val bottoms = distinct(horizontal.filter { it.y > h * .45 }) { it.y }
            val lefts = distinct(vertical.filter { it.x < w * .55 }) { it.x }; val rights = distinct(vertical.filter { it.x > w * .45 }) { it.x }
            fun intersection(a: Line, b: Line): Point? {
                val ax = a.b.x - a.a.x; val ay = a.b.y - a.a.y; val bx = b.b.x - b.a.x; val by = b.b.y - b.a.y; val cross = ax * by - ay * bx
                if (abs(cross) < 1e-6) return null
                val t = ((b.a.x - a.a.x) * by - (b.a.y - a.a.y) * bx) / cross
                return Point(a.a.x + t * ax, a.a.y + t * ay)
            }
            for (top in tops) for (bottom in bottoms) for (left in lefts) for (right in rights) {
                if (bottom.y - top.y < side * .2 || right.x - left.x < side * .2) continue
                val corners = listOf(intersection(top, left), intersection(top, right), intersection(bottom, right), intersection(bottom, left))
                if (corners.all { it != null }) ordered(corners.filterNotNull())
            }
        } finally { lines.release() }
    }
}
