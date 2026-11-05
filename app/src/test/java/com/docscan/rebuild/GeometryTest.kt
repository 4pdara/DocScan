package com.docscan.rebuild

import com.docscan.rebuild.model.*
import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun rejectsCrossedAndDegenerateCrops() {
        assertTrue(Frame.FULL.valid())
        assertFalse(Frame(listOf(Vertex(0f, 0f), Vertex(1f, 1f), Vertex(1f, 0f), Vertex(0f, 1f))).valid())
        assertFalse(Frame(listOf(Vertex(Float.NaN, 0f), Vertex(1f, 0f), Vertex(1f, 1f), Vertex(0f, 1f))).valid())
        assertFalse(Frame(listOf(Vertex(.1f, .1f), Vertex(.11f, .1f), Vertex(.11f, .11f), Vertex(.1f, .11f))).valid())
    }
    @Test fun rotationsPreserveCornerOrderAndArea() {
        val frame = Frame(listOf(Vertex(.1f, .2f), Vertex(.8f, .1f), Vertex(.9f, .8f), Vertex(.2f, .9f)))
        val turned = frame.turned(90)
        assertTrue(turned.valid()); assertEquals(frame.area(), turned.area(), .00001f)
        assertEquals(.1f, turned.points[0].x, .00001f); assertEquals(.2f, turned.points[0].y, .00001f)
        assertEquals(frame, frame.turned(360))
    }
    @Test fun captureRequiresStabilityAndRejectsDuplicate() {
        val gate = CaptureStability(); val content = List(64) { if (it % 2 == 0) 70f else 180f }
        assertFalse(gate.sample(Frame.FULL, content, 0).capture)
        assertFalse(gate.sample(Frame.FULL, content, 600).capture)
        assertTrue(gate.sample(Frame.FULL, content, 800).capture)
        assertFalse(gate.sample(Frame.FULL, content, 3000).capture)
        gate.sample(null, emptyList(), 3100); gate.sample(null, emptyList(), 3600)
        assertFalse(gate.sample(Frame.FULL, content, 3700).capture)
        assertTrue(gate.sample(Frame.FULL, content, 4500).capture)
    }
    @Test fun exposureChangeDoesNotUnlockButNewContentDoes() {
        val original = List(64) { if (it < 32) 100f else 170f }
        assertFalse(CaptureStability.contentChanged(original, original.map { it + 15 }))
        assertTrue(CaptureStability.contentChanged(original, original.reversed()))
    }
    @Test fun motionAndFailureRestartStability() {
        val gate = CaptureStability(); val content = List(64) { 100f }
        val frame = Frame(listOf(Vertex(.1f, .1f), Vertex(.8f, .1f), Vertex(.8f, .8f), Vertex(.1f, .8f)))
        gate.sample(frame, content, 0)
        val moved = Frame(frame.points.map { Vertex(it.x + .05f, it.y) })
        assertFalse(gate.sample(moved, content, 800).capture)
        assertTrue(gate.sample(moved, content, 1600).capture)
        gate.failed()
        assertFalse(gate.sample(moved, content, 1700).capture)
    }
}
