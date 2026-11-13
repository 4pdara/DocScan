package com.docscan.rebuild

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.*
import com.docscan.rebuild.model.*
import com.docscan.rebuild.tools.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ToolsAndDraftTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @get:Rule val cameraPermission=androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun fixture():File {
        val bitmap=Bitmap.createBitmap(640,880,Bitmap.Config.ARGB_8888);Canvas(bitmap).apply {drawColor(Color.WHITE);drawText("SOURCE 123",40f,120f,Paint().apply{color=Color.BLACK;textSize=40f})}
        return File(context.cacheDir,"tools-${System.nanoTime()}.png").also {file->try{file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    @Test fun draftAndPermanentDeleteKeepSharedOriginalsAndExistingCapturePages():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val store=ScanStore(context,db);val file=fixture()
        try {
            val draft=store.create("Draft",draft=true);val p=store.captured(draft.id,file)
            assertTrue(store.dao.library().first().isEmpty());assertEquals(draft.id,store.dao.drafts().first().single().book.id)
            store.discardCapture(draft.id,listOf(p.id));assertNull(store.dao.book(draft.id));assertFalse(store.source(p).exists())
            val book=store.create("Saved");val first=store.captured(book.id,file);val second=store.captured(book.id,file)
            store.discardCapture(book.id,listOf(second.id));assertEquals(listOf(first.id),store.dao.snapshot(book.id).map{it.id});assertEquals(hash(file),hash(store.source(first)))
            val copy=store.copyPages("Copy",listOf(first.id));val shared=store.dao.snapshot(copy.id).single()
            try{store.deleteForever(setOf(book.id));fail("Active book destroyed")}catch(_:IllegalArgumentException){}
            store.trash(setOf(book.id));store.deleteForever(setOf(book.id));assertTrue(store.source(shared).exists());assertNull(store.dao.book(book.id))
            store.trash(setOf(copy.id));store.deleteForever(setOf(copy.id));assertFalse(store.source(shared).exists())
        }finally{store.dao.allPages().forEach{store.source(it).delete()};file.delete();db.close()}
    }
    @Test fun decorationsOfficePdfImportEncryptionAndBackupRoundTrip():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val store=ScanStore(context,db);val file=fixture();val outputs=mutableListOf<File>()
        try{
            val book=store.create("Tools");val p=store.captured(book.id,file);val digest=hash(store.source(p))
            val d=PageDecorations(watermark="דוגמה",timestamp="2026-11-13 15:00",signature=listOf(InkStroke(listOf(Vertex(.2f,.8f),Vertex(.7f,.85f)))))
            val r=p.edit().copy(decorations=d);assertEquals(r,RecipeCodec.decode(RecipeCodec.encode(r)));store.save(p,r)
            val bitmap=ImagePipeline.render(store.source(p),r);assertEquals(640,bitmap.width);assertEquals(880,bitmap.height);bitmap.recycle();assertEquals(digest,hash(store.source(p)))
            val current=store.dao.page(p.id)!!;store.dao.recognized(p.id,"שלום\t123\n=1+2\t<&>",current.recipe)
            val formats=listOf(OfficeExport(store).word(book.id),OfficeExport(store).excel(book.id),OfficeExport(store).powerpoint(book.id));outputs+=formats
            for(output in formats)ZipFile(output).use{zip -> assertNotNull(zip.getEntry("[Content_Types].xml"));val entries=zip.entries();while(entries.hasMoreElements()){val entry=entries.nextElement();if(entry.name.endsWith(".xml")||entry.name.endsWith(".rels"))zip.getInputStream(entry).use{DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)}}}
            ZipFile(formats[1]).use {zip->val text=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText();assertTrue(text.contains("t=\"inlineStr\""));assertFalse(text.contains("<f>"));assertTrue(text.contains("=1+2"))}
            val pdf=DocumentOutput(context,store).pdf(book.id);outputs+=pdf
            val imported=PdfTools(store).import(Uri.fromFile(pdf),150);val importedPage=store.dao.snapshot(imported.id).single();assertEquals(hash(pdf),hash(store.originalPdf(importedPage)!!))
            val archive=ByteArrayOutputStream();LibraryBackup(store).export(archive);LibraryBackup(store).restore(ByteArrayInputStream(archive.toByteArray()))
            val restored=store.dao.allPages().filter{it.pdfSource!=null};assertEquals(2,restored.size);restored.forEach{assertEquals(hash(pdf),hash(store.originalPdf(it)!!))}
            val long=PdfTools(store).longImage(book.id);outputs+=long;val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(long.path,bounds);assertEquals(640,bounds.outWidth);assertEquals(880,bounds.outHeight)
            val locked=PdfTools(store).lock(pdf,"test-password");outputs+=locked;PDFBoxResourceLoader.init(context)
            try{PDDocument.load(locked);fail("Password not required")}catch(_:com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException){}
            PDDocument.load(locked,"test-password").use{assertEquals(1,it.numberOfPages)}
            val exported=File(context.getExternalFilesDir(null),"tool-exports").apply{mkdirs()};formats.forEach{it.copyTo(File(exported,"sample.${it.extension}"),overwrite=true)}
        }finally{outputs.forEach{it.delete()};store.dao.allPages().forEach{store.source(it).delete();store.originalPdf(it)?.delete()};file.delete();db.close()}
    }
    @Test fun codeAndCandidateCounterUseLocalPixels(){
        val matrix=QRCodeWriter().encode("DOCSCAN-123",BarcodeFormat.QR_CODE,400,400)
        val bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);for(y in 0..399)for(x in 0..399)bitmap.setPixel(x,y,if(matrix[x,y])Color.BLACK else Color.WHITE)
        try{assertEquals("DOCSCAN-123",CodeReader.read(bitmap))}finally{bitmap.recycle()}
        val dots=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);Canvas(dots).apply{drawColor(Color.WHITE);listOf(80f,200f,320f).forEach{drawCircle(it,200f,20f,Paint().apply{color=Color.BLACK})}}
        try{assertEquals(3,PageEffects.countCandidates(dots,true,.002).size)}finally{dots.recycle()}
    }
    @Test fun blankCaptureBackRequiresConfirmationAndCreatesNoDocument(){
        val store=(compose.activity.application as ScanApplication).store
        val before=runBlocking{store.dao.allBooks().map{it.id}.toSet()}
        compose.onNodeWithTag("new-scan").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("capture-batch").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("capture-auto").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.discard_scan_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.continue_scan)).performClick()
        compose.onNodeWithTag("capture-batch").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack();compose.onNodeWithTag("discard-scan-confirm").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("home-navigation").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(before,runBlocking{store.dao.allBooks().map{it.id}.toSet()})
    }
    @Test fun splittingAnUnfinishedScanKeepsBothPagesUncommittedUntilSave(){
        val store=(compose.activity.application as ScanApplication).store
        val draft=runBlocking {val b=store.create("Split draft ${System.nanoTime()}",draft=true);val f=fixture();try{store.captured(b.id,f)}finally{f.delete()};b}
        val original=runBlocking{store.dao.snapshot(draft.id).single()}
        try {
        compose.waitUntil(10000){compose.onAllNodesWithText(draft.title).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("draft-recovery").assertIsDisplayed()
        compose.onNodeWithText(draft.title).assertIsDisplayed().performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("page-tools").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("page-tools").performClick();compose.onNodeWithText(context.getString(R.string.split_book)).performClick()
        compose.onNodeWithText(context.getString(R.string.split_apply)).performClick()
        compose.waitUntil(10000){runBlocking{store.dao.snapshot(draft.id).size==2}}
        assertTrue(runBlocking{store.dao.book(draft.id)!!.draft})
        assertTrue(runBlocking{store.dao.library().first().none{it.book.id==draft.id}})
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("save-edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        androidx.test.espresso.Espresso.pressBack();compose.onNodeWithTag("discard-scan-confirm").performClick()
        compose.waitUntil(10000){runBlocking{store.dao.book(draft.id)==null} && !store.source(original).exists()}
        } finally { runCatching { screenshot("draft-split-final");compose.onRoot().printToLog("DraftSplit") } }
    }

    @Test fun permanentDeletionRequiresExplicitConfirmation(){
        val store=(compose.activity.application as ScanApplication).store
        val book=runBlocking {val b=store.create("Delete confirmation ${System.nanoTime()}");val f=fixture();try{store.captured(b.id,f)}finally{f.delete()};store.trash(setOf(b.id));b}
        compose.onNodeWithContentDescription(context.getString(R.string.trash)).performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("select-${book.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("select-${book.id}").performClick();compose.onNodeWithTag("delete-forever").performClick()
        compose.onNodeWithText(context.getString(R.string.delete_forever_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNotNull(runBlocking{store.dao.book(book.id)})
        compose.onNodeWithTag("delete-forever").performClick();compose.onNodeWithTag("delete-forever-confirm").performClick()
        compose.waitUntil(10000){runBlocking{store.dao.book(book.id)==null}}
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val image=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder=File(compose.activity.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        try{File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
    @Test fun homeSearchIsCompactAndAllToolCategoriesAreReachable(){
        val search=compose.onNodeWithTag("home-search").fetchSemanticsNode().boundsInRoot
        val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(search.height<root.height*.08f)
        screenshot("tools-compact-home")
        compose.onNodeWithTag("home-tab-tools").performClick()
        compose.onNodeWithTag("categorized-tools").assertIsDisplayed()
        screenshot("tools-categories-top")
        ToolGroup.entries.forEach{group->compose.onNodeWithTag("categorized-tools").performScrollToNode(hasText(context.getString(group.title)));compose.onNodeWithText(context.getString(group.title)).assertIsDisplayed()}
        screenshot("tools-categories-bottom")
        compose.onNodeWithTag("categorized-tools").performScrollToNode(hasTestTag("tool-SIGNATURE"));compose.onNodeWithTag("tool-SIGNATURE").performClick();compose.onNodeWithTag("tool-workspace").assertIsDisplayed()
    }
}
