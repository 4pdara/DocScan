package com.docscan.android.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.data.render
import com.docscan.android.processing.PageFormat
import java.io.File

object PdfExporter {
    /** Renders one page at a time; exports the saved rotation/filter snapshot. */
    fun write(pages: List<PageEntity>, repository: DocumentRepository, destination: File) {
        require(pages.isNotEmpty())
        val pdf = PdfDocument()
        try {
                pages.forEachIndexed { index, page ->
                    val bitmap = page.render(repository.source(page), 3200)
                    try {
                        val landscape = bitmap.width > bitmap.height
                        val format = PageFormat.fromKey(page.pageFormat)
                        val (width, height) = format.pdfSize(landscape)
                        val outputPage = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                        try {
                            outputPage.canvas.drawColor(Color.WHITE)
                            val margin = if (format == PageFormat.NATURAL) 36f else 0f
                            val scale = minOf((width - margin) / bitmap.width, (height - margin) / bitmap.height)
                            val drawnWidth = bitmap.width * scale
                            val drawnHeight = bitmap.height * scale
                            val left = (width - drawnWidth) / 2f
                            val top = (height - drawnHeight) / 2f
                            outputPage.canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawnWidth, top + drawnHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                        } finally { pdf.finishPage(outputPage) }
                    } finally { bitmap.recycle() }
                }
                destination.outputStream().use { pdf.writeTo(it) }
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        } finally {
            pdf.close()
        }
    }
}
