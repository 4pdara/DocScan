package com.docscan.android

import android.Manifest
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.processing.DetectionMode
import com.docscan.android.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@SdkSuppress(minSdkVersion = 29)
class DesignWorkflowDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as DocScanApplication
    private val model get() = ViewModelProvider(ui.activity)[DocScanViewModel::class.java]
    private val owned = mutableListOf<String>()
    private val inputs = mutableListOf<File>()
    private lateinit var activityConfig: Configuration
    private lateinit var appConfig: Configuration
    private fun text(id: Int) = ui.activity.getString(id)

    @Suppress("DEPRECATION")
    @Before fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ui.activity.packageName, Manifest.permission.CAMERA)
        ui.activity.getSharedPreferences("scan-preferences", 0).edit().clear().putBoolean("automatic", false).commit()
        ui.waitUntil(15_000) { !model.busy.value }
        val vm = model
        ui.runOnUiThread {
            activityConfig = Configuration(ui.activity.resources.configuration); appConfig = Configuration(app.resources.configuration)
            val he = Configuration(activityConfig).apply { setLocale(Locale.forLanguageTag("he")) }
            ui.activity.resources.updateConfiguration(he, ui.activity.resources.displayMetrics)
            app.resources.updateConfiguration(he, app.resources.displayMetrics)
            ui.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides ui.activity,
                    LocalContext provides ui.activity, LocalConfiguration provides he) { DocScanTheme { DocScanApp(vm) } }
            }
        }
    }
    @Suppress("DEPRECATION")
    @After fun cleanup() {
        runBlocking { owned.forEach { app.repository.deleteDocument(it) } }
        inputs.forEach { it.delete() }
        ui.runOnUiThread {
            ui.activity.resources.updateConfiguration(activityConfig, ui.activity.resources.displayMetrics)
            app.resources.updateConfiguration(appConfig, app.resources.displayMetrics)
        }
    }
    private fun input(): File {
        val file = File(ui.activity.cacheDir, "design-${UUID.randomUUID()}.png").also { inputs.add(it) }
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(image); val paint = Paint().apply { color = Color.DKGRAY; textSize = 28f }
        canvas.drawText("DocScan document preview", 50f, 90f, paint)
        for (y in 150..650 step 48) canvas.drawLine(50f, y.toFloat(), 540f, y.toFloat(), paint)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        return file
    }
    private fun create(title: String): String = runBlocking {
        val id = app.repository.create(title).also { owned.add(it) }
        repeat(2) { app.repository.importImage(id, Uri.fromFile(input()), detectBounds = false) }; id
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val resolver = ui.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "workflow-design-${DesignConfig.number}-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScanTest")
        })!!
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun preview(): PreviewView? {
        var result: PreviewView? = null
        fun visit(view: View) {
            if (view is PreviewView && view.isAttachedToWindow) result = view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        ui.runOnUiThread { visit(ui.activity.window.decorView) }
        return result
    }
    private fun previewMode(): Int? {
        val view = preview(); var mode: Int? = null
        ui.runOnUiThread { mode = view?.controller?.imageCaptureMode }
        return mode
    }

    @Test fun emptyNewScanBackReturnsToLibraryWithoutChangingAnExistingDocument() {
        val title = "Existing ${UUID.randomUUID()}"; val id = create(title)
        ui.waitUntil(15_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(title).performScrollTo().performClick()
        ui.onNodeWithContentDescription(text(R.string.back)).performClick()
        val before = runBlocking { app.database.documents().allDocuments().map { it.id }.toSet() }
        ui.onNodeWithText(text(R.string.new_scan)).performClick()
        ui.onNodeWithTag("camera-screen").assertIsDisplayed()
        assertNull(model.documentId)
        ui.onNodeWithContentDescription(text(R.string.back)).performClick()
        ui.onNodeWithText(text(R.string.new_scan)).assertIsDisplayed()
        assertEquals("home", model.screen)
        assertEquals(before, runBlocking { app.database.documents().allDocuments().map { it.id }.toSet() })
        assertEquals(2, runBlocking { app.database.documents().pages(id).size })
    }

    @Test fun qualitySwitchReplacesTheMountedPreviewAndKeepsCameraRoute() {
        ui.onNodeWithText(text(R.string.new_scan)).performClick()
        ui.waitUntil(10_000) { preview() != null }
        val fast = preview()
        ui.onNodeWithTag("quality-high").performClick()
        ui.waitUntil(10_000) { previewMode() == ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY }
        assertNotSame("The native host must not keep the old PreviewView", fast, preview())
        ui.onNodeWithTag("quality-high").assertIsSelected()
        screenshot("camera")
        ui.onNodeWithTag("quality-fast").performClick()
        ui.waitUntil(10_000) { previewMode() == ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("quality-fast").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("quality-fast").assertIsSelected()
        assertEquals("camera", model.screen); assertNull(model.documentId)
        ui.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals("home", model.screen)
    }

    @Test fun previewNavigationSettingsAndUnifiedOutputRemainReachable() {
        val title = "סריקת ספר"; val id = create(title); create("סיכומי לימוד")
        ui.waitUntil(15_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        if (DesignConfig.fixedTabs) ui.onNodeWithTag("nav-documents").assertIsSelected()
        else ui.onNodeWithTag("nav-documents").assertDoesNotExist()
        screenshot("library")
        ui.onNodeWithContentDescription(text(R.string.design_settings)).performClick()
        ui.onNodeWithText(text(R.string.archive_restore)).performScrollTo().assertIsDisplayed()
        screenshot("settings")
        ui.onNodeWithContentDescription(text(R.string.back)).performClick()
        ui.onNodeWithText(title).performScrollTo().performClick()
        ui.onNodeWithTag("continuous-pages").assertIsDisplayed()
        if (DesignConfig.fixedTabs) ui.onNodeWithTag("nav-pages").assertIsSelected()
        screenshot("pages")
        ui.onNodeWithContentDescription(text(R.string.share_options)).performClick()
        ui.onNodeWithText(text(R.string.save_pdf)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(text(R.string.save_gallery)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(text(R.string.share_both)).performScrollTo().assertIsDisplayed()
        screenshot("output")
        assertEquals(2, runBlocking { app.database.documents().pages(id).size })
    }

    @Test fun failedFirstCaptureDoesNotPublishADocumentAndSuccessfulPagesStayTogether() {
        ui.runOnUiThread { model.startNewScan() }
        val before = runBlocking { app.database.documents().allDocuments().map { it.id }.toSet() }
        val bad = File(ui.activity.cacheDir, "bad-${UUID.randomUUID()}.jpg").apply { writeText("not an image") }.also { inputs.add(it) }
        val failed = AtomicBoolean(false)
        ui.runOnUiThread { model.captureInSession(bad, DetectionMode.STANDARD) { assertFalse(it); failed.set(true) } }
        ui.waitUntil(15_000) { failed.get() }
        assertNull(model.documentId)
        assertEquals(before, runBlocking { app.database.documents().allDocuments().map { it.id }.toSet() })
        repeat(2) {
            val source = input(); val bytes = source.readBytes(); val complete = AtomicBoolean(false)
            ui.runOnUiThread { model.captureInSession(source, DetectionMode.STANDARD) { assertTrue(it); complete.set(true) } }
            ui.waitUntil(20_000) { complete.get() }
            val id = requireNotNull(model.documentId); if (id !in owned) owned.add(id)
            val pages = runBlocking { app.database.documents().pages(id) }
            assertEquals(it + 1, pages.size); assertArrayEquals(bytes, app.repository.source(pages.last()).readBytes())
        }
        assertEquals(before.size + 1, runBlocking { app.database.documents().allDocuments().size })
        ui.runOnUiThread { model.finishCamera() }
        assertEquals("document", model.screen)
    }
}
