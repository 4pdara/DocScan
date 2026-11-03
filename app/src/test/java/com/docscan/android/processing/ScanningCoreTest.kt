package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ScanningCoreTest {
    private val page = CropQuad(CropPoint(.18f, .12f), CropPoint(.79f, .17f), CropPoint(.86f, .86f), CropPoint(.13f, .91f))

    private fun fixture(quad: CropQuad = page, text: Boolean = false, shadow: Boolean = false): GrayImage {
        val w = 320; val h = 240; val random = Random(7)
        return GrayImage(w, h, IntArray(w * h) { index ->
            val x = (index % w).toFloat() / (w - 1); val y = (index / w).toFloat() / (h - 1)
            val p = quad.points
            val inside = p.indices.all { i ->
                val a = p[i]; val b = p[(i + 1) % 4]
                (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0
            }
            val ink = text && x in .25f.. .7f && y in .3f.. .7f && index / w % 17 < 2
            (if (inside) if (ink) 25 else 220 - if (shadow) (55 * x).toInt() else 0 else 45) + random.nextInt(7)
        })
    }

    @Test fun detectsPerspectivePageDespiteTextAndUnevenLight() {
        for (text in listOf(false, true)) for (shadow in listOf(false, true)) {
            val found = DocumentDetector.detect(fixture(text = text, shadow = shadow))
            assertNotNull("text=$text shadow=$shadow", found)
            assertTrue(requireNotNull(found).quad.maxDistance(page) < .035f)
        }
    }

    @Test fun detectsLandscapeAndRotatedPage() {
        val rotated = CropQuad(CropPoint(.30f, .10f), CropPoint(.86f, .38f), CropPoint(.68f, .89f), CropPoint(.13f, .61f))
        val found = requireNotNull(DocumentDetector.detect(fixture(rotated)))
        assertTrue(found.quad.maxDistance(rotated) < .035f)
    }

    @Test fun doesNotInventBordersOnBlankOrClippedFrames() {
        assertNull(DocumentDetector.detect(GrayImage(320, 240, IntArray(320 * 240) { 220 })))
        val clipped = CropQuad(CropPoint(0f, .1f), CropPoint(.85f, .1f), CropPoint(.85f, .9f), CropPoint(0f, .9f))
        assertNull(DocumentDetector.detect(fixture(clipped)))
    }

    @Test fun rejectsRoundBrightObjects() {
        val image = GrayImage(240, 240, IntArray(240 * 240) { i ->
            val x = i % 240 - 120; val y = i / 240 - 120
            if (x * x + y * y < 90 * 90) 230 else 40
        })
        assertNull(DocumentDetector.detect(image))
    }

    @Test fun cropEncodingRejectsCrossingAndCorruptCoordinates() {
        assertEquals(page, CropQuad.decode(page.encode()))
        assertNull(CropQuad.decode("NaN,0,1,0,1,1,0,1"))
        assertNull(CropQuad.decode("0,0,1,1,1,0,0,1"))
        assertNull(page.move(0, CropPoint(.95f, .95f)))
        assertTrue(CropQuad.FULL.isValid())
    }

    @Test fun capturesOnlyAfterContinuousStabilityAndWaitsForPageRemoval() {
        val gate = AutoCaptureGate(stableMillis = 600, removalMillis = 300)
        for (t in 0L..450L step 150) assertFalse(gate.update(page, t).capture)
        assertTrue(gate.update(page, 600).capture)
        for (t in 750L..3000L step 150) assertFalse(gate.update(page, t).capture)
        assertEquals(CaptureStatus.WAIT_FOR_NEXT, gate.update(null, 3150).status)
        gate.update(null, 3300)
        assertEquals(CaptureStatus.SEARCHING, gate.update(null, 3450).status)
        for (t in 3600L..4050L step 150) assertFalse(gate.update(page, t).capture)
        assertTrue(gate.update(page, 4200).capture)
    }

    @Test fun motionPauseAndLongFrameGapsNeverCountAsStability() {
        val gate = AutoCaptureGate(stableMillis = 600)
        gate.update(page, 0); gate.update(page, 300)
        assertFalse(gate.update(page, 1000).capture)
        gate.update(page, 1150, blocked = true)
        assertFalse(gate.update(page, 1300).capture)
        val moved = CropQuad.from(page.points.map { CropPoint(it.x + .04f, it.y) })
        assertFalse(gate.update(moved, 1600).capture)
        assertFalse(gate.update(moved, 1900).capture)
        assertTrue(gate.update(moved, 2200).capture)
    }

    @Test fun savingAndPausingCannotRearmSamePage() {
        val gate = AutoCaptureGate(300)
        gate.markCaptured()
        gate.update(null, 100, blocked = true)
        gate.update(null, 1000, blocked = true)
        assertEquals(CaptureStatus.WAIT_FOR_NEXT, gate.update(page, 1200).status)
        assertFalse(gate.update(page, 1500).capture)
    }

    @Test fun thirtyPagesProduceOneCaptureEachEvenWhenLeftInView() {
        val gate = AutoCaptureGate(stableMillis = 600, removalMillis = 300)
        var time = 0L
        var captures = 0
        repeat(30) {
            // A page remains visible long after its first capture.
            repeat(16) {
                if (gate.update(page, time).capture) captures++
                time += 150
            }
            assertEquals(it + 1, captures)
            repeat(4) { gate.update(null, time); time += 150 }
        }
        assertEquals(30, captures)
    }

    @Test fun failedCaptureCanRetryTheSamePageAfterAFullStabilityWindow() {
        val gate = AutoCaptureGate(stableMillis = 600)
        for (time in 0L..450L step 150) gate.update(page, time)
        assertTrue(gate.update(page, 600).capture)
        gate.reset() // Camera/save failure: the page was never successfully stored.
        for (time in 750L..1200L step 150) assertFalse(gate.update(page, time).capture)
        assertTrue(gate.update(page, 1350).capture)
    }
}
