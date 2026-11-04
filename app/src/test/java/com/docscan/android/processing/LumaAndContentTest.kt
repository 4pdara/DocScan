package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class LumaAndContentTest {
    @Test fun chromaPaddingCropOffsetsAndRotationDoNotMoveThePageContrast() {
        val y = ByteBuffer.wrap(ByteArray(8*4) { 180.toByte() })
        val u = ByteBuffer.wrap(byteArrayOf(99, 125, 99, 140.toByte(), 99, 99, 99, 99, 99, 130.toByte(), 99, 120, 99, 99, 99, 99)).apply { position(1) }
        val v = ByteBuffer.wrap(ByteArray(16) { 128.toByte() }).apply { position(1) }
        for (rotation in listOf(0, 90, 180, 270)) {
            val result = LumaSampler.sample(y, 8, 2, 1, 1, 2, 2, rotation, 360,
                LumaSampler.Chroma(u, 8, 2), LumaSampler.Chroma(v, 8, 2))
            val original = intArrayOf(PageContrast.yuv(180,125,128), PageContrast.yuv(180,140,128), PageContrast.yuv(180,130,128), PageContrast.yuv(180,120,128))
            val order = when(rotation) { 90 -> listOf(2,0,3,1); 180 -> listOf(3,2,1,0); 270 -> listOf(1,3,0,2); else -> listOf(0,1,2,3) }
            assertArrayEquals(order.map { original[it] }.toIntArray(), result.pageContrast)
        }
        assertEquals(1,u.position()); assertEquals(1,v.position()); assertEquals(0,y.position())
    }

    @Test fun paddedYPlaneAndCropOriginAreRespected() {
        val bytes = ByteArray(8 * 4) { 99 }
        for (y in 0..3) for (x in 0..3) bytes[y * 8 + x * 2] = (y * 10 + x).toByte()
        val result = LumaSampler.sample(ByteBuffer.wrap(bytes), 8, 2, 1, 1, 2, 2, 0)
        assertArrayEquals(intArrayOf(11, 12, 21, 22), result.pixels)
    }

    @Test fun everySensorRotationPreservesCorrectCornerOrder() {
        val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))
        val expected = mapOf(0 to intArrayOf(1, 2, 3, 4, 5, 6), 90 to intArrayOf(5, 3, 1, 6, 4, 2),
            180 to intArrayOf(6, 5, 4, 3, 2, 1), 270 to intArrayOf(2, 4, 6, 1, 3, 5))
        for ((rotation, pixels) in expected) {
            val image = LumaSampler.sample(input, 2, 1, 0, 0, 2, 3, rotation)
            assertArrayEquals("rotation=$rotation", pixels, image.pixels)
            assertEquals(if (rotation % 180 == 0) 2 else 3, image.width)
        }
        assertEquals(0, input.position())
    }

    @Test fun exposureChangeDoesNotCreateANewPageFingerprint() {
        val first = GrayImage(120, 120, IntArray(14400) { if (it / 120 % 20 < 5) 45 else 200 })
        val brighter = first.copy(pixels = first.pixels.map { it + 20 }.toIntArray())
        val a = PageFingerprint.from(first, CropQuad.FULL)
        val b = PageFingerprint.from(brighter, CropQuad.FULL)
        assertTrue(a.distance(b) < .01f)
        assertFalse(a.differsFrom(b))
    }

    @Test fun stableNewContentCanScanWithoutChangingThePageOutline() {
        val page = CropQuad(CropPoint(.1f, .1f), CropPoint(.9f, .1f), CropPoint(.9f, .9f), CropPoint(.1f, .9f))
        val first = PageFingerprint(List(144) { if (it % 2 == 0) -20f else 20f })
        val next = PageFingerprint(first.cells.map { -it })
        val gate = AutoCaptureGate(600)
        for (t in 0L..450L step 150) assertFalse(gate.update(page, t, fingerprint = first).capture)
        assertTrue(gate.update(page, 600, fingerprint = first).capture)
        for (t in 750L..1200L step 150) assertFalse(gate.update(page, t, fingerprint = first).capture)
        for (t in 1350L..1800L step 150) assertFalse(gate.update(page, t, fingerprint = next).capture)
        assertTrue(gate.update(page, 1950, fingerprint = next).capture)
        assertFalse(gate.update(page, 2100, fingerprint = next).capture)
    }
}
