package com.docscan.rebuild

import com.docscan.rebuild.export.PdfLayout
import org.junit.Assert.*
import org.junit.Test

class PdfLayoutTest {
    @Test fun scanShapeKeepsTheOldPagesAndAddsMargins() {
        val plain = PdfLayout().place(1000, 1500)
        assertEquals(595.0, plain.pageWidth, 1e-9); assertEquals(892.5, plain.pageHeight, 1e-9)
        assertEquals(0.0, plain.x, 1e-9); assertEquals(595.0, plain.width, 1e-9)
        val framed = PdfLayout(margin = PdfLayout.Margin.NORMAL).place(1000, 1500)
        assertEquals(595.0, framed.pageWidth, 1e-9); assertEquals(28.35, framed.x, 1e-9)
        assertEquals(framed.width * 1.5, framed.height, 1e-9); assertEquals(framed.height + 2 * 28.35, framed.pageHeight, 1e-9)
    }

    @Test fun paperFitsTheScanCentredAndTurnsForWideScans() {
        val a4 = PdfLayout(PdfLayout.Paper.A4, margin = PdfLayout.Margin.NORMAL).place(1800, 2400)
        assertEquals(595.28, a4.pageWidth, 1e-9); assertEquals(841.89, a4.pageHeight, 1e-9)
        assertEquals(595.28 - 2 * 28.35, a4.width, 1e-6)
        assertEquals((a4.pageHeight - a4.height) / 2, a4.y, 1e-9); assertEquals(a4.y, a4.top, 1e-9)
        val wide = PdfLayout(PdfLayout.Paper.A4).place(3000, 2000)
        assertEquals(841.89, wide.pageWidth, 1e-9); assertEquals(595.28, wide.pageHeight, 1e-9)
        val portrait = PdfLayout(PdfLayout.Paper.LETTER, PdfLayout.Orientation.PORTRAIT).place(3000, 2000)
        assertEquals(612.0, portrait.pageWidth, 1e-9); assertEquals(612.0, portrait.width, 1e-9); assertEquals(408.0, portrait.height, 1e-9)
        assertEquals((792.0 - 408.0) / 2, portrait.y, 1e-9)
    }
}
