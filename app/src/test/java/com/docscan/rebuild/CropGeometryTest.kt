package com.docscan.rebuild

import com.docscan.rebuild.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CropGeometryTest {
    private fun projected(yaw: Double, pitch: Double, roll: Double = 0.0): Frame {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        return Frame(listOf(-80.0 to -125.0, 80.0 to -125.0, 80.0 to 125.0, -80.0 to 125.0).map { (x, y) ->
            val xx = cy * x + sy * sp * y; val yy = cp * y; val z = -sy * x + cy * sp * y + 650.0
            Vertex(((899.5 + 1900 * (cr * xx - sr * yy) / z) / 1799).toFloat(), ((1199.5 + 1900 * (sr * xx + cr * yy) / z) / 2399).toFloat())
        })
    }
    @Test fun fullPhotoKeepsExactPixelsAndQuarterTurnSwapsAxes() {
        val d = CropGeometry.dimensions(Frame.FULL, 4032, 3024)
        assertEquals(4032, d.width); assertEquals(3024, d.height)
        assertEquals(CropGeometry.Dimensions(3024, 4032), d.rotated(1))
    }
    @Test fun obliqueRectangleRecoversItsMetricRatioInsteadOfForeshortenedEdges() {
        for (yaw in listOf(-.65, -.3, .4, .7)) for (pitch in listOf(-.45, .25, .5)) {
            val frame = projected(yaw, pitch, .12); assertTrue(frame.valid())
            val d = CropGeometry.dimensions(frame, 1800, 2400)
            assertEquals("yaw=$yaw pitch=$pitch", 160.0 / 250, d.width.toDouble() / d.height, .004)
        }
    }
    @Test fun calibratedFocalHandlesOneAxisTiltAndMatchesPreviewScale() {
        val frame = projected(.75, 0.0)
        val full = CropGeometry.dimensions(frame, 1800, 2400, focalToDiagonal = 1900.0 / 3000)
        val preview = CropGeometry.dimensions(frame, 900, 1200, focalToDiagonal = 1900.0 / 3000)
        assertEquals(.64, full.aspect.toDouble(), .003)
        assertEquals(full.aspect, preview.aspect, .003f)
    }
    @Test fun frontalCropAndExplicitBookRatioRemainStable() {
        val frame = Frame(listOf(Vertex(.1f,.1f),Vertex(.9f,.1f),Vertex(.9f,.9f),Vertex(.1f,.9f)))
        assertEquals(.75, CropGeometry.dimensions(frame, 1800, 2400).aspect.toDouble(), .002)
        assertEquals(.64, CropGeometry.dimensions(frame, 1800, 2400, .64f).aspect.toDouble(), .002)
        assertEquals(1.0, CropGeometry.dimensions(frame, 1800, 2400, 1f).aspect.toDouble(), .002)
    }
}
