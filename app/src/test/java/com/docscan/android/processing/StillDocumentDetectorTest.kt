package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test

class StillDocumentDetectorTest {
    @Test fun normalManualShotFallsBackToTheWiderSearchForADistantDarkPage() {
        val w = 720; val h = 960
        val pixels = IntArray(w * h) { 225 }
        for (y in 330..620) for (x in 260..450) pixels[y * w + x] = 70
        val image = GrayImage(w, h, pixels)
        val found = requireNotNull(StillDocumentDetector.detect(image, DetectionMode.STANDARD))
        val expected = CropQuad(CropPoint(260f / w, 330f / h), CropPoint(450f / w, 330f / h),
            CropPoint(450f / w, 620f / h), CropPoint(260f / w, 620f / h))
        assertTrue("Must find the page, not the background", found.quad.maxDistance(expected) < .02f)
    }

    @Test fun savedPhotoRefinementRetainsTheOuterPageAndItsPrintedEdges() {
        val w = 720; val h = 960
        val pixels = IntArray(w * h) { 45 }
        for (y in 120..840) for (x in 90..630) pixels[y * w + x] = 220
        for (y in 160..800 step 35) for (x in 115..605) pixels[y * w + x] = 40
        val image = GrayImage(w, h, pixels)
        val found = requireNotNull(StillDocumentDetector.detect(image, DetectionMode.STANDARD))
        val expected = CropQuad(CropPoint(.125f, .125f), CropPoint(.875f, .125f), CropPoint(.875f, .875f), CropPoint(.125f, .875f))
        assertTrue(found.quad.maxDistance(expected) < .015f)
    }

    @Test fun anUnboundedOrBlankSceneDoesNotInventACrop() {
        for (value in listOf(25, 180, 255)) {
            assertNull(StillDocumentDetector.detect(GrayImage(720, 960, IntArray(720 * 960) { value }), DetectionMode.STANDARD))
        }
    }
}
