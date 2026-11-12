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
        gate.sample(Frame.FULL, content, 4200)
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
        gate.sample(moved, content, 1200)
        assertTrue(gate.sample(moved, content, 1600).capture)
        gate.failed()
        assertFalse(gate.sample(moved, content, 1700).capture)
    }

    @Test fun edgeHandlesTranslateBothEndpointsAndClampAtImageBoundary() {
        val f=Frame(listOf(Vertex(.2f,.2f),Vertex(.8f,.2f),Vertex(.8f,.8f),Vertex(.2f,.8f)))
        val moved=f.dragHandle(4,.3f,.1f,.75f)
        assertEquals(.3f,moved.points[0].y,.00001f);assertEquals(.3f,moved.points[1].y,.00001f)
        assertEquals(f.points[2],moved.points[2]);assertEquals(f.points[3],moved.points[3])
        assertEquals(.2f,moved.points[0].x,.00001f)
        val clamped=f.dragHandle(7,-2f,0f,.75f)
        assertTrue(clamped.valid());assertEquals(0f,clamped.points[0].x,.00001f);assertEquals(0f,clamped.points[3].x,.00001f)
    }
    @Test fun tiltedSidePreservesItsDirectionAndCannotCrossOppositeEdge() {
        val f=Frame(listOf(Vertex(.2f,.1f),Vertex(.8f,.2f),Vertex(.9f,.8f),Vertex(.1f,.9f)))
        val next=f.dragHandle(4,.05f,.1f,.7f)
        assertEquals(f.points[1].x-f.points[0].x,next.points[1].x-next.points[0].x,.00001f)
        assertEquals(f.points[1].y-f.points[0].y,next.points[1].y-next.points[0].y,.00001f)
        assertTrue(f.dragHandle(4,0f,2f).valid())
        assertEquals(f,f.dragHandle(0,Float.NaN,0f))
    }
    @Test fun splitUsesThePerspectiveFrameWithoutLosingTheGutter() {
        val f=Frame(listOf(Vertex(.1f,.1f),Vertex(.8f,.2f),Vertex(.9f,.9f),Vertex(.2f,.8f)))
        val(left,right)=f.split(.4f)
        assertEquals(f.area(),left.area()+right.area(),.00001f)
        assertEquals(left.points[1],right.points[0]);assertEquals(left.points[2],right.points[3])
        assertTrue(left.valid()&&right.valid())
    }
    @Test fun curvesHaveAnIdentityResetAndMonotonicVerticalMapping() {
        assertFalse(BookCurve().active);assertEquals(.6f,BookCurve().sourceY(.5f,.6f),.00001f)
        for(t in listOf(-.2f,.2f))for(b in listOf(-.2f,.2f)) {
            val curve=BookCurve(t,b);assertTrue(curve.valid())
            for(x in 0..10)assertTrue(curve.sourceY(x/10f,.9f)>curve.sourceY(x/10f,.1f))
            assertEquals(.6f,curve.sourceY(0f,.6f),.00001f)
        }
        assertFalse(BookCurve(Float.NaN).valid())
    }
    @Test fun boundaryConsensusRejectsAnIsolatedJumpAndSmoothsSmallJitter() {
        val tracker=BoundaryTracker();val f=Frame(listOf(Vertex(.2f,.2f),Vertex(.7f,.2f),Vertex(.7f,.7f),Vertex(.2f,.7f)))
        assertNull(tracker.sample(f));assertEquals(f,tracker.sample(f))
        val jitter=Frame(f.points.map {Vertex(it.x+.01f,it.y)})
        val smoothed=tracker.sample(jitter)!!;assertTrue(smoothed.motion(f)<jitter.motion(f))
        val jump=Frame(f.points.map {Vertex(it.x+.15f,it.y)})
        assertNull(tracker.sample(jump));assertNotNull(tracker.sample(f))
        assertNull(tracker.sample(null));assertNull(tracker.sample(f))
    }
    @Test fun twoObservationsOrChangingContentCannotTriggerAutomaticCapture() {
        val gate=CaptureStability();val a=List(64){if(it<32)40f else 180f}
        assertFalse(gate.sample(Frame.FULL,a,0).capture)
        assertFalse(gate.sample(Frame.FULL,a,1000).capture)
        assertFalse(gate.sample(Frame.FULL,a.reversed(),1200).capture)
        assertFalse(gate.sample(Frame.FULL,a,2200).capture)
        gate.unstable();assertFalse(gate.sample(Frame.FULL,a,3200).capture)
    }
}
