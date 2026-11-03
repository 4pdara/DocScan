package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class FasterScanningTest {
    private val page = CropQuad(CropPoint(.15f, .12f), CropPoint(.82f, .16f), CropPoint(.85f, .88f), CropPoint(.12f, .90f))

    private fun scene(paper: Int, surface: Int, noise: Int = 2): GrayImage {
        val w = 288; val h = 216; val random = Random(21)
        return GrayImage(w, h, IntArray(w * h) { i ->
            val x = (i % w).toFloat() / (w - 1); val y = (i / w).toFloat() / (h - 1)
            val inside = page.points.indices.all { j ->
                val a = page.points[j]; val b = page.points[(j + 1) % 4]
                (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0
            }
            val ink = x in .26f.. .70f && y in .3f.. .7f && i / w % 21 < 2
            ((if (inside) if (ink) 65 else paper else surface) + (x * 10).toInt() + random.nextInt(noise * 2 + 1) - noise).coerceIn(0, 255)
        })
    }

    @Test fun detectsLightPaperOnLightSurfaceAndDarkPaperOnLightSurface() {
        for ((paper, surface) in listOf(230 to 205, 100 to 195, 220 to 45)) {
            val found = requireNotNull(DocumentDetector.detect(scene(paper, surface))) { "paper=$paper surface=$surface" }
            assertTrue("$paper/$surface ${found.quad}", found.quad.maxDistance(page) < .045f)
        }
    }

    @Test fun uniformNoiseAndLightingGradientDoNotBecomePageEdges() {
        val random = Random(3)
        val noise = GrayImage(288, 216, IntArray(288 * 216) { 180 + random.nextInt(17) - 8 })
        val gradient = GrayImage(288, 216, IntArray(288 * 216) { 130 + (it % 288) / 3 })
        assertNull(DocumentDetector.detect(noise))
        assertNull(DocumentDetector.detect(gradient))
    }

    @Test fun smallJitterAndOneMissDoNotRestartCaptureCountdown() {
        val gate = AutoCaptureGate()
        assertFalse(gate.update(page, 0).capture)
        val jitter = CropQuad.from(page.points.map { CropPoint(it.x + .022f, it.y) })
        assertFalse(gate.update(jitter, 100).capture)
        assertFalse(gate.update(null, 200).capture)
        assertFalse(gate.update(page, 300).capture)
        assertFalse(gate.update(jitter, 400).capture)
        assertFalse(gate.update(page, 500).capture)
        assertTrue(gate.update(jitter, 600).capture)
        repeat(20) { assertFalse(gate.update(page, 700L + it * 100).capture) }
    }

    @Test fun prolongedMissingDetectionRequiresFreshStability() {
        val gate = AutoCaptureGate()
        gate.update(page, 0); gate.update(page, 100)
        for (time in 200L..500L step 100) assertFalse(gate.update(null, time).capture)
        for (time in 600L..1000L step 100) assertFalse(gate.update(page, time).capture)
        assertTrue(gate.update(page, 1100).capture)
    }

    @Test fun exposureGainDoesNotCreateNewPageContent() {
        val first = GrayImage(120, 120, IntArray(14400) { if (it / 120 % 20 < 5) 45 else 180 })
        val exposure = first.copy(pixels = first.pixels.map { (it * 1.2 + 10).toInt() }.toIntArray())
        val a = PageFingerprint.from(first, CropQuad.FULL); val b = PageFingerprint.from(exposure, CropQuad.FULL)
        assertTrue(a.distance(b) < .1f)
        assertFalse(a.differsFrom(b))
    }
}
