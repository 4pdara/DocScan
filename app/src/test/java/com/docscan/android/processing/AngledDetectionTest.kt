package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class AngledDetectionTest {
    private val far = CropQuad(CropPoint(.34f, .32f), CropPoint(.60f, .34f), CropPoint(.62f, .62f), CropPoint(.35f, .64f))
    private val angled = CropQuad(CropPoint(.42f, .20f), CropPoint(.56f, .23f), CropPoint(.88f, .86f), CropPoint(.12f, .77f))
    private fun inside(point: CropPoint, quad: CropQuad) = quad.points.indices.all { i ->
        val a = quad.points[i]; val b = quad.points[(i + 1) % 4]
        (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x) >= 0
    }
    private fun scene(quad: CropQuad, paper: Int = 240, background: Int = 45): GrayImage {
        val w = 480; val h = 360; val noise = Random(31)
        return GrayImage(w, h, IntArray(w * h) { i ->
            val point = CropPoint((i % w).toFloat() / (w - 1), (i / w).toFloat() / (h - 1))
            ((if (inside(point, quad)) paper else background) + noise.nextInt(5) - 2).coerceIn(0, 255)
        })
    }
    @Test fun findsSmallDistantPageWithoutLoweringNormalModeThresholds() {
        val image = scene(far)
        assertNull(DocumentDetector.detect(image))
        val found = requireNotNull(DocumentDetector.detect(image, DetectionMode.ANGLED_DISTANCE))
        assertTrue(found.quad.maxDistance(far) < .025f); assertTrue(found.confidence >= .45f)
    }
    @Test fun rectifiesStrongPerspectiveCandidateWithConvergingSides() {
        val found = requireNotNull(DocumentDetector.detect(scene(angled), DetectionMode.ANGLED_DISTANCE))
        assertTrue(found.quad.maxDistance(angled) < .03f)
    }
    @Test fun findsDarkAngledAndDistantPagesThroughEdges() {
        for (quad in listOf(far, angled)) {
            val found = requireNotNull(DocumentDetector.detect(scene(quad, 65, 210), DetectionMode.ANGLED_DISTANCE)) { "Missing dark page $quad" }
            assertTrue("${found.quad}", found.quad.maxDistance(quad) < .04f)
        }
    }
    @Test fun noiseGradientCirclesAndIncompletePagesDoNotTriggerTheWiderMode() {
        val w = 480; val h = 360; val random = Random(4)
        val noise = GrayImage(w, h, IntArray(w * h) { 170 + random.nextInt(17) - 8 })
        val gradient = GrayImage(w, h, IntArray(w * h) { 90 + (it % w) / 4 })
        val circle = GrayImage(w, h, IntArray(w * h) { i -> if (hypot((i % w - w / 2).toDouble(), (i / w - h / 2).toDouble()) < 85) 240 else 40 })
        val clipped = scene(CropQuad(CropPoint(0f, .1f), CropPoint(.6f, .1f), CropPoint(.6f, .9f), CropPoint(0f, .9f)))
        for (image in listOf(noise, gradient, circle, clipped)) assertNull(DocumentDetector.detect(image, DetectionMode.ANGLED_DISTANCE))
    }
    @Test fun refinedBoundaryFollowsPageInsideBookCover() {
        val cover = CropQuad(CropPoint(.12f, .08f), CropPoint(.88f, .12f), CropPoint(.82f, .93f), CropPoint(.18f, .90f))
        val page = CropQuad(CropPoint(.17f, .13f), CropPoint(.83f, .17f), CropPoint(.77f, .88f), CropPoint(.23f, .85f))
        val w = 480; val h = 360
        val image = GrayImage(w, h, IntArray(w * h) { i ->
            val p = CropPoint((i % w).toFloat() / (w - 1), (i / w).toFloat() / (h - 1))
            when { inside(p, page) -> 245; inside(p, cover) -> 70; else -> 40 }
        })
        val found = requireNotNull(DocumentDetector.detect(image))
        assertTrue("Detected cover instead of page: ${found.quad}", found.quad.maxDistance(page) < .025f)
    }
    @Test fun smallPageStillRequiresStabilityAndCapturesOnlyOnce() {
        val image = scene(far)
        val page = requireNotNull(DocumentDetector.detect(image, DetectionMode.ANGLED_DISTANCE)).quad
        val fingerprint = PageFingerprint.from(image, page)
        val gate = AutoCaptureGate(); var count = 0
        for (time in 0L..1500L step 100) {
            val result = gate.update(page, time, fingerprint = fingerprint)
            if (time < 450) assertFalse(result.capture)
            if (result.capture) count++
        }
        assertEquals(1, count)
    }
    @Test fun edgeHandleMovesBothCornersPerpendicularlyAndKeepsOppositeSide() {
        val rectangle = CropQuad(CropPoint(.1f, .1f), CropPoint(.9f, .1f), CropPoint(.9f, .9f), CropPoint(.1f, .9f))
        val moved = requireNotNull(rectangle.moveEdgeNormal(1, -.1f, .3f, 1.5f))
        assertEquals(.8f, moved.topRight.x, .0001f); assertEquals(.8f, moved.bottomRight.x, .0001f)
        assertEquals(rectangle.topRight.y, moved.topRight.y, .0001f)
        assertEquals(rectangle.bottomRight.y, moved.bottomRight.y, .0001f)
        assertEquals(rectangle.topLeft, moved.topLeft); assertEquals(rectangle.bottomLeft, moved.bottomLeft)
        val restored = requireNotNull(moved.moveEdgeNormal(1, .1f, -.3f, 1.5f))
        assertTrue(restored.maxDistance(rectangle) < .0001f)
    }
    @Test fun perspectiveSideHandleDoesNotSlideAlongTheSideOrCrossTheOppositeSide() {
        val moved = requireNotNull(angled.moveEdgeNormal(1, -.025f, .015f, 1.5f))
        val ex = (angled.bottomRight.x - angled.topRight.x) * 1.5f
        val ey = angled.bottomRight.y - angled.topRight.y
        val mx = (moved.topRight.x - angled.topRight.x) * 1.5f
        val my = moved.topRight.y - angled.topRight.y
        assertEquals(0f, ex * mx + ey * my, .0001f)
        assertEquals(angled.topLeft, moved.topLeft); assertEquals(angled.bottomLeft, moved.bottomLeft)
        assertNull(angled.moveEdgeNormal(1, -1f, 0f, 1.5f))
    }
    @Test fun outwardEdgeDragStopsAtImageBoundsWithoutSlidingAlongTheEdge() {
        val moved = requireNotNull(angled.moveEdgeNormal(1, 1f, .1f, 1.5f))
        assertTrue(moved.isValid())
        assertTrue(moved.topRight.x >= .999f || moved.bottomRight.x >= .999f || moved.bottomRight.y >= .999f)
        val ex = (angled.bottomRight.x - angled.topRight.x) * 1.5f
        val ey = angled.bottomRight.y - angled.topRight.y
        val mx = (moved.topRight.x - angled.topRight.x) * 1.5f
        val my = moved.topRight.y - angled.topRight.y
        assertEquals(0f, ex * mx + ey * my, .0001f)
    }
}
