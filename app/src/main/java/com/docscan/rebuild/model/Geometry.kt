package com.docscan.rebuild.model

import kotlin.math.*

data class Vertex(val x: Float, val y: Float) {
    fun distance(other: Vertex) = hypot(x - other.x, y - other.y)
}

/** Clockwise in image coordinates: top-left, top-right, bottom-right, bottom-left. */
data class Frame(val points: List<Vertex>) {
    fun valid(): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val crosses = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return crosses.all { it > 0.0001f } && area() > 0.025f && points.indices.all { points[it].distance(points[(it + 1) % 4]) > .03f }
    }
    fun area(): Float = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]; (a.x * b.y - a.y * b.x).toDouble()
    }.toFloat()) / 2
    fun motion(other: Frame) = points.zip(other.points).maxOf { (a, b) -> a.distance(b) }
    fun turned(degrees: Int): Frame {
        var vertices = points
        repeat(((degrees % 360 + 360) % 360) / 90) {
            val rotated = vertices.map { Vertex(1f - it.y, it.x) }
            vertices = listOf(rotated[3], rotated[0], rotated[1], rotated[2])
        }
        return Frame(vertices)
    }
    companion object {
        val FULL = Frame(listOf(Vertex(0f, 0f), Vertex(1f, 0f), Vertex(1f, 1f), Vertex(0f, 1f)))
    }
}

enum class Treatment(val requiresReferenceEngine: Boolean = false) {
    ORIGINAL, COLOR, GRAY, INK, PAPER,
    NATIVE_MAGIC_COLOR(true), NATIVE_NO_SHADOW(true), NATIVE_INVERT(true), NATIVE_PRO_LOCAL(true)
}
data class EditRecipe(val frame: Frame = Frame.FULL, val turns: Int = 0, val treatment: Treatment = Treatment.ORIGINAL, val brightness: Float = 0f, val contrast: Float = 1f, val outputAspect: Float? = null)

/** A capture is latched until the content changes or the paper leaves the frame. */
class CaptureStability {
    private var anchor: Frame? = null
    private var stableSince = 0L
    private var absentSince: Long? = null
    private var captured: List<Float>? = null
    data class Decision(val progress: Float, val capture: Boolean)
    @Synchronized fun sample(frame: Frame?, signature: List<Float>, now: Long): Decision {
        if (frame == null || !frame.valid()) {
            if (absentSince == null) absentSince = now
            if (now - (absentSince ?: now) >= 450) captured = null
            anchor = null
            return Decision(0f, false)
        }
        absentSince = null
        val previous = captured
        if (previous != null && !contentChanged(previous, signature)) return Decision(1f, false)
        val origin = anchor
        if (origin == null || origin.motion(frame) > .018f) {
            anchor = frame; stableSince = now
            return Decision(0f, false)
        }
        val progress = ((now - stableSince) / 750f).coerceIn(0f, 1f)
        if (progress < 1f) return Decision(progress, false)
        captured = signature.toList(); anchor = null
        return Decision(1f, true)
    }
    @Synchronized fun failed() { captured = null; anchor = null }
    @Synchronized fun markCaptured(signature: List<Float>) { captured = signature.toList(); anchor = null }
    @Synchronized fun unstable() { anchor = null }
    companion object {
        fun contentChanged(a: List<Float>, b: List<Float>): Boolean {
            if (a.size != b.size || a.isEmpty()) return true
            val meanA = a.average(); val meanB = b.average()
            return a.indices.count { abs((a[it] - meanA) - (b[it] - meanB)) > 12 } >= max(4, a.size / 5)
        }
    }
}

