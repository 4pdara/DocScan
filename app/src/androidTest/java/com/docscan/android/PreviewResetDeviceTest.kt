package com.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.docscan.android.ui.DocScanTheme
import com.docscan.android.ui.PagePreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PreviewResetDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun imageStaysPaintedAfterRepeatedZoomPanReleaseAndReset() {
        val app = ui.activity.application as DocScanApplication
        val input = File(ui.activity.cacheDir, "zoom-${UUID.randomUUID()}.png")
        val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(180, 35, 45))
        Canvas(image).drawRect(200f, 0f, 400f, 600f, Paint().apply { color = Color.rgb(35, 60, 180) })
        input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        val id = runBlocking { app.repository.create("Preview reset") }
        try {
            val page = runBlocking { app.repository.importImage(id, Uri.fromFile(input), detectBounds = false) }
            val bytes = app.repository.source(page).readBytes()
            ui.activity.setContent { DocScanTheme {
                PagePreview(page, app.repository, Modifier.fillMaxSize().testTag("preview-container"), zoomable = true)
            } }
            val zoomIn = ui.activity.getString(R.string.zoom_in)
            val reset = ui.activity.getString(R.string.zoom_reset)
            ui.waitUntil(15_000) { ui.onAllNodesWithText(zoomIn).fetchSemanticsNodes().isNotEmpty() }
            fun assertImageVisible() {
                ui.waitForIdle()
                val pixels = ui.onNodeWithTag("preview-container").captureToImage().toPixelMap()
                var colored = 0; var count = 0
                for (y in 0 until pixels.height step 8) for (x in 0 until pixels.width step 8) {
                    val p = pixels[x, y]; count++
                    if ((p.red > .45f && p.blue < .35f) || (p.blue > .45f && p.red < .35f)) colored++
                }
                assertTrue("The actual image must remain painted, not just its zoom button", colored > count * .35f)
            }
            assertImageVisible()
            repeat(3) {
                ui.onNodeWithText(zoomIn).performClick()
                ui.onNodeWithText(reset).assertIsDisplayed()
                ui.onNodeWithTag("preview-image-${page.id}").performTouchInput {
                    swipe(center, center + Offset(25f, 35f), 200)
                }
                assertImageVisible()
                ui.onNodeWithText(reset).performClick()
                ui.onNodeWithText(zoomIn).assertIsDisplayed()
                assertImageVisible()
            }
            ui.onNodeWithTag("preview-image-${page.id}").performTouchInput { doubleClick(center) }
            ui.onNodeWithText(reset).assertIsDisplayed().performClick()
            assertImageVisible()
            assertArrayEquals(bytes, app.repository.source(page).readBytes())
        } finally { runBlocking { app.repository.deleteDocument(id) }; input.delete() }
    }
}
