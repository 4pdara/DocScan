package com.docscan.rebuild

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RebuildFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val cameraPermission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fixture(name: String = "fixture.jpg"): File {
        val bitmap = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(40, 45, 50))
        val paint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(180f, 220f, 1620f, 2180f, paint)
        paint.color = Color.BLACK; paint.textSize = 28f
        repeat(35) { canvas.drawText("DocScan source ${it + 1} 0123456789", 250f, 350f + it * 45f, paint) }
        return File(context.cacheDir, name).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle() }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage()
        val output = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888).also { result ->
            File(output, "$name.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }; result.recycle()
        }
    }

    @Test fun cameraCapturesDurablePageAndDoneOpensDocument() {
        val store = (compose.activity.application as ScanApplication).store
        val existing = runBlocking { store.dao.library().first().map { it.book.id }.toSet() }
        compose.onNodeWithTag("new-scan").performClick()
        val shutter = hasContentDescription(compose.activity.getString(R.string.capture)) and isEnabled()
        compose.waitUntil(30000) { compose.onAllNodes(shutter).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable()).performClick()
        screenshot("camera")
        compose.onNode(shutter).performClick()
        var captured: Sheet? = null
        compose.waitUntil(30000) {
            captured = runBlocking { store.dao.library().first().firstOrNull { it.book.id !in existing }?.let { store.dao.snapshot(it.book.id).firstOrNull() } }
            captured != null
        }
        val page = requireNotNull(captured)
        assertTrue(store.source(page).length() > 0); assertTrue(minOf(page.width, page.height) >= 480 && maxOf(page.width, page.height) >= 640)
        compose.waitUntil(30000) { compose.onAllNodes(hasText(compose.activity.getString(R.string.done)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.onNodeWithTag("document-pages").assertIsDisplayed()
    }

    @Test fun sourceRecipeAndOrderSurviveDatabaseReopen(): Unit = runBlocking {
        @Suppress("DEPRECATION") val packageInfo = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(packageInfo.requestedPermissions.orEmpty().contains(android.Manifest.permission.INTERNET))
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val name = "test-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        var store = ScanStore(context, db); val book = store.create("Persistence")
        val input = fixture(); val digest = hash(input)
        val first = store.captured(book.id, input); val second = store.captured(book.id, input)
        val recipe = EditRecipe(turns = 1, treatment = Treatment.INK, brightness = 9f)
        store.save(first, recipe); store.move(book.id, second.id, -1)
        assertEquals(digest, hash(store.source(first)))
        db.close(); db = Room.databaseBuilder(context, ScanDatabase::class.java, name).build(); store = ScanStore(context, db)
        val restored = store.dao.snapshot(book.id)
        assertEquals(listOf(second.id, first.id), restored.map { it.id })
        assertEquals(recipe, restored.last().edit()); assertEquals(digest, hash(store.source(restored.last())))
        restored.forEach { store.source(it).delete() }; input.delete(); db.close(); context.deleteDatabase(name)
    }

    @Test fun realNativePipelineDetectsCropsAndKeepsFullResolution() {
        val file = fixture("processing.jpg")
        try {
            val detected = requireNotNull(ImagePipeline.detect(file))
            assertTrue(detected.valid())
            assertEquals(.1f, detected.points[0].x, .035f); assertEquals(.092f, detected.points[0].y, .035f)
            val original = ImagePipeline.render(file, EditRecipe())
            assertEquals(1800, original.width); assertEquals(2400, original.height); original.recycle()
            for (treatment in Treatment.entries) {
                val result = ImagePipeline.render(file, EditRecipe(frame = detected, turns = 1, treatment = treatment, brightness = -12f, contrast = .8f))
                assertTrue(result.width > 1800); assertTrue(result.height > 1300)
                assertEquals(255, Color.alpha(result.getPixel(result.width / 2, result.height / 2)))
                result.recycle()
            }
        } finally { file.delete() }
    }

    @Test fun pdfContainsAllPagesAndLeavesMastersUntouched(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val book = store.create("PDF test"); val input = fixture("pdf-source.jpg")
        val first = store.captured(book.id, input); val second = store.captured(book.id, input); val digest = hash(input)
        store.save(second, EditRecipe(turns = 1, treatment = Treatment.GRAY))
        val pdf = DocumentOutput(context, store).pdf(book.id)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
            assertEquals(2, renderer.pageCount)
            renderer.openPage(0).use { assertTrue(it.height > it.width) }
            renderer.openPage(1).use { assertTrue(it.width > it.height) }
        } }
        assertEquals(digest, hash(store.source(first))); assertEquals(digest, hash(store.source(second)))
        pdf.delete(); input.delete(); store.source(first).delete(); store.source(second).delete(); db.close()
    }

    @Test fun invalidCropCannotOverwriteSourceOrSavedRecipe(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val book = store.create("Invalid edit"); val input = fixture("invalid.jpg"); val page = store.captured(book.id, input)
        val invalid = EditRecipe(frame = Frame(listOf(Vertex(0f, 0f))))
        try { store.save(page, invalid); fail("Expected invalid crop rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(EditRecipe(), store.dao.page(page.id)!!.edit()); assertEquals(hash(input), hash(store.source(page)))
        store.source(page).delete(); input.delete(); db.close()
    }

    @Test fun editorHasInlineFiltersAndActionsAboveSystemNavigation() {
        val language = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("locale", "en")
        assertEquals(if (language == "he") android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR, compose.activity.resources.configuration.layoutDirection)
        val store = (compose.activity.application as ScanApplication).store
        val title = "UI fixture ${System.nanoTime()}"
        val page = runBlocking { val book = store.create(title); store.captured(book.id, fixture("ui.jpg")) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        screenshot("library")
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        compose.onNodeWithTag("filter-strip").assertIsDisplayed()
        compose.waitUntil(20000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
        screenshot("editor")
        compose.onNodeWithText(compose.activity.getString(R.string.gray)).performClick()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("save-edit").fetchSemanticsNode().boundsInRoot
        var navBottom = 0
        compose.runOnUiThread { navBottom = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0 }
        val toolbar = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("Save remains visible", save.top >= 0 && save.bottom < root.bottom - navBottom)
        assertTrue("App toolbar reserves navigation inset", toolbar.bottom <= root.bottom - navBottom + 1)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("filter-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save-edit").performClick()
        compose.waitUntil(10000) { runBlocking { store.dao.page(page.id)?.edit()?.treatment == Treatment.GRAY } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
        screenshot("document")
    }
}
