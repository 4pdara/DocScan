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
