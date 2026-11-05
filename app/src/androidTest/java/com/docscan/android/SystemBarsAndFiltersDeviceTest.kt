package com.docscan.android

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.ui.DesignConfig
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Exercises real platform insets, including Android 15 edge-to-edge enforcement. */
@SdkSuppress(minSdkVersion = 35)
class SystemBarsAndFiltersDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as DocScanApplication
    private val model get() = ViewModelProvider(ui.activity)[DocScanViewModel::class.java]
    private fun text(id: Int, vararg args: Any) = ui.activity.getString(id, *args)
    private var document: String? = null
    private var source: File? = null
    private var previousMode = 0

    private fun navigationMode(mode: Int) {
        val overlay = if (mode == 2) "gestural" else "threebutton"
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.$overlay",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        ui.waitUntil(15_000) {
            Settings.Secure.getInt(ui.activity.contentResolver, "navigation_mode", -1) == mode
        }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15_000) { !model.busy.value && ui.onAllNodesWithTag("safe-app").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }

    private fun assertSafe(node: SemanticsNodeInteraction = ui.onNodeWithTag("safe-app")) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        var height = 0; var width = 0
        var insets = androidx.core.graphics.Insets.NONE
        ui.runOnUiThread {
            val decor = ui.activity.window.decorView
            height = decor.height; width = decor.width
            insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        assertTrue("Test must have a visible navigation region", insets.bottom > 0)
        assertTrue("Content crosses the navigation bar: $bounds vs $insets", bounds.bottom <= height - insets.bottom + 2)
        assertTrue("Content crosses the status bar", bounds.top >= insets.top - 2)
        assertTrue("Content crosses a side safe area", bounds.left >= insets.left - 2 && bounds.right <= width - insets.right + 2)
    }

    private fun screenshot(mode: Int, screen: String) {
        ui.waitForIdle()
        val resolver = ui.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "workflow-system-ui-${DesignConfig.number}-$mode-$screen.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScanTest")
        }))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Before fun prepare() {
        previousMode = Settings.Secure.getInt(ui.activity.contentResolver, "navigation_mode", 0)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ui.activity.packageName, Manifest.permission.CAMERA)
        ui.activity.getSharedPreferences("scan-preferences", 0).edit().putBoolean("automatic", false).commit()
        ui.waitUntil(15_000) { !model.busy.value }
        val file = File(ui.activity.cacheDir, "insets-${UUID.randomUUID()}.png").also { source = it }
        val bitmap = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        document = runBlocking {
            app.repository.create("System bars ${UUID.randomUUID()}").also {
                app.repository.importImage(it, Uri.fromFile(file), detectBounds = false)
            }
        }
    }

    @After fun cleanup() {
        document?.let { id -> runBlocking { app.repository.deleteDocument(id) } }
        source?.delete()
        navigationMode(previousMode)
    }

    @Test fun allMainScreensAndInlineFiltersStayAboveBothNavigationModes() {
        for (mode in listOf(0, 2)) {
            navigationMode(mode)
            ui.runOnUiThread { model.screen = "home" }
            assertSafe(); assertSafe(ui.onNodeWithText(text(R.string.new_scan)))
            ui.onNodeWithText(text(R.string.new_scan)).performClick()
            assertSafe(); assertSafe(ui.onNodeWithTag("capture-button"))
            ui.onNodeWithTag("capture-button").assertHeightIsAtLeast(64.dp)
            screenshot(mode, "camera")
            ui.onNodeWithContentDescription(text(R.string.back)).performClick()

            ui.runOnUiThread { model.openDocument(requireNotNull(document)) }
            ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription(text(R.string.edit_page_desc, 1)).fetchSemanticsNodes().isNotEmpty() }
            assertSafe(); assertSafe(ui.onNodeWithContentDescription(text(R.string.share_options)))
            ui.onNodeWithContentDescription(text(R.string.edit_page_desc, 1)).performClick()
            ui.onNodeWithText(text(R.string.open_filters)).performClick()
            ui.onNodeWithTag("filter-strip").assertIsDisplayed()
            ui.onAllNodes(isDialog()).assertCountEquals(0)
            ui.onNodeWithContentDescription(text(R.string.filters_label)).performScrollToNode(hasText(text(R.string.filter_auto_document)))
            ui.onNodeWithText(text(R.string.filter_auto_document)).performClick()
            ui.waitUntil(15_000) { !model.busy.value }
            assertSafe(); assertSafe(ui.onNodeWithTag("filter-strip"))
            screenshot(mode, "filters")
            ui.onNodeWithTag("page-pager").assertHeightIsAtLeast(96.dp)
            ui.onNodeWithText(text(R.string.done)).performClick()
            ui.onNodeWithText(text(R.string.crop_title)).performClick()
            assertSafe(); assertSafe(ui.onNodeWithText(text(R.string.apply_crop)))
            ui.onNodeWithText(text(R.string.cancel)).performClick()
            ui.onNodeWithContentDescription(text(R.string.design_settings)).performClick()
            ui.onNodeWithText(text(R.string.archive_restore)).performScrollTo()
            assertSafe(); assertSafe(ui.onNodeWithText(text(R.string.archive_restore)))
            ui.onNodeWithContentDescription(text(R.string.back)).performClick()
        }
    }
}
