package com.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.data.DocScanDatabase
import com.docscan.android.data.DocumentRepository
import com.docscan.android.export.JpegExporter
import com.docscan.android.export.PdfExporter
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ImageAdjustments
import com.docscan.android.processing.PageFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProfessionalExportTest {
    @Test fun savedAdjustmentsAndPageShapeReachTheRealJpegAndPdf() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "pro-export-${UUID.randomUUID()}.db"
        val db = DocScanDatabase.open(context, name); val repository = DocumentRepository(context, db)
        val input = File(context.cacheDir, "pro-export-${UUID.randomUUID()}.png")
        val jpeg = File(context.cacheDir, "pro-export-${UUID.randomUUID()}.jpg")
        val pdfFile = File(context.cacheDir, "pro-export-${UUID.randomUUID()}.pdf")
        var id: String? = null
        try {
            val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(60, 100, 140))
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val original = input.readBytes(); val doc = repository.create("Quality").also { id = it }
            val page = repository.importImage(doc, Uri.fromFile(input))
            repository.setAdjustments(page.id, ImageAdjustments(brightness = 20, saturation = -100, sharpness = 45))
            repository.setCropAndFormat(page.id, CropQuad.FULL, PageFormat.SQUARE)
            repository.readSnapshot(doc) { _, pages -> jpeg.outputStream().use { JpegExporter.write(pages.single(), repository, it) } }
            val rendered = requireNotNull(BitmapFactory.decodeFile(jpeg.path))
            try {
                assertEquals(rendered.width, rendered.height)
                val pixel = rendered.getPixel(rendered.width / 2, rendered.height / 2)
                assertTrue(Color.red(pixel) in 115..121); assertTrue(kotlin.math.abs(Color.red(pixel) - Color.blue(pixel)) <= 2)
            } finally { rendered.recycle() }
            repository.setCropAndFormat(page.id, CropQuad.FULL, PageFormat.LETTER)
            repository.readSnapshot(doc) { _, pages -> PdfExporter.write(pages, repository, pdfFile) }
            PdfRenderer(ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                pdf.openPage(0).use { output ->
                    assertEquals(612, output.width); assertEquals(792, output.height)
                    val image = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
                    try {
                        output.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertTrue(Color.red(image.getPixel(306, 396)) in 115..121)
                    } finally { image.recycle() }
                }
            }
            assertArrayEquals(original, repository.source(page).readBytes())
        } finally {
            id?.let { repository.deleteDocument(it) }; db.close(); context.deleteDatabase(name)
            input.delete(); jpeg.delete(); pdfFile.delete()
        }
    }

    @Test fun jpegExportKeepsSourceDetailAboveThePrevious2400PixelLimit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "detail-${UUID.randomUUID()}.db"
        val db = DocScanDatabase.open(context, name); val repository = DocumentRepository(context, db)
        val input = File(context.cacheDir, "detail-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "detail-${UUID.randomUUID()}.jpg"); var id: String? = null
        try {
            val image = Bitmap.createBitmap(2700, 1800, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE)
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            val doc = repository.create("Detail").also { id = it }; val page = repository.importImage(doc, Uri.fromFile(input))
            repository.readSnapshot(doc) { _, pages -> output.outputStream().use { JpegExporter.write(pages.single(), repository, it) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(output.path, bounds)
            assertEquals(2700, bounds.outWidth); assertEquals(1800, bounds.outHeight)
            assertEquals(0, page.sharpness)
        } finally { id?.let { repository.deleteDocument(it) }; db.close(); context.deleteDatabase(name); input.delete(); output.delete() }
    }
}
