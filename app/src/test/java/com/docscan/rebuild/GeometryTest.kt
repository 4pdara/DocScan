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
    @Test fun savedStraighteningKeepsBothModels() {
        val legacy = List(9) { .01f * it - .04f }
        assertTrue(Flattening.valid(legacy))
        val surface = listOf(16f, 6f) + List(96) { .002f * (it % 7) - .006f }
        assertTrue(Flattening.valid(surface))
        assertTrue(EditRecipe(flatten = surface).valid())
        assertFalse(Flattening.valid(surface.dropLast(1)))
        assertFalse(Flattening.valid(listOf(16.5f, 6f) + List(99) { 0f }))
        assertFalse(Flattening.valid(listOf(40f, 6f) + List(240) { 0f }))
        assertFalse(Flattening.valid(listOf(16f, 6f) + List(95) { 0f } + 2f))
        assertFalse(Flattening.valid(legacy.dropLast(1) + Float.NaN))
    }
    @Test fun outlineJitterBelowTwoAndAHalfPercentKeepsAutomaticCaptureSteady() {
        val gate = CaptureStability(); val content = List(64) { 100f }
        val frame = Frame(listOf(Vertex(.2f, .2f), Vertex(.7f, .2f), Vertex(.7f, .8f), Vertex(.2f, .8f)))
        assertFalse(gate.sample(frame, content, 0).capture)
        assertFalse(gate.sample(Frame(frame.points.map { Vertex(it.x + .02f, it.y) }), content, 400).capture)
        assertTrue(gate.sample(Frame(frame.points.map { Vertex(it.x - .01f, it.y) }), content, 800).capture)
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
        // The first outline shows at once; later looks smooth it.
        assertEquals(f,tracker.sample(f));assertEquals(f,tracker.sample(f))
        val jitter=Frame(f.points.map {Vertex(it.x+.01f,it.y)})
        val smoothed=tracker.sample(jitter)!!;assertTrue(smoothed.motion(f)<jitter.motion(f))
        // A single jump is not followed; two agreeing looks at the new place are.
        val jump=Frame(f.points.map {Vertex(it.x+.15f,it.y)})
        assertNull(tracker.sample(jump));assertNotNull(tracker.sample(f))
        assertNull(tracker.sample(jump));assertEquals(jump,tracker.sample(jump))
        assertNull(tracker.sample(null));assertEquals(f,tracker.sample(f))
    }
    @Test fun shownOutlineHoldsStillUntilACornerClearlyMoves() {
        val tracker=BoundaryTracker();val f=Frame(listOf(Vertex(.2f,.2f),Vertex(.7f,.2f),Vertex(.7f,.7f),Vertex(.2f,.7f)))
        assertEquals(f,tracker.sample(f))
        // Differences of under 2% between looks leave every corner exactly where it is shown.
        assertEquals(f,tracker.sample(Frame(f.points.mapIndexed {i,p->Vertex(p.x+(if(i%2==0) .012f else -.01f),p.y+.008f)})))
        // A corner that clearly moved follows at once, the others stay.
        val moved=Frame(f.points.mapIndexed {i,p->if(i==2) Vertex(p.x+.04f,p.y+.02f) else Vertex(p.x+.005f,p.y)})
        val shown=tracker.sample(moved)!!
        assertEquals(moved.points[2],shown.points[2]);assertEquals(f.points[0],shown.points[0]);assertEquals(f.points[3],shown.points[3])
    }
    @Test fun quickLooksMoveTheWholeOutlineAndKeepItThroughOneOddLook() {
        // 1.3.8: with the page network a look comes every 120 ms or so.
        val tracker=BoundaryTracker(quick=true);val f=Frame(listOf(Vertex(.2f,.2f),Vertex(.7f,.2f),Vertex(.7f,.7f),Vertex(.2f,.7f)))
        fun moved(dx:Float)=Frame(f.points.map {Vertex(it.x+dx,it.y)})
        assertEquals(f,tracker.sample(f))
        // The phone moving several percent per look: every look is shown.
        for(i in 1..4) assertEquals(moved(i*.05f),tracker.sample(moved(i*.05f)))
        // One corner 3% away: the outline shown is the new look as a whole, never corners of two looks.
        val bent=Frame(moved(.2f).points.mapIndexed {i,p->if(i==0) Vertex(p.x-.03f,p.y-.03f) else p})
        assertEquals(bent,tracker.sample(bent))
        // A single far look keeps the shown outline; the next look that agrees with it is followed.
        val far=moved(-.15f)
        assertEquals(bent,tracker.sample(far));assertEquals(far,tracker.sample(far))
        // Two far looks in a row that disagree with each other hide it.
        assertEquals(far,tracker.sample(moved(.1f)));assertNull(tracker.sample(moved(.2f)))
    }
    @Test fun aSmallPageAsksToComeCloserAndIsNotPhotographedAutomatically() {
        val gate=CaptureStability();val content=List(64){if(it%3==0) 60f else 190f}
        val small=Frame(listOf(Vertex(.35f,.35f),Vertex(.6f,.35f),Vertex(.6f,.75f),Vertex(.35f,.75f)))
        for(t in listOf(0L,600L,1200L,2400L)) { val d=gate.sample(small,content,t);assertTrue(d.closer);assertFalse(d.capture);assertEquals(0f,d.progress) }
        // Closer: the page fills more than half of the view, and steadiness is measured from then on.
        val near=Frame(listOf(Vertex(.15f,.12f),Vertex(.82f,.12f),Vertex(.82f,.9f),Vertex(.15f,.9f)))
        assertFalse(gate.sample(near,content,3000).closer)
        assertFalse(gate.sample(near,content,3400).capture)
        assertTrue(gate.sample(near,content,3800).capture)
        // A guided capture (an ID card is small) is not held back.
        val guided=CaptureStability()
        assertFalse(guided.sample(small,content,0,nearOnly=false).closer)
        guided.sample(small,content,400,nearOnly=false)
        assertTrue(guided.sample(small,content,800,nearOnly=false).capture)
    }
    @Test fun twoObservationsOrChangingContentCannotTriggerAutomaticCapture() {
        val gate=CaptureStability();val a=List(64){if(it<32)40f else 180f}
        assertFalse(gate.sample(Frame.FULL,a,0).capture)
        assertFalse(gate.sample(Frame.FULL,a,1000).capture)
        assertFalse(gate.sample(Frame.FULL,a.reversed(),1200).capture)
        assertFalse(gate.sample(Frame.FULL,a,2200).capture)
        gate.unstable();assertFalse(gate.sample(Frame.FULL,a,3200).capture)
    }
    @Test fun capturedPhotoKeepsTheOutlineShownInTheCamera() {
        fun quad(l: Float, t: Float, r: Float, b: Float) = Frame(listOf(Vertex(l, t), Vertex(r, t), Vertex(r, b), Vertex(l, b)))
        val shown = quad(.1f, .1f, .8f, .9f)
        // Detection on the full photo agrees: its more precise corners are used.
        val close = quad(.11f, .1f, .81f, .9f)
        assertEquals(close, Frame.choose(close, shown))
        // Detection found another shape (a text block, the facing page): the user gets what the camera showed.
        assertEquals(shown, Frame.choose(quad(.3f, .3f, .6f, .6f), shown))
        assertEquals(shown, Frame.choose(null, shown))
        // Nothing was shown (detection off or no page in view): only the photo's own detection counts.
        assertEquals(close, Frame.choose(close, null))
        assertNull(Frame.choose(null, null))
    }
    @Test fun turningAPageCarriesItsMarksAlongAndFourTurnsReturnThem() {
        val recipe = EditRecipe(
            erase = listOf(EraseMark(EraseKind.RECT, listOf(Vertex(.1f, .2f), Vertex(.3f, .25f))), EraseMark(EraseKind.STROKE, listOf(Vertex(.5f, .5f), Vertex(.6f, .7f)), .02f)),
            ink = listOf(InkStroke(listOf(Vertex(.2f, .8f)), .01f, 0xFF0000)), texts = listOf(TextMark("שלום", .25f, .75f, .04f)),
            watermarkSpot = MarkSpot(.2f, .3f, -30f), signatures = listOf(SignatureMark(listOf(listOf(Vertex(0f, 0f), Vertex(1f, 1f))), 2f, .5f, .7f, .3f)))
        assertTrue(recipe.valid())
        val once = recipe.turnedBy(1, .75f)
        assertEquals(1, once.turns)
        // Clockwise: the point (x, y) goes to (1 - y, x); a rectangle stays top-left / bottom-right.
        assertEquals(listOf(Vertex(.75f, .1f), Vertex(.8f, .3f)), once.erase[0].points)
        assertEquals(.25f, once.texts[0].x, 1e-6f); assertEquals(.25f, once.texts[0].y, 1e-6f)
        assertEquals(.7f, once.watermarkSpot.x, 1e-6f); assertEquals(.2f, once.watermarkSpot.y, 1e-6f); assertEquals(-30f, once.watermarkSpot.angle)
        // The signature keeps its size on paper: 0.3 of a page 0.75 as wide as tall is 0.225 of the turned page's width.
        assertEquals(.225f, once.signatures[0].width, 1e-5f)
        assertTrue(once.valid())
        val back = recipe.turnedBy(4, .75f)
        assertEquals(0, back.turns)
        recipe.erase.zip(back.erase).forEach { (a, b) -> a.points.zip(b.points).forEach { (p, q) -> assertEquals(p.x, q.x, 1e-5f); assertEquals(p.y, q.y, 1e-5f) } }
        assertEquals(recipe.signatures[0].width, back.signatures[0].width, 1e-5f)
        assertEquals(recipe.signatures[0].x, back.signatures[0].x, 1e-5f); assertEquals(recipe.signatures[0].y, back.signatures[0].y, 1e-5f)
    }
    @Test fun markValidationRejectsOutOfRangeValues() {
        assertFalse(EraseMark(EraseKind.RECT, listOf(Vertex(.1f, .1f))).valid())
        assertFalse(EraseMark(EraseKind.STROKE, listOf(Vertex(2f, .1f))).valid())
        assertFalse(InkStroke(emptyList(), .01f, 0).valid())
        assertFalse(TextMark(" ", .5f, .5f, .04f).valid())
        assertFalse(MarkStyle(alpha = 0).valid())
        assertEquals(MarkStyle(MarkFont.BOLD, MarkColor.BLUE, MarkStrength.STRONG, MarkSize.LARGE), MarkStyle(MarkFont.BOLD, MarkColor.BLUE.rgb, 130, 1.35f))
    }
}
