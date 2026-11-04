package com.docscan.android.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.processing.*
import com.docscan.android.export.PdfExporter
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentWorkflowTest {
    private lateinit var context: Context
    private lateinit var database: DocScanDatabase
    private lateinit var repository: DocumentRepository
    private val databaseName = "workflow-${UUID.randomUUID()}.db"
    private val documents = mutableListOf<String>()
    private val inputs = mutableListOf<File>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = DocScanDatabase.open(context, databaseName)
        repository = DocumentRepository(context, database)
    }
    @After fun cleanup(): Unit = runBlocking {
        documents.forEach { repository.deleteDocument(it) }
        inputs.forEach { it.delete() }; database.close(); context.deleteDatabase(databaseName); Unit
    }
    private suspend fun document(title: String) = repository.create(title).also { documents.add(it) }
    private suspend fun page(id: String): PageEntity {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.png").also { inputs.add(it) }
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return repository.importImage(id, Uri.fromFile(file), detectBounds = false)
    }
    private fun pdf(): File {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.pdf").also { inputs.add(it) }
        PDDocument().use { pdf ->
            for (word in listOf("FIRST", "SECOND")) {
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font.HELVETICA, 18f); out.newLineAtOffset(40f, 500f); out.showText(word); out.endText()
                }
            }
            pdf.save(file)
        }
        return file
    }
    @Test fun mergedDocumentsKeepOrderEditsAndIndependentSources(): Unit = runBlocking {
        val a = document("A"); val b = document("B")
        val first = page(a); val second = page(b)
        repository.rotate(second.id); repository.setFilter(second.id, ScanFilter.CLEAN_TEXT)
        repository.setAdjustments(second.id, ImageAdjustments(sharpness = 45))
        val merged = repository.mergeDocuments(listOf(b, a), "Merged").also { documents.add(it) }
        val pages = database.documents().pages(merged)
        assertEquals(listOf(0, 1), pages.map { it.position }); assertEquals(90, pages[0].rotation)
        assertEquals(45, pages[0].sharpness); assertEquals(ScanFilter.CLEAN_TEXT.name, pages[0].filter)
        assertNotEquals(second.sourceFile, pages[0].sourceFile)
        val bytes = repository.source(first).readBytes()
        repository.deleteDocument(a); repository.deleteDocument(b)
        assertTrue(pages.all { repository.source(it).exists() })
        assertArrayEquals(bytes, repository.source(pages[1]).readBytes())
    }
    @Test fun mergeFailureRollsBackCopiesAndDocument(): Unit = runBlocking {
        val a = document("A"); val b = document("B"); page(a); val missing = page(b)
        repository.source(missing).delete()
        val before = File(context.filesDir, "originals").listFiles().orEmpty().map { it.name }.toSet()
        assertTrue(runCatching { repository.mergeDocuments(listOf(a, b), "Failed") }.isFailure)
        assertEquals(before, File(context.filesDir, "originals").listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(documents.size, database.documents().observeDocuments().first().size)
    }
    @Test fun invalidBatchChangesNothing(): Unit = runBlocking {
        val id = document("Selection"); val p = page(id)
        assertTrue(runCatching { repository.rotatePages(id, listOf(p.id, "missing")) }.isFailure)
        assertEquals(0, database.documents().page(p.id)!!.rotation)
        assertTrue(runCatching { repository.selectedPages(listOf(p), emptyList()) }.isFailure)
    }
    @Test fun batchChangesOnlySelectedPagesAndCopiesCurrentFilter(): Unit = runBlocking {
        val id = document("Batch"); val pages = List(3) { page(id) }
        repository.filterPages(id, listOf(pages[0].id, pages[2].id), ScanFilter.INVERT, 38)
        val changed = database.documents().pages(id)
        assertEquals(listOf("INVERT", "ORIGINAL", "INVERT"), changed.map { it.filter })
        repository.setFilter(pages[0].id, ScanFilter.CLEAN_COLOR)
        repository.setFilterStrength(pages[0].id, 23)
        assertEquals(3, repository.copyFilterToDocument(pages[0].id))
        assertTrue(database.documents().pages(id).all { it.filter == "CLEAN_COLOR" && it.filterStrength == 23 })
    }
    @Test fun batchDeletionRepairsPositionsAndPreservesOtherSources(): Unit = runBlocking {
        val id = document("Delete"); val pages = List(3) { page(id) }
        repository.deletePages(id, listOf(pages[0].id, pages[2].id))
        assertEquals(listOf(pages[1].id), database.documents().pages(id).map { it.id })
        assertEquals(0, database.documents().pages(id).single().position)
        assertTrue(repository.source(pages[1]).exists()); assertFalse(repository.source(pages[0]).exists())
    }
    @Test fun pdfImportAndExportRetainTextOrderAndOriginal(): Unit = runBlocking {
        val id = document("PDF"); val input = pdf(); val bytes = input.readBytes()
        assertEquals(2, repository.importPdf(id, Uri.fromFile(input)))
        val pages = database.documents().pages(id)
        assertTrue(pages.all { it.sourceFile.endsWith(".pdf") })
        val output = File(context.cacheDir, "${UUID.randomUUID()}.pdf").also { inputs.add(it) }
        PdfExporter.write(pages.reversed(), repository, output)
        PDDocument.load(output).use { exported ->
            assertEquals(2, exported.numberOfPages)
            val text = PDFTextStripper().getText(exported)
            assertTrue(text.indexOf("SECOND") < text.indexOf("FIRST"))
        }
        assertArrayEquals(bytes, input.readBytes())
    }
    @Test fun excessivePdfPageCountDoesNotAppendToExistingDocument(): Unit = runBlocking {
        val id = document("Limit"); val existing = page(id)
        val input = File(context.cacheDir, "${UUID.randomUUID()}.pdf").also { inputs.add(it) }
        PDDocument().use { pdf -> repeat(PdfSources.MAX_PAGES + 1) { pdf.addPage(PDPage()) }; pdf.save(input) }
        assertTrue(runCatching { repository.importPdf(id, Uri.fromFile(input)) }.isFailure)
        assertEquals(listOf(existing.id), database.documents().pages(id).map { it.id })
        assertTrue(repository.source(existing).exists())
        assertTrue(File(context.filesDir, "pending").listFiles().orEmpty().isEmpty())
    }
    @Test fun brokenPdfLeavesNoPublishedPageOrPendingFiles(): Unit = runBlocking {
        val id = document("Bad PDF")
        val broken = File(context.cacheDir, "${UUID.randomUUID()}.pdf").apply { writeText("broken") }.also { inputs.add(it) }
        assertTrue(runCatching { repository.importPdf(id, Uri.fromFile(broken)) }.isFailure)
        assertTrue(database.documents().pages(id).isEmpty())
        assertTrue(File(context.filesDir, "pending").listFiles().orEmpty().isEmpty())
    }
}
