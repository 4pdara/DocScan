package com.docscan.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.docscan.android.processing.CropQuad
import com.docscan.android.ui.DocScanApp
import com.docscan.android.ui.DocScanTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.Locale

@SdkSuppress(minSdkVersion = 29)
class ProfessionalEditingFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as DocScanApplication
    private var labels: Resources? = null
    private fun text(id: Int) = labels?.getString(id) ?: ui.activity.getString(id)

    private fun screenshot(name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocScanTest")
        }
        val resolver = ui.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        ui.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { resolver.openOutputStream(uri).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it)) } }
        finally { bitmap.recycle() }
    }

    private fun editingPage(block: (String, ByteArray) -> Unit) {
        val title = "Professional editor ${UUID.randomUUID()}"
        val input = File(ui.activity.cacheDir, "pro-ui-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(130, 145, 160))
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val id = runBlocking { app.repository.create(title) }
        try {
            val page = runBlocking { app.repository.importImage(id, Uri.fromFile(input)) }
            ui.waitUntil(10_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(title).performScrollTo().performClick()
            ui.onNodeWithText(text(R.string.edit)).performClick()
            block(page.id, input.readBytes())
            assertArrayEquals(input.readBytes(), app.repository.source(requireNotNull(runBlocking { app.database.documents().page(page.id) })).readBytes())
        } finally { runBlocking { app.repository.deleteDocument(id) }; input.delete() }
    }

    @Test fun imageAdjustmentDraftCanBeCancelledAppliedAndReopened() = editingPage { id, _ ->
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text(R.string.zoom_in)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(text(R.string.zoom_in)).performClick()
        ui.onNodeWithText(text(R.string.zoom_reset)).assertIsDisplayed().performClick()
        ui.onNodeWithText(text(R.string.zoom_in)).assertIsDisplayed()
        ui.onNodeWithText(text(R.string.image_adjustments)).performClick()
        ui.onNodeWithContentDescription(text(R.string.adjust_sharpness)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        ui.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(0, runBlocking { app.database.documents().page(id)?.sharpness })
        ui.onNodeWithText(text(R.string.image_adjustments)).performClick()
        ui.onNodeWithContentDescription(text(R.string.adjust_brightness)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        ui.onNodeWithContentDescription(text(R.string.adjust_sharpness)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        screenshot("image-adjustments.png")
        ui.onNodeWithText(text(R.string.apply_adjustments)).performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.documents().page(id)?.sharpness == 75 } }
        assertEquals(20, runBlocking { app.database.documents().page(id)?.brightness })
        ui.onNodeWithText(text(R.string.image_adjustments)).performClick()
        ui.onNodeWithText("75%").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(text(R.string.reset_adjustments)).performClick()
        ui.onNodeWithText(text(R.string.apply_adjustments)).performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.documents().page(id)?.sharpness == 0 } }
    }

    @Test fun rectangleAndPageShapeStayDraftUntilCropIsApplied() = editingPage { id, _ ->
        ui.onNodeWithText(text(R.string.crop_title)).performClick()
        ui.onNodeWithText(text(R.string.crop_options)).performClick()
        ui.onNodeWithText(text(R.string.crop_rectangle)).performClick()
        ui.onNodeWithText(text(R.string.format_letter)).performClick()
        screenshot("crop-options.png")
        assertEquals("NATURAL", runBlocking { app.database.documents().page(id)?.pageFormat })
        ui.onNodeWithText(text(R.string.done)).performClick()
        ui.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals("NATURAL", runBlocking { app.database.documents().page(id)?.pageFormat })
        ui.onNodeWithText(text(R.string.crop_title)).performClick()
        ui.onNodeWithText(text(R.string.crop_options)).performClick()
        ui.onNodeWithText(text(R.string.format_natural)).assertIsSelected()
        ui.onNodeWithText(text(R.string.crop_perspective)).assertIsSelected()
        ui.onNodeWithText(text(R.string.crop_rectangle)).performClick()
        ui.onNodeWithText(text(R.string.format_letter)).performClick()
        ui.onNodeWithText(text(R.string.done)).performClick()
        val canvas = ui.onNodeWithContentDescription(text(R.string.crop_hint))
        val size = canvas.fetchSemanticsNode().size
        val scale = minOf(size.width / 300f, size.height / 400f)
        val top = (size.height - 400 * scale) / 2
        val slop = android.view.ViewConfiguration.get(ui.activity).scaledTouchSlop.toFloat()
        canvas.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(size.width / 2f, top + 2f))
            moveTo(androidx.compose.ui.geometry.Offset(size.width / 2f, top + slop + 4f), 100)
            moveTo(androidx.compose.ui.geometry.Offset(size.width / 2f, top + slop + 400 * scale * .2f), 200)
        }
        screenshot("crop-loupe.png")
        canvas.performTouchInput { up() }
        val navigation = androidx.lifecycle.ViewModelProvider(ui.activity)[DocScanViewModel::class.java]
        assertEquals("crop", navigation.screen)
        assertEquals(id, navigation.pageId)
        ui.activityRule.scenario.recreate()
        try {
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.crop_hint)).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            val restored = androidx.lifecycle.ViewModelProvider(ui.activity)[DocScanViewModel::class.java]
            android.util.Log.e("DocScanTest", "restored route=" + restored.screen +
                ", selected page matches=" + (restored.pageId == id) +
                ", database page exists=" + runBlocking { app.database.documents().page(id) != null })
            runCatching { screenshot("crop-restore-failure.png") }
            println(ui.onAllNodes(isRoot()).onLast().printToString())
            throw error
        }
        ui.onNodeWithText(text(R.string.crop_options)).performClick()
        ui.onNodeWithText(text(R.string.format_letter)).assertIsSelected()
        ui.onNodeWithText(text(R.string.crop_rectangle)).assertIsSelected()
        ui.onNodeWithText(text(R.string.done)).performClick()
        ui.onNodeWithText(text(R.string.apply_crop)).performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.documents().page(id)?.pageFormat == "LETTER" } }
        val saved = requireNotNull(runBlocking { app.database.documents().page(id) })
        val quad = requireNotNull(CropQuad.decode(saved.crop))
        assertTrue(quad.topLeft.y > .05f)
        assertEquals(quad.topLeft.y, quad.topRight.y); assertEquals(quad.bottomLeft.y, quad.bottomRight.y)
        ui.onNodeWithText(text(R.string.crop_title)).performClick()
        ui.onNodeWithText(text(R.string.crop_options)).performClick()
        ui.onNodeWithText(text(R.string.format_letter)).assertIsSelected()
    }

    @Test fun perspectiveSideHandlesPullBothCornersAndKeepTheOppositeSide() = editingPage { id, _ ->
        ui.onNodeWithText(text(R.string.crop_title)).performClick()
        val canvas = ui.onNodeWithContentDescription(text(R.string.crop_hint))
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text(R.string.crop_hint)).fetchSemanticsNodes().isNotEmpty() }
        val size = canvas.fetchSemanticsNode().size
        val scale = minOf(size.width / 300f, size.height / 400f)
        val right = (size.width + 300 * scale) / 2f
        val centerY = size.height / 2f
        val slop = android.view.ViewConfiguration.get(ui.activity).scaledTouchSlop.toFloat()
        canvas.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(right - 2f, centerY))
            moveTo(androidx.compose.ui.geometry.Offset(right - slop - 4f, centerY), 100)
            moveTo(androidx.compose.ui.geometry.Offset(right - slop - 300 * scale * .18f, centerY + 25f), 200)
            up()
        }
        screenshot("workflow-side-handles.png")
        ui.onNodeWithText(text(R.string.apply_crop)).performClick()
        ui.waitUntil(10_000) { runBlocking { app.database.documents().page(id)?.crop != null } }
        val saved = CropQuad.decode(runBlocking { app.database.documents().page(id)?.crop })!!
        assertTrue(saved.topRight.x < .9f); assertEquals(saved.topRight.x, saved.bottomRight.x, .001f)
        assertEquals(0f, saved.topLeft.x, .001f); assertEquals(0f, saved.bottomLeft.x, .001f)
        assertEquals(0f, saved.topRight.y, .001f); assertEquals(1f, saved.bottomRight.y, .001f)
    }

    @Suppress("DEPRECATION")
    @Test fun hebrewEditorFiltersAdjustmentsAndCropOptionsRemainUsable() {
        val original = Configuration(ui.activity.resources.configuration)
        val originalApp = Configuration(app.resources.configuration)
        val configuration = Configuration(original).apply { setLocale(Locale.forLanguageTag("he")) }
        labels = ui.activity.resources
        ui.runOnUiThread {
            // Dialog windows create their own Android context; update the actual test Activity resources.
            ui.activity.resources.updateConfiguration(configuration, ui.activity.resources.displayMetrics)
            app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
            ui.activity.setContent {
                CompositionLocalProvider(LocalContext provides ui.activity, LocalConfiguration provides configuration) {
                    DocScanTheme { DocScanApp() }
                }
            }
        }
        try {
        ui.onNodeWithText(text(R.string.library_title)).assertIsDisplayed()
        screenshot("he-home.png")
        editingPage { id, _ ->
            ui.onNodeWithText(text(R.string.open_filters)).performClick()
            ui.onNodeWithContentDescription(text(R.string.filters_label)).performScrollToNode(hasText(text(R.string.filter_adaptive)))
            ui.onNodeWithText(text(R.string.filter_adaptive)).performClick()
            ui.waitUntil(10_000) { runBlocking { app.database.documents().page(id)?.filter == "ADAPTIVE_MONO" } }
            screenshot("he-filters.png")
            ui.onNodeWithText(text(R.string.done)).performClick()
            ui.onNodeWithText(text(R.string.image_adjustments)).performClick()
            screenshot("he-adjustments.png")
            ui.onNodeWithText(text(R.string.cancel)).performClick()
            ui.onNodeWithText(text(R.string.crop_title)).performClick()
            ui.onNodeWithText(text(R.string.crop_options)).performClick()
            ui.onNodeWithText(text(R.string.format_a4)).performClick()
            screenshot("he-crop-options.png")
        }
        } finally {
            ui.runOnUiThread {
                ui.activity.resources.updateConfiguration(original, ui.activity.resources.displayMetrics)
                app.resources.updateConfiguration(originalApp, app.resources.displayMetrics)
            }
        }
    }
}
