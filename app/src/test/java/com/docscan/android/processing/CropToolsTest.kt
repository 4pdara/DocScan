package com.docscan.android.processing

import org.junit.Assert.*
import org.junit.Test

class CropToolsTest {
    private val quad = CropQuad(CropPoint(.2f, .15f), CropPoint(.85f, .2f), CropPoint(.8f, .9f), CropPoint(.1f, .8f))

    @Test fun rectangleModeKeepsItsOppositeCornerAndParallelSides() {
        val rectangle = quad.rectangle()
        val edited = requireNotNull(rectangle.moveRectangleCorner(0, CropPoint(.3f, .35f)))
        assertEquals(rectangle.bottomRight, edited.bottomRight)
        assertEquals(.3f, edited.topLeft.x, .0001f)
        assertEquals(edited.topLeft.y, edited.topRight.y)
        assertEquals(edited.bottomLeft.x, edited.topLeft.x)
        assertTrue(requireNotNull(edited.moveRectangleCorner(0, CropPoint(.99f, .99f))).isValid())
    }

    @Test fun edgeDraggingMovesOnlyItsTwoEndpointsAndClampsToTheSource() {
        val shifted = requireNotNull(quad.moveEdge(0, .03f, .02f))
        assertEquals(quad.bottomLeft, shifted.bottomLeft); assertEquals(quad.bottomRight, shifted.bottomRight)
        assertEquals(quad.topLeft.x + .03f, shifted.topLeft.x, .0001f)
        val limited = requireNotNull(quad.moveEdge(0, 10f, 0f))
        assertEquals(1f, limited.topRight.x, .0001f); assertTrue(limited.isValid())
        assertNull(quad.moveEdge(0, 0f, .9f))
    }

    @Test fun pageFormatsUseTheSelectedOrientationAndStandardPdfDimensions() {
        assertNull(PageFormat.NATURAL.aspect(false))
        assertEquals(612 to 792, PageFormat.LETTER.pdfSize(false))
        assertEquals(842 to 595, PageFormat.A4.pdfSize(true))
        assertEquals(243 to 153, PageFormat.ID_CARD.pdfSize(true))
        assertEquals(1f, requireNotNull(PageFormat.SQUARE.aspect(true)), .0001f)
        assertEquals(PageFormat.NATURAL, PageFormat.fromKey("unknown"))
    }
}
