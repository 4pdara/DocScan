package com.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscan.android.data.DocScanDatabase
import com.docscan.android.data.DocumentRepository
import com.docscan.android.export.PdfExporter
import com.docscan.android.export.JpegExporter
import com.docscan.android.processing.ScanFilter
import com.docscan.android.processing.CropPoint
import com.docscan.android.processing.CropQuad
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires an emulator/device; validates the actual Android PDF/bitmap stack. */
@RunWith(AndroidJUnit4::class)
class ExportFlowTest {
    @Test fun jpegExportIncludesCropRotationAndCleanupStrength() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "jpeg-${UUID.randomUUID()}.db"
        val database = DocScanDatabase.open(context, name)
        val repository = DocumentRepository(context, database)
        val input = File(context.cacheDir, "jpeg-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "jpeg-${UUID.randomUUID()}.jpg")
        var documentId: String? = null
        try {
            val image = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.rgb(160, 160, 160))
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            val original = input.readBytes()
            val id = repository.create("JPEG").also { documentId = it }
            val page = repository.importImage(id, Uri.fromFile(input))
            repository.setCrop(page.id, CropQuad(CropPoint(.2f, .2f), CropPoint(.8f, .2f), CropPoint(.8f, .8f), CropPoint(.2f, .8f)))
            repository.rotate(page.id); repository.setFilter(page.id, ScanFilter.CLEAN_COLOR); repository.setFilterStrength(page.id, 100)
            repository.readSnapshot(id) { _, pages -> output.outputStream().use { JpegExporter.write(pages.single(), repository, it) } }
            val rendered = requireNotNull(BitmapFactory.decodeFile(output.path))
            try {
                assertEquals(240, rendered.width); assertEquals(180, rendered.height)
                assertTrue(Color.red(rendered.getPixel(120, 90)) >= 250)
            } finally { rendered.recycle() }
            assertArrayEquals(original, repository.source(page).readBytes())
        } finally {
            documentId?.let { repository.deleteDocument(it) }; database.close(); context.deleteDatabase(name)
            input.delete(); output.delete()
        }
    }

    @Test fun cleanupStrengthIsAppliedToTheActualPdf() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "cleanup-export-${UUID.randomUUID()}.db"
        val database = DocScanDatabase.open(context, name)
        val repository = DocumentRepository(context, database)
        val input = File(context.cacheDir, "cleanup-pdf-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "cleanup-pdf-${UUID.randomUUID()}.pdf")
        var documentId: String? = null
        fun centerGray(): Int {
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                pdf.openPage(0).use { page ->
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return Color.red(rendered.getPixel(rendered.width / 2, rendered.height / 2))
                    } finally { rendered.recycle() }
                }
            }
        }
        try {
            val image = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.rgb(160, 160, 160))
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            val original = input.readBytes()
            val id = repository.create("Cleanup PDF").also { documentId = it }
            val page = repository.importImage(id, Uri.fromFile(input))
            repository.setFilter(page.id, ScanFilter.CLEAN_COLOR)
            repository.setFilterStrength(page.id, 0)
            repository.readSnapshot(id) { _, pages -> PdfExporter.write(pages, repository, output) }
            assertTrue(centerGray() in 150..170)
            repository.setFilterStrength(page.id, 100)
            repository.readSnapshot(id) { _, pages -> PdfExporter.write(pages, repository, output) }
            assertTrue(centerGray() >= 250)
            assertArrayEquals(original, repository.source(page).readBytes())
        } finally {
            documentId?.let { repository.deleteDocument(it) }; database.close(); context.deleteDatabase(name)
            input.delete(); output.delete()
        }
    }

    @Test fun savedCropIsAppliedToPdfWithoutChangingTheOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "crop-export-${UUID.randomUUID()}.db"
        var database = DocScanDatabase.open(context, databaseName)
        var repository = DocumentRepository(context, database)
        val input = File(context.cacheDir, "crop-export-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "crop-export-${UUID.randomUUID()}.pdf")
        var documentId: String? = null
        try {
            val image = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.DKGRAY)
            Canvas(image).apply {
                drawRect(80f, 50f, 280f, 430f, Paint().apply { color = Color.WHITE })
                drawRect(120f, 130f, 240f, 350f, Paint().apply { color = Color.RED })
            }
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val original = input.readBytes()
            val id = repository.create("חיתוך ל־PDF").also { documentId = it }
            val page = repository.importImage(id, Uri.fromFile(input))
            val crop = CropQuad(CropPoint(.2f, .1f), CropPoint(.7f, .1f), CropPoint(.7f, .86f), CropPoint(.2f, .86f))
            repository.setCrop(page.id, crop)
            database.close()
            database = DocScanDatabase.open(context, databaseName)
            repository = DocumentRepository(context, database)
            assertEquals(crop, CropQuad.decode(database.documents().page(page.id)?.crop))
            repository.readSnapshot(id) { _, pages -> PdfExporter.write(pages, repository, output) }
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(1, pdf.pageCount)
                pdf.openPage(0).use { renderedPage ->
                    val rendered = Bitmap.createBitmap(renderedPage.width, renderedPage.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        renderedPage.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(rendered.width * rendered.height)
                        rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                        assertTrue("Page content must survive the crop", pixels.count {
                            Color.red(it) > 180 && Color.green(it) < 80 && Color.blue(it) < 80
                        } > 1000)
                        assertEquals("The dark background must be removed from the PDF", 0, pixels.count {
                            Color.red(it) < 120 && Color.green(it) < 120 && Color.blue(it) < 120
                        })
                    } finally { rendered.recycle() }
                }
            }
            assertArrayEquals(original, repository.source(page).readBytes())
        } finally {
            documentId?.let { repository.deleteDocument(it) }
            database.close(); context.deleteDatabase(databaseName)
            input.delete(); output.delete()
        }
    }

    @Test fun twoImportedPagesProduceReadablePdfAndKeepOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "export-test-${UUID.randomUUID()}.db"
        val database = DocScanDatabase.open(context, databaseName)
        val repository = DocumentRepository(context, database)
        var documentId: String? = null
        val input = File(context.cacheDir, "fixture-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "result-${UUID.randomUUID()}.pdf")
        try {
            Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(0xFFBB9977.toInt())
                input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val original = input.readBytes()
            val id = repository.create("מסמך בדיקה").also { documentId = it }
            val first = repository.importImage(id, Uri.fromFile(input))
            val second = repository.importImage(id, Uri.fromFile(input))
            repository.rotate(second.id); repository.setFilter(first.id, ScanFilter.GRAYSCALE)
            repository.readSnapshot(id) { _, pages -> PdfExporter.write(pages, repository, output) }
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { assertEquals(595, it.width); assertEquals(842, it.height) }
                renderer.openPage(1).use { assertEquals(842, it.width); assertEquals(595, it.height) }
            }
            assertArrayEquals(original, repository.source(first).readBytes())
            assertArrayEquals(original, repository.source(second).readBytes())
        } finally {
            documentId?.let { repository.deleteDocument(it) }
            database.close(); context.deleteDatabase(databaseName)
            input.delete(); output.delete()
        }
    }
}
