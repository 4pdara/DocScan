package com.docscan.android.processing

import kotlin.math.*

data class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
}

data class DocumentDetection(val quad: CropQuad, val confidence: Float)

/** Bounded, offline detector for a light page against a contrasting surface.
 * No confident quadrilateral means no automatic crop/capture; never invent edges.
 */
object DocumentDetector {
    fun detect(image: GrayImage): DocumentDetection? {
        val w = image.width; val h = image.height; val pixels = image.pixels
        if (w < 32 || h < 32 || w * h > 1_000_000) return null
        val histogram = IntArray(256)
        pixels.forEach { histogram[it.coerceIn(0, 255)]++ }
        val total = pixels.size
        val sum = histogram.indices.sumOf { it.toDouble() * histogram[it] }
        var background = 0; var sumBackground = 0.0; var bestVariance = 0.0; var threshold = 128
        for (i in 0..254) {
            background += histogram[i]; sumBackground += i.toDouble() * histogram[i]
            if (background == 0) continue
            val foreground = total - background
            if (foreground == 0) break
            val difference = sumBackground / background - (sum - sumBackground) / foreground
            val variance = background.toDouble() * foreground * difference * difference
            if (variance > bestVariance) { bestVariance = variance; threshold = i }
        }
        if (bestVariance / total / total < 90) return null
        var best: DocumentDetection? = null
        for (level in listOf(threshold + 1, threshold + 20, threshold - 16).map { it.coerceIn(20, 235) }.distinct()) {
            val visited = BooleanArray(total)
            val queue = IntArray(total)
            for (start in pixels.indices) {
                if (visited[start] || pixels[start] < level) continue
                var head = 0; var tail = 1
                queue[0] = start; visited[start] = true
                var touchesBorder = false
                val boundary = ArrayList<CropPoint>()
                while (head < tail) {
                    val index = queue[head++]; val x = index % w; val y = index / w
                    var isBoundary = false
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesBorder = true
                    for (direction in 0..3) {
                        val nx = x + if (direction == 0) -1 else if (direction == 1) 1 else 0
                        val ny = y + if (direction == 2) -1 else if (direction == 3) 1 else 0
                        if (nx !in 0 until w || ny !in 0 until h) { isBoundary = true; continue }
                        val next = ny * w + nx
                        if (pixels[next] < level) { isBoundary = true; continue }
                        if (!visited[next]) { visited[next] = true; queue[tail++] = next }
                    }
                    if (isBoundary) boundary.add(CropPoint(x.toFloat(), y.toFloat()))
                }
                if (touchesBorder || tail < total * .16f || tail > total * .94f) continue
                val hull = convexHull(boundary)
                if (hull.size < 4) continue
                val reduced = hull.toMutableList()
                while (reduced.size > 4) {
                    val remove = reduced.indices.minBy { i ->
                        abs(cross(reduced[(i + reduced.size - 1) % reduced.size], reduced[i], reduced[(i + 1) % reduced.size]))
                    }
                    reduced.removeAt(remove)
                }
                val polygonArea = polygonArea(reduced)
                val hullArea = polygonArea(hull)
                if (polygonArea < total * .18f || polygonArea > total * .92f || polygonArea / hullArea < .94f) continue
                if (tail / polygonArea < .70f) continue
                val startCorner = reduced.indices.minBy { reduced[it].x + reduced[it].y }
                val corners = List(4) { reduced[(it + startCorner) % 4] }
                val sides = corners.indices.map { corners[it].distance(corners[(it + 1) % 4]) }
                if (sides.min() < min(w, h) * .16f || sides.max() / sides.min() > 4.5f) continue
                val normalized = CropQuad.from(corners.map { CropPoint(it.x / (w - 1), it.y / (h - 1)) })
                if (!normalized.isValid()) continue
                val contrast = edgeContrast(image, corners)
                if (contrast < 15f) continue
                val confidence = (polygonArea / total * (polygonArea / hullArea) * min(contrast / 45f, 1f)).coerceIn(0f, 1f)
                // Include the outer edge rather than cutting into letters close to the margin.
                val centerX = corners.sumOf { it.x.toDouble() }.toFloat() / 4
                val centerY = corners.sumOf { it.y.toDouble() }.toFloat() / 4
                val expanded = CropQuad.from(corners.map {
                    val dx = it.x - centerX; val dy = it.y - centerY; val length = hypot(dx, dy)
                    CropPoint(((it.x + dx / length * 1.5f) / (w - 1)).coerceIn(0f, 1f),
                        ((it.y + dy / length * 1.5f) / (h - 1)).coerceIn(0f, 1f))
                })
                if (expanded.isValid() && (best == null || confidence > best.confidence)) best = DocumentDetection(expanded, confidence)
            }
        }
        return best
    }

    private fun edgeContrast(image: GrayImage, corners: List<CropPoint>): Float {
        var sum = 0f; var count = 0
        for (i in 0..3) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            val dx = b.x - a.x; val dy = b.y - a.y; val length = hypot(dx, dy)
            val nx = -dy / length * 3f; val ny = dx / length * 3f
            for (sample in 1..9) {
                val t = sample / 10f; val x = a.x + dx * t; val y = a.y + dy * t
                fun value(px: Float, py: Float): Int = image.pixels[py.roundToInt().coerceIn(0, image.height - 1) * image.width + px.roundToInt().coerceIn(0, image.width - 1)]
                sum += value(x + nx, y + ny) - value(x - nx, y - ny); count++
            }
        }
        return sum / count
    }

    private fun cross(a: CropPoint, b: CropPoint, c: CropPoint) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun polygonArea(p: List<CropPoint>) = abs(p.indices.sumOf { i ->
        val a = p[i]; val b = p[(i + 1) % p.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    private fun convexHull(points: List<CropPoint>): List<CropPoint> {
        val sorted = points.distinct().sortedWith(compareBy<CropPoint> { it.x }.thenBy { it.y })
        if (sorted.size < 4) return sorted
        val result = ArrayList<CropPoint>()
        for (p in sorted) {
            while (result.size >= 2 && cross(result[result.size - 2], result.last(), p) <= 0) result.removeAt(result.lastIndex)
            result.add(p)
        }
        val lower = result.size
        for (p in sorted.asReversed().drop(1)) {
            while (result.size > lower && cross(result[result.size - 2], result.last(), p) <= 0) result.removeAt(result.lastIndex)
            result.add(p)
        }
        result.removeAt(result.lastIndex)
        return result
    }
}
