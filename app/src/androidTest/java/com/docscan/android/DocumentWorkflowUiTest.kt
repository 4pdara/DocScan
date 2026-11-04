package com.docscan.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.data.PageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class DocumentWorkflowUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as DocScanApplication
    private val prefix = "Workflow ${UUID.randomUUID()}"
    private val inputs = mutableListOf<File>()
    private fun text(id: Int) = ui.activity.getString(id)
    private fun ready() { ui.waitForIdle() }
    private fun create(suffix: String, count: Int): Pair<String, List<PageEntity>> = runBlocking {
        val id = app.repository.create("$prefix $suffix")
        val file = File(ui.activity.cacheDir, "${UUID.randomUUID()}.png").also { inputs.add(it) }
        val bitmap = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        id to List(count) { app.repository.importImage(id, Uri.fromFile(file), detectBounds = false) }
    }
    private fun open(suffix: String) {
        ui.waitUntil(15_000) { ui.onAllNodesWithText("$prefix $suffix").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("$prefix $suffix").performScrollTo().performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithText(ui.activity.getString(R.string.page_label, 1)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun selectPages() {
        ui.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        ui.onNodeWithText(text(R.string.select_pages)).performClick()
    }
    private fun screenshot(name: String) {
        ready()
        val resolver = ui.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScanTest")
        })!!
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @After fun cleanup(): Unit = runBlocking {
        app.database.documents().observeDocuments().first().filter { it.document.title.startsWith(prefix) }.forEach { app.repository.deleteDocument(it.document.id) }
        inputs.forEach { it.delete() }
        val resolver = ui.activity.contentResolver
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null)?.use { cursor ->
            val uris = mutableListOf<Uri>()
            while (cursor.moveToNext()) uris.add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
            uris.forEach { resolver.delete(it, null, null) }
        }; Unit
    }
    @Test fun selectedPagesSurviveRecreationAndGalleryExportsOnlySelection() {
        create("Gallery", 2); open("Gallery"); selectPages()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.page_select_desc, 1)).performClick()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription(ui.activity.getString(R.string.page_select_desc, 1)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.page_select_desc, 1)).assertIsOn()
        screenshot("workflow-selected-pages.png")
        ui.onNodeWithText(text(R.string.save_gallery)).performClick()
        ui.waitUntil(20_000) { ui.onAllNodesWithText(ui.activity.getString(R.string.saved_gallery, 1)).fetchSemanticsNodes().isNotEmpty() }
        ui.activity.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0", arrayOf("$prefix%"), null)!!.use { assertEquals(1, it.count) }
    }
    @Test fun librarySelectionCreatesIndependentMergedDocument() {
        val a = create("A", 1); val b = create("B", 1)
        runBlocking {
            app.repository.rotate(a.second.single().id)
            app.repository.setFilter(b.second.single().id, com.docscan.android.processing.ScanFilter.GRAYSCALE)
        }
        ui.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        ui.onNodeWithText(text(R.string.select_documents)).performClick()
        for (suffix in listOf("B", "A")) {
            ui.waitUntil(15_000) { ui.onAllNodesWithText("$prefix $suffix").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("$prefix $suffix").performScrollTo().performClick()
        }
        ui.onNodeWithText(text(R.string.merge_documents)).performClick()
        ui.onNodeWithText(text(R.string.document_name)).performTextInput("$prefix merged")
        screenshot("workflow-merge-order.png")
        ui.onNodeWithText(text(R.string.create_merged)).performClick()
        ui.waitUntil(15_000) { runBlocking { app.database.documents().observeDocuments().first().any { it.document.title == "$prefix merged" } } }
        val merged = runBlocking { app.database.documents().observeDocuments().first().first { it.document.title == "$prefix merged" }.document }
        val pages = runBlocking { app.database.documents().pages(merged.id) }
        assertEquals(2, pages.size)
        assertEquals("GRAYSCALE", pages[0].filter); assertEquals(90, pages[1].rotation)
        assertNotEquals(b.second.single().sourceFile, pages[0].sourceFile)
        assertNotEquals(a.second.single().sourceFile, pages[1].sourceFile)
        ui.waitUntil(10_000) { ui.onAllNodesWithText(ui.activity.getString(R.string.page_label, 2)).fetchSemanticsNodes().isNotEmpty() }
        screenshot("workflow-merged-document.png")
    }
    @Test fun batchFilterEditsSelectionAndShareSheetOffersAllFormats() {
        val created = create("Batch", 3); open("Batch"); selectPages()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.page_select_desc, 1)).performClick()
        ui.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.page_select_desc, 3)).performClick()
        ui.onNodeWithContentDescription(text(R.string.batch_filters)).performClick()
        ui.onNodeWithContentDescription(text(R.string.filters_label)).performScrollToNode(hasText(text(R.string.filter_invert)))
        ui.onNodeWithText(text(R.string.filter_invert)).performClick()
        ui.onNodeWithText(text(R.string.apply)).performClick()
        ui.waitUntil(15_000) { runBlocking { app.database.documents().pages(created.first).map { it.filter } == listOf("INVERT", "ORIGINAL", "INVERT") } }
        ui.onNodeWithContentDescription(text(R.string.share_options)).performClick()
        ui.onNodeWithText(text(R.string.share_pdf)).assertIsDisplayed()
        ui.onNodeWithText(text(R.string.share_images)).assertIsDisplayed()
        ui.onNodeWithText(text(R.string.share_both)).assertIsDisplayed()
        screenshot("workflow-share-options.png")
    }
}
