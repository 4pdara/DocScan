package com.docscan.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.print.PageRange
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.data.*
import com.docscan.android.export.*
import com.docscan.android.processing.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GallerySharingDeviceTest {
    private lateinit var context: Context
    private lateinit var database: DocScanDatabase
    private lateinit var repository: DocumentRepository
    private lateinit var directory: File
    private val documents = mutableListOf<String>()
    private val gallery = mutableListOf<Uri>()
    private val shareDirectories = mutableListOf<File>()
    private val databaseName = "output-${UUID.randomUUID()}.db"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (Build.VERSION.SDK_INT <= 28) {
            for (permission in listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, permission)
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
            }
            assertEquals(Environment.MEDIA_MOUNTED, Environment.getExternalStorageState())
        }
        database = DocScanDatabase.open(context, databaseName); repository = DocumentRepository(context, database)
        directory = File(context.cacheDir, "output-test-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup(): Unit = runBlocking {
        gallery.forEach { context.contentResolver.delete(it, null, null) }
        shareDirectories.forEach { it.deleteRecursively() }; documents.forEach { repository.deleteDocument(it) }
        directory.deleteRecursively(); database.close(); context.deleteDatabase(databaseName); Unit
    }
    private suspend fun document() = repository.create("Device ${UUID.randomUUID()}").also { documents.add(it) }
    private suspend fun image(id: String): PageEntity {
        val file = File(directory, "source.png")
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return repository.importImage(id, Uri.fromFile(file), detectBounds = false)
    }
    @Test fun galleryWritesReadableJpegsWithEditsAndKeepsSource(): Unit = runBlocking {
        val id = document(); val page = image(id); val original = repository.source(page).readBytes()
        repository.rotate(page.id)
        val edited = database.documents().pages(id)
        val uris = GalleryExporter.write(context, "Gallery ${UUID.randomUUID()}", edited, repository).also { gallery.addAll(it) }
        assertEquals(1, uris.size)
        context.contentResolver.openInputStream(uris.single()).use { input ->
            val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
            assertEquals(120, bitmap.width); assertEquals(80, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(50, 40)) > 240); bitmap.recycle()
        }
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.query(uris.single(), arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.getString(1).startsWith("Pictures/DocScan"))
        }
        assertArrayEquals(original, repository.source(page).readBytes())
    }
    @Test fun interruptedGalleryBatchRollsBackOnlyItsOwnRows(): Unit = runBlocking {
        val id = document(); val page = image(id)
        val retained = GalleryExporter.write(context, "Retained", listOf(page), repository).also { gallery.addAll(it) }.single()
        val title = "Rollback ${UUID.randomUUID()}"
        var checkpoints = 0
        assertTrue(runCatching {
            GalleryExporter.write(context, title, listOf(page, page), repository) {
                if (++checkpoints == 2) throw kotlinx.coroutines.CancellationException("test cancellation")
            }
        }.isFailure)
        context.contentResolver.openInputStream(retained)!!.use { assertTrue(it.read() >= 0) }
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("$title%"), null)!!.use { assertEquals(0, it.count) }
    }
    @Test fun sharesUseReadableContentUrisAndReadOnlyGrants(): Unit = runBlocking {
        val id = document(); val page = image(id)
        val files = ShareExport.prepare(context, "Mixed", listOf(page), repository, ShareFormat.BOTH)
        shareDirectories.add(requireNotNull(files.first().parentFile))
        val intent = ShareExport.intent(context, files)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action); assertEquals("*/*", intent.type)
        assertEquals(2, intent.clipData!!.itemCount)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        files.forEachIndexed { index, file ->
            val uri = intent.clipData!!.getItemAt(index).uri
            assertEquals("content", uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(file.readBytes(), it.readBytes()) }
        }
        val single = ShareExport.intent(context, listOf(files.first()))
        assertEquals(Intent.ACTION_SEND, single.action); assertEquals("application/pdf", single.type)
    }
    private fun vectorPdf(): File {
        val file = File(directory, "vector.pdf")
        PDDocument().use { pdf ->
            for (word in listOf("FIRST", "SECOND", "THIRD")) {
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.setNonStrokingColor(1f, 0f, 0f); out.addRect(0f, 0f, 612f, 792f); out.fill()
                    out.setNonStrokingColor(1f, 1f, 1f); out.beginText(); out.setFont(PDType1Font.HELVETICA, 20f)
                    out.newLineAtOffset(40f, 700f); out.showText(word); out.endText()
                }
            }
            pdf.save(file)
        }
        return file
    }
    @Test fun importedPdfKeepsTextUntilImageEditedAndRendersEdits(): Unit = runBlocking {
        val id = document(); val input = vectorPdf(); val original = input.readBytes()
        assertEquals(3, repository.importPdf(id, Uri.fromFile(input)))
        val pages = database.documents().pages(id)
        val unedited = File(directory, "unedited.pdf")
        PdfExporter.write(pages, repository, unedited)
        PDDocument.load(unedited).use { assertTrue(PDFTextStripper().getText(it).contains("SECOND")) }
        repository.setFilter(pages[0].id, ScanFilter.INVERT)
        val edited = File(directory, "edited.pdf")
        PdfExporter.write(listOf(database.documents().page(pages[0].id)!!), repository, edited)
        PDDocument.load(edited).use { assertTrue(PDFTextStripper().getText(it).isBlank()) }
        val bitmap = PdfSources.render(edited, 600)
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue(Color.green(pixel) > 230 && Color.blue(pixel) > 230 && Color.red(pixel) < 25); bitmap.recycle()
        assertArrayEquals(original, input.readBytes())
    }
    @Test fun angledPageIsSavedAndExportedAsStraightPageWithoutTheDesk(): Unit = runBlocking {
        val id = document(); val input = File(directory, "angled.png")
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(25, 35, 45))
        val path = android.graphics.Path().apply {
            moveTo(252f, 160f); lineTo(336f, 184f); lineTo(528f, 688f); lineTo(72f, 616f); close()
        }
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawPath(path, android.graphics.Paint().apply { color = Color.WHITE })
        canvas.drawRect(265f, 360f, 340f, 440f, android.graphics.Paint().apply { color = Color.RED })
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val bytes = input.readBytes()
        val page = repository.importImage(id, Uri.fromFile(input), detectionMode = DetectionMode.ANGLED_DISTANCE)
        assertNotNull(page.crop)
        val jpeg = File(directory, "straight.jpg")
        jpeg.outputStream().use { JpegExporter.write(page, repository, it) }
        val output = requireNotNull(BitmapFactory.decodeFile(jpeg.path))
        try {
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            assertTrue("Only the page should remain", pixels.count { Color.red(it) < 100 } < pixels.size * .01f)
            assertTrue(output.width < 570 && output.height < 700)
        } finally { output.recycle() }
        val pdf = File(directory, "straight.pdf")
        PdfExporter.write(listOf(page), repository, pdf)
        val rendered = PdfSources.render(pdf, 700)
        try {
            val pixels = IntArray(rendered.width * rendered.height)
            rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
            assertTrue(pixels.count { Color.red(it) > 200 && Color.green(it) < 80 } > 500)
            assertTrue(pixels.count { Color.red(it) < 100 } < pixels.size * .01f)
        } finally { rendered.recycle() }
        assertArrayEquals(bytes, repository.source(page).readBytes())
    }
    @Test fun printerRangeWriterIncludesOnlyRequestedPages() {
        val source = vectorPdf(); val target = File(directory, "range.pdf")
        target.outputStream().use { PrintExport.writeRanges(source, it, arrayOf(PageRange(1, 1))) }
        PDDocument.load(target).use {
            assertEquals(1, it.numberOfPages)
            val text = PDFTextStripper().getText(it)
            assertTrue(text.contains("SECOND")); assertFalse(text.contains("FIRST")); assertFalse(text.contains("THIRD"))
        }
    }
}
