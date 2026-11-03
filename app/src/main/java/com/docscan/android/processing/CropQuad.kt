package com.docscan.android.processing

import kotlin.math.abs
import kotlin.math.hypot

data class CropPoint(val x: Float, val y: Float) {
    fun distance(other: CropPoint) = hypot(x - other.x, y - other.y)
}

/** Clockwise corners in the EXIF-oriented original, before user rotation. */
data class CropQuad(val topLeft: CropPoint, val topRight: CropPoint, val bottomRight: CropPoint, val bottomLeft: CropPoint) {
    val points get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    val area: Float get() = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % 4]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    fun isValid(): Boolean {
        val p = points
        if (p.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        if (area < .005f) return false
        return p.indices.all { i ->
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            a.distance(b) >= .015f && (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > .0001f
        }
    }

    fun move(index: Int, point: CropPoint): CropQuad? {
        val p = points.toMutableList()
        p[index] = CropPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
        return from(p).takeIf { it.isValid() }
    }

    fun maxDistance(other: CropQuad) = points.zip(other.points).maxOf { (a, b) -> a.distance(b) }
    fun rectangle(): CropQuad = CropQuad(CropPoint(points.minOf { it.x }, points.minOf { it.y }),
        CropPoint(points.maxOf { it.x }, points.minOf { it.y }),
        CropPoint(points.maxOf { it.x }, points.maxOf { it.y }),
        CropPoint(points.minOf { it.x }, points.maxOf { it.y }))

    fun moveRectangleCorner(index: Int, point: CropPoint): CropQuad? {
        require(index in 0..3)
        val opposite = points[(index + 2) % 4]
        val x = if (index == 0 || index == 3) point.x.coerceIn(0f, (opposite.x - .02f).coerceAtLeast(0f))
            else point.x.coerceIn((opposite.x + .02f).coerceAtMost(1f), 1f)
        val y = if (index < 2) point.y.coerceIn(0f, (opposite.y - .02f).coerceAtLeast(0f))
            else point.y.coerceIn((opposite.y + .02f).coerceAtMost(1f), 1f)
        fun between(point: CropPoint) = from(listOf(CropPoint(minOf(point.x, opposite.x), minOf(point.y, opposite.y)),
            CropPoint(maxOf(point.x, opposite.x), minOf(point.y, opposite.y)),
            CropPoint(maxOf(point.x, opposite.x), maxOf(point.y, opposite.y)),
            CropPoint(minOf(point.x, opposite.x), maxOf(point.y, opposite.y))))
        val target = CropPoint(x, y)
        val candidate = between(target)
        if (candidate.isValid()) return candidate
        // Stop at the valid area boundary when a finger crosses the opposite corner.
        var low = 0f; var high = 1f; var best = rectangle()
        val start = best.points[index]
        repeat(16) {
            val t = (low + high) / 2
            val limited = between(CropPoint(start.x + (target.x - start.x) * t, start.y + (target.y - start.y) * t))
            if (limited.isValid()) { low = t; best = limited } else high = t
        }
        return best.takeIf { it.isValid() }
    }

    fun moveEdge(index: Int, dx: Float, dy: Float): CropQuad? {
        require(index in 0..3)
        val a = points[index]; val b = points[(index + 1) % 4]
        val x = dx.coerceIn(-minOf(a.x, b.x), 1 - maxOf(a.x, b.x))
        val y = dy.coerceIn(-minOf(a.y, b.y), 1 - maxOf(a.y, b.y))
        return from(points.mapIndexed { i, p ->
            if (i == index || i == (index + 1) % 4) CropPoint(p.x + x, p.y + y) else p
        }).takeIf { it.isValid() }
    }
    fun encode() = points.flatMap { listOf(it.x, it.y) }.joinToString(",")

    companion object {
        val FULL = CropQuad(CropPoint(0f, 0f), CropPoint(1f, 0f), CropPoint(1f, 1f), CropPoint(0f, 1f))
        fun from(points: List<CropPoint>): CropQuad {
            require(points.size == 4)
            return CropQuad(points[0], points[1], points[2], points[3])
        }
        fun decode(value: String?): CropQuad? = runCatching {
            val p = requireNotNull(value).split(',').map { it.toFloat() }
            require(p.size == 8)
            from(p.chunked(2).map { CropPoint(it[0], it[1]) }).takeIf { it.isValid() }
        }.getOrNull()
    }
}
