package com.docscan.rebuild
import com.docscan.rebuild.model.*
import com.docscan.rebuild.tools.OfficeExport
import org.junit.Assert.*
import org.junit.Test

class ToolModelTest {
    @Test fun orderedPageRangesRejectAmbiguityAndOutOfBounds(){
        assertEquals(listOf(4,0,1,2),PageSelection.parse("5, 1-3",5))
        listOf("","0","6","3-1","1,1","1-3,2","1-2-3","1,","-1").forEach { value -> assertTrue(value,runCatching{PageSelection.parse(value,5)}.isFailure) }
    }
    @Test fun decorationsRejectInvalidCoordinatesAndBoundWork(){
        assertTrue(PageDecorations(signature=listOf(InkStroke(listOf(Vertex(.1f,.2f),Vertex(.8f,.9f))))).valid())
        assertFalse(PageDecorations(erasures=listOf(EraseRegion(.6f,0f,.3f,1f))).valid())
        assertFalse(PageDecorations(signature=listOf(InkStroke(listOf(Vertex(Float.NaN,.1f))))).valid())
    }
    @Test fun officeEscapingAndCellNamesNeverBecomeFormulas(){
        assertEquals("A",OfficeExport.column(0));assertEquals("Z",OfficeExport.column(25));assertEquals("AA",OfficeExport.column(26));assertEquals("XFD",OfficeExport.column(16383))
        assertEquals("&lt;script&gt;&amp;&quot;=SUM(A1)\t",OfficeExport.xml("<script>&\"=SUM(A1)\t\u0000"))
    }
}
