package com.docscan.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.data.*
import com.docscan.android.processing.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalExportTest {
    private lateinit var context: Context
    private lateinit var database: DocScanDatabase
    private lateinit var repository: DocumentRepository
    private val name = "resolution-${UUID.randomUUID()}.db"
    private val temporary = mutableListOf<File>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = DocScanDatabase.open(context, name)
        repository = DocumentRepository(context, database)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    }
    @After fun cleanup(): Unit = runBlocking {
        database.documents().allDocuments().forEach { repository.deleteDocument(it.id) }
        temporary.forEach { it.deleteRecursively() }; database.close(); context.deleteDatabase(name); Unit
    }
    private fun file(extension: String) = File(context.cacheDir, "${UUID.randomUUID()}.$extension").also { temporary.add(it) }
    private fun input(width: Int, height: Int, jpeg: Boolean = false): File {
        val result = file(if (jpeg) "jpg" else "png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in row.indices) row[x] = Color.rgb(x * 220 / width, y * 220 / height, 40)
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        result.outputStream().use { bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); return result
    }
    private suspend fun page(source: File) = repository.importImage(repository.create("מקור 123"), Uri.fromFile(source), detectBounds = false)
    private fun jpeg(page: PageEntity, quality: ExportQuality = ExportQuality.ORIGINAL) =
        ByteArrayOutputStream().use { JpegExporter.write(page, repository, it, quality); it.toByteArray() }
    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }
    private fun pdfImage(file: File): Pair<Int, Int> = PDDocument.load(file).use { pdf ->
        val resources = pdf.getPage(0).resources
        val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
        image.width to image.height
    }

    @Test fun autoDocumentFilterExportsAtSourceResolutionAndSurvivesBackup(): Unit = runBlocking {
        val input = file("png")
        val bitmap = Bitmap.createBitmap(3600, 700, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(215, 195, 160))
        for (y in 300..303) for (x in 300..3300) bitmap.setPixel(x, y, Color.rgb(65, 55, 40))
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val page = page(input); val original = repository.source(page).readBytes()
        repository.setFilter(page.id, ScanFilter.AUTO_DOCUMENT); repository.setFilterStrength(page.id, 100)
        val edited = database.documents().page(page.id)!!
        val result = jpeg(edited)
        assertEquals(3600 to 700, dimensions(result))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(result, 0, result.size))
        try {
            val paper = decoded.getPixel(1800, 200)
            assertTrue(Color.blue(paper) > 245)
            assertTrue(kotlin.math.abs(Color.red(paper) - Color.blue(paper)) < 8)
            assertTrue(Color.red(decoded.getPixel(1800, 301)) < 100)
        } finally { decoded.recycle() }
        val pdf = file("pdf"); PdfExporter.write(listOf(edited), repository, pdf)
        assertEquals(3600 to 700, pdfImage(pdf))
        val archive = ByteArrayOutputStream().also { repository.backup(it) }.toByteArray()
        repository.restore(archive.inputStream())
        val restored = database.documents().allDocuments().single { it.id != page.documentId }.id
        val copy = database.documents().pages(restored).single()
        assertEquals("AUTO_DOCUMENT", copy.filter); assertEquals(100, copy.filterStrength)
        assertArrayEquals(original, repository.source(copy).readBytes())
        assertArrayEquals(original, repository.source(edited).readBytes())
    }

    @Test fun defaultJpegPdfAndSharedImagesKeepNativeGridBeyond3200(): Unit = runBlocking {
        val page = page(input(4500, 1100)); val original = repository.source(page).readBytes()
        assertEquals(4500 to 1100, dimensions(jpeg(page)))
        val pdf = file("pdf"); PdfExporter.write(listOf(page), repository, pdf)
        assertEquals(4500 to 1100, pdfImage(pdf))
        val shared = ShareExport.prepare(context, "Original", listOf(page), repository, ShareFormat.BOTH)
        temporary.add(requireNotNull(shared.first().parentFile))
        assertEquals(4500 to 1100, dimensions(shared.single { it.extension == "jpg" }.readBytes()))
        assertEquals(4500 to 1100, pdfImage(shared.single { it.extension == "pdf" }))
        assertArrayEquals(original, repository.source(page).readBytes())
    }

    @Test fun cropUsesOriginalPixelsBeforeAnyOptionalDownsizing(): Unit = runBlocking {
        val page = page(input(5000, 2000))
        repository.setCrop(page.id, CropQuad(CropPoint(.1f, .2f), CropPoint(.9f, .2f), CropPoint(.9f, .8f), CropPoint(.1f, .8f)))
        val edited = database.documents().page(page.id)!!
        assertEquals(4000 to 1200, dimensions(jpeg(edited)))
        assertEquals(2560 to 768, dimensions(jpeg(edited, ExportQuality.HIGH)))
    }

    @Test fun everyExifOrientationAndUserRotationMatchTheFullFrameReference(): Unit = runBlocking {
        for (orientation in 1..8) {
            val source = input(800, 600, jpeg = true)
            ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val page = page(source)
            repository.setCrop(page.id, CropQuad(CropPoint(.18f, .15f), CropPoint(.82f, .2f), CropPoint(.85f, .87f), CropPoint(.13f, .9f)))
            repository.rotate(page.id)
            val edited = database.documents().page(page.id)!!
            val expected = edited.render(repository.source(edited), 1600)
            val actual = edited.renderForExport(repository.source(edited), ExportQuality.ORIGINAL)
            try { assertTrue("EXIF $orientation", expected.sameAs(actual)) }
            finally { expected.recycle(); actual.recycle() }
        }
    }

    @Test fun explicitSmallerProfilesNeverUpscaleTinySources(): Unit = runBlocking {
        val large = page(input(4500, 1100))
        assertEquals(3200, dimensions(jpeg(large, ExportQuality.HIGH)).first)
        assertEquals(1600, dimensions(jpeg(large, ExportQuality.COMPACT)).first)
        val tiny = page(input(300, 200))
        for (quality in ExportQuality.entries) assertEquals(300 to 200, dimensions(jpeg(tiny, quality)))
        assertEquals(ExportQuality.ORIGINAL, ExportQuality.fromKey(null))
        assertEquals(ExportQuality.ORIGINAL, ExportQuality.fromKey("unknown"))
    }

    @Test fun memoryRefusalDoesNotPreventByteExactOriginalExport(): Unit = runBlocking {
        val page = page(input(700, 500)); val original = repository.source(page).readBytes()
        assertTrue(runCatching { OriginalResolution.requireBudget(6000, 4000, 6000, 4000, 64L * 1024 * 1024) }.exceptionOrNull() is OriginalResolutionUnavailable)
        repository.rotate(page.id); repository.setFilter(page.id, ScanFilter.INVERT)
        val edited = database.documents().page(page.id)!!
        val copy = ByteArrayOutputStream().use { SourceExporter.write(edited, repository, it); it.toByteArray() }
        assertArrayEquals(original, copy); assertArrayEquals(original, repository.source(page).readBytes())
        assertEquals("image/png", SourceExporter.mimeType(repository.source(page)))
    }

    @Test fun untouchedPdfKeepsTextAndItsPageSourceCanBeSavedExactly(): Unit = runBlocking {
        val input = file("pdf")
        PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 14f)
                it.newLineAtOffset(30f, 500f); it.showText("NATIVE TEXT"); it.endText()
            }
            pdf.save(input)
        }
        val id = repository.create("PDF"); repository.importPdf(id, Uri.fromFile(input))
        val page = database.documents().pages(id).single()
        val output = file("pdf"); PdfExporter.write(listOf(page), repository, output)
        PDDocument.load(output).use { assertTrue(PDFTextStripper().getText(it).contains("NATIVE TEXT")) }
        val original = repository.source(page).readBytes()
        val copy = ByteArrayOutputStream().use { SourceExporter.write(page, repository, it); it.toByteArray() }
        assertArrayEquals(original, copy); assertEquals("application/pdf", SourceExporter.mimeType(repository.source(page)))
        // Edited-PDF raster dimensions are checked on Android; Robolectric's native PdfRenderer lacks FileDescriptor ownership APIs.
    }

    @Test fun processedExportDoesNotCopyPhotoLocationMetadata(): Unit = runBlocking {
        val source = input(800, 600, jpeg = true)
        ExifInterface(source).apply { setLatLong(1.0, 2.0); setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val page = page(source); val original = repository.source(page).readBytes()
        val encoded = jpeg(page)
        assertEquals(600 to 800, dimensions(encoded))
        val output = file("jpg").apply { writeBytes(encoded) }
        assertNull(ExifInterface(output).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNotNull(ExifInterface(repository.source(page)).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val copy = ByteArrayOutputStream().use { SourceExporter.write(page, repository, it); it.toByteArray() }
        assertArrayEquals(original, copy); assertArrayEquals(original, repository.source(page).readBytes())
    }
}
