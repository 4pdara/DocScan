package com.docscan.rebuild.export

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.imaging.ImagePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class DocumentOutput(private val context: Context, private val store: ScanStore) {
    /** Page geometry is in PDF points. Each raster is rendered at source resolution before drawing. */
    suspend fun pdf(bookId: String): File = withContext(Dispatchers.IO) {
        val pages = store.dao.snapshot(bookId); require(pages.isNotEmpty())
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, sheet ->
                val bitmap = ImagePipeline.render(store.source(sheet), sheet.edit())
                try {
                    val width = 595; val height = (width * bitmap.height.toDouble() / bitmap.width).roundToInt().coerceAtLeast(1)
                    val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                    page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                } finally { bitmap.recycle() }
            }
            FileOutputStream(target).use { document.writeTo(it); it.fd.sync() }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
        finally { document.close() }
    }
}
