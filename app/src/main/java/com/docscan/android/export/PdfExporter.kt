package com.docscan.android.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ScanFilter
import java.io.File

object PdfExporter {
    /** Renders one page at a time; exports the saved rotation/filter snapshot. */
    fun write(pages: List<PageEntity>, repository: DocumentRepository, destination: File) {
        require(pages.isNotEmpty())
        val pdf = PdfDocument()
        try {
                pages.forEachIndexed { index, page ->
                    val bitmap = ImagePipeline.render(repository.source(page), page.rotation, ScanFilter.valueOf(page.filter), 2400, CropQuad.decode(page.crop))
                    try {
                        val landscape = bitmap.width > bitmap.height
                        val width = if (landscape) 842 else 595
                        val height = if (landscape) 595 else 842
                        val outputPage = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                        try {
                            outputPage.canvas.drawColor(Color.WHITE)
                            val scale = minOf((width - 36f) / bitmap.width, (height - 36f) / bitmap.height)
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
