package com.docscan.android

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.processing.CropQuad
import com.docscan.android.scanning.GoogleScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ScanEditingFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int) = ui.activity.getString(id)
    private val app get() = ui.activity.application as DocScanApplication

    @Before fun allowCamera() {
        ui.activity.getSharedPreferences("scan-preferences", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            ui.activity.packageName, Manifest.permission.CAMERA,
        )
    }

    private fun saveScreenshot(name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocScanTest")
        }
        val resolver = ui.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        ui.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { resolver.openOutputStream(uri).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(output)) } }
        finally { bitmap.recycle() }
    }

    @Test fun cropCanBeDraggedSavedCancelledAndResetThroughTheRealUi() {
        val title = "Crop UI ${UUID.randomUUID()}"
        val input = File(ui.activity.cacheDir, "crop-ui.png")
        val image = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.DKGRAY)
        val canvas = Canvas(image)
        canvas.drawRect(45f, 50f, 315f, 430f, Paint().apply { color = Color.WHITE })
        for (y in 130..350 step 30) canvas.drawRect(90f, y.toFloat(), 270f, y + 4f, Paint().apply { color = Color.BLACK })
        input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        val original = input.readBytes()
        val id = runBlocking { app.repository.create(title) }
        try {
            val page = runBlocking { app.repository.importImage(id, Uri.fromFile(input)) }
            val initial = requireNotNull(CropQuad.decode(page.crop))
            ui.waitUntil(10_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(title).performScrollTo().performClick()
            ui.onNodeWithText(text(R.string.edit), useUnmergedTree = false).performClick()
            ui.onNodeWithText(text(R.string.crop_title)).performClick()
            val crop = ui.onNodeWithContentDescription(text(R.string.crop_hint))
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.crop_hint)).fetchSemanticsNodes().isNotEmpty() }
            crop.assertIsDisplayed()
            saveScreenshot("crop-editor.png")
            val size = crop.fetchSemanticsNode().size
            val scale = minOf(size.width / 360f, size.height / 480f)
            val start = Offset((size.width - 360 * scale) / 2 + initial.topLeft.x * 360 * scale,
                (size.height - 480 * scale) / 2 + initial.topLeft.y * 480 * scale)
            crop.performTouchInput { swipe(start, start + Offset(28f, 28f), durationMillis = 400) }
            ui.onNodeWithText(text(R.string.apply_crop)).performClick()
            ui.waitUntil(10_000) { runBlocking {
                CropQuad.decode(app.database.documents().page(page.id)?.crop)?.topLeft?.distance(initial.topLeft)?.let { it > .02f } == true
            } }
            val saved = runBlocking { app.database.documents().page(page.id)?.crop }
            assertArrayEquals(original, app.repository.source(page).readBytes())
            ui.onNodeWithText(text(R.string.crop_title)).performClick()
            ui.onNodeWithText(text(R.string.reset_crop)).performClick()
            ui.onNodeWithText(text(R.string.cancel)).performClick()
            assertEquals(saved, runBlocking { app.database.documents().page(page.id)?.crop })
            ui.onNodeWithText(text(R.string.crop_title)).performClick()
            ui.onNodeWithText(text(R.string.reset_crop)).performClick()
            ui.onNodeWithText(text(R.string.apply_crop)).performClick()
            ui.waitUntil(10_000) { runBlocking { app.database.documents().page(page.id)?.crop == null } }
            assertArrayEquals(original, app.repository.source(page).readBytes())
        } finally { runBlocking { app.repository.deleteDocument(id) }; input.delete() }
    }

    @Test fun cleanupFilterAndStrengthCanBeSelectedAndReopened() {
        val title = "Cleanup UI ${UUID.randomUUID()}"
        val input = File(ui.activity.cacheDir, "cleanup-ui.png")
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (x in 0 until 300) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 400f,
            Paint().apply { color = Color.rgb(140 + x / 4, 140 + x / 4, 140 + x / 4) })
        for (y in 100..300 step 30) canvas.drawRect(50f, y.toFloat(), 250f, y + 3f, Paint().apply { color = Color.DKGRAY })
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val original = input.readBytes()
        val id = runBlocking { app.repository.create(title) }
        try {
            val page = runBlocking { app.repository.importImage(id, Uri.fromFile(input)) }
            ui.waitUntil(10_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(title).performScrollTo().performClick()
            ui.onNodeWithText(text(R.string.edit)).performClick()
            ui.onNodeWithText(text(R.string.open_filters)).performClick()
            ui.onNodeWithTag("filter-strip").assertIsDisplayed()
            ui.onAllNodes(isDialog()).assertCountEquals(0)
            ui.onNodeWithTag("page-pager").assertHeightIsAtLeast(96.dp)
            ui.onNodeWithContentDescription(text(R.string.filters_label))
                .performScrollToNode(hasText(text(R.string.clean_color)))
            ui.onNodeWithText(text(R.string.clean_color)).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.filter_strength)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription(text(R.string.filter_strength)).performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
            ui.waitUntil(10_000) { runBlocking { app.database.documents().page(page.id)?.filterStrength == 25 } }
            saveScreenshot("filter-picker.png")
            ui.onNodeWithText(text(R.string.done)).performClick()
            saveScreenshot("image-cleanup.png")
            ui.onNodeWithContentDescription(text(R.string.back)).performClick()
            ui.onNodeWithText(text(R.string.edit)).performClick()
            ui.onNodeWithText(text(R.string.open_filters)).performClick()
            ui.onNodeWithText("25%").assertIsDisplayed()
            assertEquals("CLEAN_COLOR", runBlocking { app.database.documents().page(page.id)?.filter })
            assertArrayEquals(original, app.repository.source(page).readBytes())
        } finally { runBlocking { app.repository.deleteDocument(id) }; input.delete() }
    }

    @Test fun cameraThumbnailsOpenReviewAndRetakeCanBeCancelledSafely() {
        val title = "Preview strip ${UUID.randomUUID()}"
        val input = File(ui.activity.cacheDir, "preview-strip.png")
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val original = input.readBytes()
        val id = runBlocking { app.repository.create(title) }
        try {
            val page = runBlocking { app.repository.importImage(id, Uri.fromFile(input)) }
            ui.waitUntil(10_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(title).performScrollTo().performClick()
            ui.onNodeWithText(text(R.string.save_and_share)).assertIsDisplayed()
            ui.onNodeWithText(text(R.string.add_pages)).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText(ui.activity.getString(R.string.page_label, 1)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(ui.activity.getString(R.string.page_label, 1)).performClick()
            ui.onNodeWithText(text(R.string.retake)).performClick()
            ui.onNodeWithContentDescription(text(R.string.fast_scan)).assertIsOff()
            ui.onNodeWithText(text(R.string.cancel)).performClick()
            ui.onNodeWithText(text(R.string.retake)).assertIsDisplayed()
            ui.onNodeWithContentDescription(text(R.string.back)).performClick()
            ui.onNodeWithText(ui.activity.getString(R.string.page_label, 1)).assertIsDisplayed()
            saveScreenshot("camera-pages.png")
            assertEquals(1, runBlocking { app.database.documents().pages(id).size })
            assertArrayEquals(original, app.repository.source(page).readBytes())
        } finally { runBlocking { app.repository.deleteDocument(id) }; input.delete() }
    }

    @Test fun googleScannerUnavailableKeepsLocalScannerUsable() {
        assumeFalse(GoogleScanner.isAvailable(ui.activity))
        val before = runBlocking { app.repository.observeDocuments().first().map { it.document.id }.toSet() }
        try {
            ui.onNodeWithContentDescription(text(R.string.design_settings)).performClick()
            ui.onNodeWithText(text(R.string.scan_google)).performScrollTo().performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText(text(R.string.google_scan_failed)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(text(R.string.google_scan_failed)).assertIsDisplayed()
            assertEquals(before, runBlocking { app.repository.observeDocuments().first().map { it.document.id }.toSet() })
            ui.onNodeWithContentDescription(text(R.string.back)).performClick()
            ui.onNodeWithText(text(R.string.new_scan)).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.fast_scan)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription(text(R.string.fast_scan)).assertIsOn()
        } finally {
            val created = runBlocking { app.repository.observeDocuments().first().map { it.document.id }.filter { it !in before } }
            runBlocking { created.forEach { app.repository.deleteDocument(it) } }
        }
    }

    @Test fun angledDetectionOptionPersistsAcrossActivityReplacement() {
        val before = runBlocking { app.repository.observeDocuments().first().map { it.document.id }.toSet() }
        try {
            ui.onNodeWithText(text(R.string.new_scan)).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.fast_scan)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription(text(R.string.fast_scan)).performClick()
            ui.onNodeWithContentDescription(text(R.string.more_options)).performClick()
            ui.onNodeWithContentDescription(text(R.string.angle_distance_detection)).assertIsOff().performClick()
            ui.onNodeWithContentDescription(text(R.string.fast_scan)).assertIsOff()
            assertTrue(ui.activity.getSharedPreferences("scan-preferences", android.content.Context.MODE_PRIVATE).getBoolean("angled-distance", false))
            ui.activityRule.scenario.recreate()
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.fast_scan)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription(text(R.string.more_options)).performClick()
            ui.onNodeWithContentDescription(text(R.string.angle_distance_detection)).assertIsOn()
            saveScreenshot("workflow-angled-detection.png")
        } finally {
            val created = runBlocking { app.repository.observeDocuments().first().map { it.document.id }.filter { it !in before } }
            runBlocking { created.forEach { app.repository.deleteDocument(it) } }
        }
    }

    @Test fun fastScanAndPauseAreVisibleAndManualModeRemainsAvailable() {
        val before = runBlocking { app.repository.observeDocuments().first().map { it.document.id }.toSet() }
        try {
            ui.waitForIdle()
            ui.onNodeWithText(text(R.string.new_scan)).performClick()
            val fast = ui.onNodeWithContentDescription(text(R.string.fast_scan))
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.fast_scan)).fetchSemanticsNodes().isNotEmpty() }
            fast.assertIsOn()
            ui.onNodeWithText(text(R.string.pause_scan)).performClick()
            ui.onNodeWithText(text(R.string.resume_scan)).assertIsDisplayed()
            saveScreenshot("fast-scan.png")
            fast.performClick()
            fast.assertIsOff()
            ui.onNodeWithText(text(R.string.manual_scan_hint)).assertIsDisplayed()
        } finally {
            val created = runBlocking { app.repository.observeDocuments().first().map { it.document.id }.filter { it !in before } }
            runBlocking { created.forEach { app.repository.deleteDocument(it) } }
        }
    }
}
