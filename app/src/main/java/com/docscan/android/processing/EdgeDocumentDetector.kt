package com.docscan.android.processing

import kotlin.math.*

/** Bounded gradient/Hough pass. Unlike brightness segmentation, either side of an edge may be darker. */
internal object EdgeDocumentDetector {
    private const val ANGLES = 60
    private val cosines = FloatArray(ANGLES) { cos(it * PI / ANGLES).toFloat() }
    private val sines = FloatArray(ANGLES) { sin(it * PI / ANGLES).toFloat() }
    private data class Line(val angle: Int, val rho: Float, val votes: Int)
    private data class PairOfLines(val a: Line, val b: Line)

    fun detect(image: GrayImage): DocumentDetection? {
        val w = image.width; val h = image.height
        val diagonal = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val bins = diagonal * 2 + 1
        val votes = IntArray(ANGLES * bins)
        val magnitude = IntArray(w * h)
        val histogram = IntArray(511)
        // A sparse, orientation-guided vote avoids scanning every angle for every edge pixel.
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val gx = image.pixels[i + 1] - image.pixels[i - 1]
            val gy = image.pixels[i + w] - image.pixels[i - w]
            val strength = abs(gx) + abs(gy)
            magnitude[i] = strength
            histogram[strength]++
        }
        var count = 0; var median = 0
        val middle = (w - 4) * (h - 4) / 2
        while (median < histogram.lastIndex) { count += histogram[median]; if (count >= middle) break; median++ }
        val threshold = (median * 3 + 4).coerceIn(10, 96)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            if (magnitude[i] < threshold) continue
            val gx = image.pixels[i + 1] - image.pixels[i - 1]
            val gy = image.pixels[i + w] - image.pixels[i - w]
            val angle = ((atan2(gy.toDouble(), gx.toDouble()) * ANGLES / PI).roundToInt() + ANGLES) % ANGLES
            for (delta in -1..1) {
                val a = (angle + delta + ANGLES) % ANGLES
                val rho = (x * cosines[a] + y * sines[a]).roundToInt() + diagonal
                votes[a * bins + rho]++
            }
        }
        val peaks = ArrayList<Line>()
        val minimumVotes = max(12, min(w, h) / 7)
        for (a in 0 until ANGLES) for (r in 2 until bins - 2) {
            val v = votes[a * bins + r]
            if (v < minimumVotes || v < votes[a * bins + r - 1] || v <= votes[a * bins + r + 1]) continue
            peaks.add(Line(a, (r - diagonal).toFloat(), v))
        }
        val lines = ArrayList<Line>()
        for (candidate in peaks.sortedByDescending { it.votes }) {
            if (lines.none { angularDistance(it.angle, candidate.angle) <= 2 &&
                    abs(it.rho - if (abs(it.angle - candidate.angle) > ANGLES / 2) -candidate.rho else candidate.rho) < 7 }) {
                lines.add(candidate)
                if (lines.size == 16) break
            }
        }
        val pairs = ArrayList<PairOfLines>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            if (angularDistance(a.angle, b.angle) > 10) continue
            val alignedRho = if (abs(a.angle - b.angle) > ANGLES / 2) -b.rho else b.rho
            if (abs(a.rho - alignedRho) < min(w, h) * .25f) continue
            pairs.add(PairOfLines(a, b))
        }
        var best: DocumentDetection? = null
        var bestScore = 0f
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val a = pairs[i]; val b = pairs[j]
            if (listOf(a.a, a.b, b.a, b.b).distinct().size != 4) continue
            if (angularDistance(a.a.angle, b.a.angle) !in 15..30) continue
            val points = listOf(intersect(a.a, b.a), intersect(a.a, b.b), intersect(a.b, b.a), intersect(a.b, b.b))
            if (points.any { it == null }) continue
            val corners = points.filterNotNull()
            if (corners.any { it.x < 3 || it.y < 3 || it.x > w - 4 || it.y > h - 4 }) continue
            val cx = corners.sumOf { it.x.toDouble() } / 4
            val cy = corners.sumOf { it.y.toDouble() } / 4
            val ordered = corners.sortedBy { atan2(it.y - cy, it.x - cx) }
            val first = ordered.indices.minBy { ordered[it].x + ordered[it].y }
            val pixelCorners = List(4) { ordered[(it + first) % 4] }
            val quad = CropQuad.from(pixelCorners.map { CropPoint(it.x / (w - 1), it.y / (h - 1)) })
            if (!quad.isValid() || quad.area !in .15f.. .92f) continue
            val lengths = pixelCorners.indices.map { pixelCorners[it].distance(pixelCorners[(it + 1) % 4]) }
            if (lengths.min() < min(w, h) * .18f || lengths.max() / lengths.min() > 4.5f) continue
            val support = pixelCorners.indices.map { edgeSupport(magnitude, w, h, pixelCorners[it], pixelCorners[(it + 1) % 4], threshold) }
            // Each whole side needs evidence; a circle's four tangent lines must not become a page.
            if (support.min() < .60f || support.average() < .78) continue
            val confidence = support.average().toFloat()
            val score = quad.area * confidence
            if (score > bestScore) { bestScore = score; best = DocumentDetection(quad, confidence) }
        }
        return best
    }

    private fun angularDistance(a: Int, b: Int) = min(abs(a - b), ANGLES - abs(a - b))

    private fun intersect(a: Line, b: Line): CropPoint? {
        val ca = cosines[a.angle]; val sa = sines[a.angle]
        val cb = cosines[b.angle]; val sb = sines[b.angle]
        val determinant = ca * sb - sa * cb
        if (abs(determinant) < .3f) return null
        return CropPoint((a.rho * sb - sa * b.rho) / determinant, (ca * b.rho - a.rho * cb) / determinant)
    }

    private fun edgeSupport(magnitude: IntArray, w: Int, h: Int, a: CropPoint, b: CropPoint, threshold: Int): Float {
        var supported = 0
        for (sample in 1..24) {
            val t = sample / 25f
            val x = (a.x + (b.x - a.x) * t).roundToInt()
            val y = (a.y + (b.y - a.y) * t).roundToInt()
            var maximum = 0
            for (dy in -2..2) for (dx in -2..2) {
                val px = (x + dx).coerceIn(0, w - 1); val py = (y + dy).coerceIn(0, h - 1)
                maximum = max(maximum, magnitude[py * w + px])
            }
            if (maximum >= threshold) supported++
        }
        return supported / 24f
    }
}
