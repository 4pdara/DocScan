package com.docscan.rebuild

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.*
import com.docscan.rebuild.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.security.MessageDigest

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class DocumentOcrTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun fixture(): File {
        val bitmap = Bitmap.createBitmap(1600,900,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=90f }
            drawText("DOCUMENT SCANNING",100f,240f,paint)
            drawText("READABLE BOOK 12345",100f,400f,paint)
        }
        return File(context.cacheDir,"ocr-batch-${System.nanoTime()}.png").also {
            try { it.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) } }
            finally { bitmap.recycle() }
        }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun batchCancelResumeKeepsManualCorrectionsAndFullOriginals(): Unit = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build(); val store=ScanStore(context,db)
        val file=fixture()
        try {
            val book=store.create("Batch"); val pages=(0..2).map { store.captured(book.id,file) }
            val manual="תיקון ידני 50%_\\ ספר"; store.dao.recognized(pages[0].id,manual,pages[0].recipe)
            store.save(pages[0], pages[0].edit())
            assertEquals("Saving an unchanged legacy recipe must keep corrected OCR", manual, store.dao.page(pages[0].id)!!.currentText())
            val token=OcrRequest()
            try {
                OfflineOcr(store).recognizeBook(book.id,OcrOptions("eng",OcrLayout.BLOCK),request=token) {
                    if(it.page==2 && it.percent==100) token.cancel()
                }
                fail("Cancellation must stop the batch")
            } catch(_: CancellationException) {}
            assertEquals(manual,store.dao.page(pages[0].id)!!.currentText())
            assertTrue(store.dao.page(pages[1].id)!!.currentText()!!.contains("DOCUMENT"))
            assertNull(store.dao.page(pages[2].id)!!.currentText())
            val result=OfflineOcr(store).recognizeBook(book.id,OcrOptions("eng",OcrLayout.COLUMN))
            assertEquals(1,result.recognized); assertEquals(2,result.retained); assertTrue(result.failedPages.isEmpty())
            val text=StringWriter();DocumentOutput(context,store).text(book.id,text)
            val exported=text.toString().split("\n\n\u000c\n\n")
            assertEquals(3,exported.size);assertEquals(manual,exported[0]);assertTrue(exported[2].contains("DOCUMENT"))
            pages.forEach { assertEquals(hash(file),hash(store.source(it))) }
        } finally { store.dao.allPages().forEach { store.source(it).delete() };file.delete();db.close() }
    }

    @Test fun literalSearchTracksCurrentEditsAndExportRejectsMissingPages(): Unit = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val store=ScanStore(context,db);val file=fixture()
        try {
            val book=store.create("Name without search terms");val page=store.captured(book.id,file)
            val other=store.create("Other");val plain=store.captured(other.id,file)
            store.dao.recognized(page.id,"שלום 50%_\\ WORLD",page.recipe)
            store.dao.recognized(plain.id,"plain text",plain.recipe)
            for(query in listOf("שלום","world","%","_","\\")) assertEquals(listOf(book.id),store.dao.textMatches(TextSearch.pattern(query)).first())
            store.move(book.id,page.id,1)
            store.save(page,page.edit().copy(brightness=3f))
            assertTrue(store.dao.textMatches(TextSearch.pattern("שלום")).first().isEmpty())
            try {DocumentOutput(context,store).text(book.id,StringWriter());fail("Stale text exported")}catch(_:IllegalArgumentException){}
            val changed=store.dao.page(page.id)!!
            assertEquals(0,store.dao.recognized(page.id,"stale write",page.recipe))
            store.dao.recognized(page.id,"",changed.recipe) // Blank pages count as processed.
            assertEquals("",store.dao.page(page.id)!!.currentText())
            val missing=store.captured(book.id,file);store.source(missing).delete()
            val result=OfflineOcr(store).recognizeBook(book.id,OcrOptions("eng"))
            assertEquals(listOf(2),result.failedPages);assertEquals(1,result.retained)
            assertEquals(hash(file),hash(store.source(page)))
        } finally { store.dao.allPages().forEach { store.source(it).delete() };file.delete();db.close() }
    }

    @Test fun documentTextScreenRetainsTextAndSavesCorrections(): Unit {
        val store=(compose.activity.application as ScanApplication).store;val file=fixture();val title="Text workflow ${System.nanoTime()}"
        val pages=runBlocking {val b=store.create(title);(0..1).map { val p=store.captured(b.id,file);store.dao.recognized(p.id,"MANUAL ${it+1}",p.recipe);p }}
        try {
            compose.onNodeWithTag("home-search").performTextInput(title)
            val document=hasText(title) and !hasSetTextAction()
            compose.waitUntil(15000) { compose.onAllNodes(document).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(document).performClick();compose.onNodeWithTag("export-menu").performClick()
            compose.onNodeWithTag("document-ocr-open").performScrollTo().performClick()
            compose.onNodeWithTag("document-ocr").assertIsDisplayed()
            compose.onNodeWithTag("ocr-document-export").assertIsEnabled()
            compose.onNodeWithTag("ocr-layout-BLOCK").performScrollTo().performClick()
            compose.onNodeWithTag("ocr-document-start").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithTag("ocr-batch-result").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("MANUAL 1",runBlocking {store.dao.page(pages[0].id)!!.currentText()})
            compose.onNodeWithTag("ocr-page-${pages[0].id}").performScrollTo().performClick()
            compose.onNodeWithTag("recognized-text").performTextReplacement("EDITED שלום")
            compose.onNodeWithText(compose.activity.getString(R.string.save)).performClick()
            compose.waitUntil(15000) { runBlocking {store.dao.page(pages[0].id)!!.currentText()=="EDITED שלום"} }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
                try {val dir=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()};File(dir,"ocr-text-editor.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }}finally{image.recycle()}
            }
        } finally { file.delete() }
    }
}
