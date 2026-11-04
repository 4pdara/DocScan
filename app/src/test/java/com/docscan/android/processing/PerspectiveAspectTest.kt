package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PerspectiveAspectTest {
    private fun projected(ratio: Double, yaw: Double, pitch: Double): CropQuad {
        val c=cos(yaw);val s=sin(yaw);val cp=cos(pitch);val sp=sin(pitch)
        return CropQuad.from(listOf(0.0 to 0.0,1.0 to 0.0,1.0 to 1.0,0.0 to 1.0).map { (u,v) ->
            val x=(u-.5)*ratio;val y=v-.5
            val px=c*x+s*sp*y;val py=cp*y;val pz=3-s*x+c*sp*y
            CropPoint(((400+600*px/pz)/800).toFloat(),((450+600*py/pz)/900).toFloat())
        })
    }
    @Test fun recoversPortraitLandscapeAndSquareRatiosFromProjectedCameraRectangles() {
        for (ratio in listOf(.707,1.586,1.0)) {
            val quad=projected(ratio,.4,.6)
            assertEquals(ratio,requireNotNull(PerspectiveAspect.estimate(800,900,quad)).toDouble(),.002)
        }
    }
    @Test fun parallelAndIllConditionedCornersKeepTheExistingSizeFallback() {
        assertNull(PerspectiveAspect.estimate(800,900,CropQuad.FULL))
        assertNull(PerspectiveAspect.estimate(800,900,projected(.707,0.0,.6)))
        val nonPhysical=CropQuad(CropPoint(.42f,.20f),CropPoint(.56f,.23f),CropPoint(.88f,.86f),CropPoint(.12f,.77f))
        assertNull(PerspectiveAspect.estimate(600,800,nonPhysical))
    }
}
