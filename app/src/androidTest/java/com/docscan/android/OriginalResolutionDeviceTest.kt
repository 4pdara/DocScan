package com.docscan.android

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscan.android.data.PageEntity
import com.docscan.android.export.*
import com.docscan.android.processing.ScanFilter
import com.docscan.android.ui.DocScanApp
import com.docscan.android.ui.DocScanTheme
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OriginalResolutionDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val prefix = "Resolution-${UUID.randomUUID().toString().take(8)}"
    private val files = mutableListOf<File>()
    private var originalActivityConfiguration: Configuration? = null
    private var originalAppConfiguration: Configuration? = null
    private val app get() = ui.activity.application as DocScanApplication
    private val model get() = ViewModelProvider(ui.activity)[DocScanViewModel::class.java]
    private fun text(id: Int) = ui.activity.getString(id)
    private fun qualityText(label: Int) = ui.activity.getString(R.string.export_quality_current, text(label))
    private fun file(extension: String) = File(ui.activity.cacheDir, "${UUID.randomUUID()}.$extension").also { files.add(it) }
    private fun galleryFiles(): List<Uri> {
        val result = mutableListOf<Uri>()
        ui.activity.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null)!!.use {
            while (it.moveToNext()) result.add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)))
        }
        return result
    }
    @Before fun setup() {
        if (Build.VERSION.SDK_INT <= 28) for (permission in listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ui.activity.packageName, permission)
        }
        ui.waitUntil(15_000) { !model.busy.value }
        ui.runOnUiThread { model.chooseExportQuality(ExportQuality.ORIGINAL) }
    }
    @Suppress("DEPRECATION")
    @After fun cleanup(): Unit = runBlocking {
        galleryFiles().forEach { ui.activity.contentResolver.delete(it, null, null) }
        app.database.documents().allDocuments().filter { it.title == prefix }.forEach { app.repository.deleteDocument(it.id) }
        files.forEach { it.delete() }
        app.getSharedPreferences("output-preferences", 0).edit { remove("quality") }
        ui.runOnUiThread {
            originalActivityConfiguration?.let { ui.activity.resources.updateConfiguration(it, ui.activity.resources.displayMetrics) }
            originalAppConfiguration?.let { app.resources.updateConfiguration(it, app.resources.displayMetrics) }
        }
        Unit
    }
    private suspend fun create(width: Int = 4500, height: Int = 1100): PageEntity {
        val source = file("png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(bitmap).drawRect(width * .2f, height * .2f, width * .8f, height * .8f, Paint().apply { color = Color.BLUE })
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        return app.repository.importImage(app.repository.create(prefix), Uri.fromFile(source), detectBounds = false)
    }
    private fun bounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ui.activity.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }
    private fun pdfImage(file: File): Pair<Int, Int> = PDDocument.load(file).use { pdf ->
        val resources = pdf.getPage(0).resources
        val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
        image.width to image.height
    }
    private fun openDocument(pageLabel: String = ui.activity.getString(R.string.page_label, 1)) {
        ui.waitUntil(15_000) { ui.onAllNodesWithText(prefix).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(prefix).performScrollTo().performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithText(pageLabel).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun fullResolutionJpegPdfGalleryAndRawSourcesAreRealFiles(): Unit = runBlocking {
        val page = create(); val original = app.repository.source(page).readBytes()
        val jpeg = file("jpg"); jpeg.outputStream().use { JpegExporter.write(page, app.repository, it) }
        assertEquals(4500 to 1100, bounds(Uri.fromFile(jpeg)))
        val pdf = file("pdf"); PdfExporter.write(listOf(page), app.repository, pdf)
        assertEquals(4500 to 1100, pdfImage(pdf))
        val gallery = GalleryExporter.write(ui.activity, prefix, listOf(page), app.repository).single()
        assertEquals(4500 to 1100, bounds(gallery))
        val copy = file("png"); copy.outputStream().use { SourceExporter.write(page, app.repository, it) }
        assertArrayEquals(original, copy.readBytes()); assertArrayEquals(original, app.repository.source(page).readBytes())

        val vector = file("pdf")
        PDDocument().use {
            val output = PDPage(PDRectangle.A4); it.addPage(output)
            PDPageContentStream(it, output).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 16f)
                stream.newLineAtOffset(40f, 600f); stream.showText("300 DPI"); stream.endText()
            }; it.save(vector)
        }
        val id = app.repository.create(prefix); app.repository.importPdf(id, Uri.fromFile(vector))
        val imported = app.database.documents().pages(id).single()
        app.repository.setFilter(imported.id, ScanFilter.GRAYSCALE)
        val edited = file("pdf")
        PdfExporter.write(listOf(app.database.documents().page(imported.id)!!), app.repository, edited)
        assertEquals(2480 to 3508, pdfImage(edited))
        val rotated = file("pdf")
        PDDocument.load(vector).use { document -> document.getPage(0).rotation = 90; document.save(rotated) }
        assertEquals(3508 to 2480, com.docscan.android.processing.PdfSources.dimensionsAtDpi(rotated, 300))
    }

    @Test fun selectedQualitySurvivesRecreationAndActuallyChangesGalleryOutput() {
        runBlocking { create() }; openDocument()
        ui.onNodeWithText(qualityText(R.string.quality_original)).performClick()
        ui.onNodeWithText(text(R.string.quality_high)).performClick()
        ui.onNodeWithText(text(R.string.apply)).performScrollTo().performClick()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15_000) { ui.onAllNodesWithText(qualityText(R.string.quality_high)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription(text(R.string.share_options)).performClick()
        ui.waitUntil(15_000) {
            ui.onAllNodes(hasText(text(R.string.save_gallery)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText(text(R.string.save_gallery)).performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(30_000) { !model.busy.value && galleryFiles().size == 1 }
        assertEquals(3200, bounds(galleryFiles().single()).first)
        ui.onNodeWithText(qualityText(R.string.quality_high)).performClick()
        ui.onNodeWithText(text(R.string.quality_original)).performClick()
        ui.onNodeWithText(text(R.string.apply)).performScrollTo().performClick()
        ui.onNodeWithContentDescription(text(R.string.share_options)).performClick()
        ui.onNodeWithText(text(R.string.save_gallery)).performScrollTo().performClick()
        ui.waitUntil(30_000) { !model.busy.value && galleryFiles().size == 2 }
        assertEquals(setOf(3200, 4500), galleryFiles().map { bounds(it).first }.toSet())
    }

    @Suppress("DEPRECATION")
    @Test fun hebrewQualityAndExplicitUneditedSourceExportHaveClearControls() {
        runBlocking { create(800, 600) }
        val activity = ui.activity
        val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("he")) }
        val vm = model
        ui.runOnUiThread {
            originalActivityConfiguration = Configuration(activity.resources.configuration)
            originalAppConfiguration = Configuration(app.resources.configuration)
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides activity,
                    LocalContext provides activity, LocalConfiguration provides configuration) {
                    DocScanTheme { DocScanApp(vm) }
                }
            }
        }
        val he = activity.resources
        openDocument(he.getString(R.string.page_label, 1))
        ui.onNodeWithText(he.getString(R.string.export_quality_current, he.getString(R.string.quality_original))).performClick()
        ui.onNodeWithText(he.getString(R.string.quality_original_hint)).assertExists()
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "he-output-quality.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScanTest")
            })!!
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            activity.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        ui.onNodeWithText(he.getString(R.string.apply)).performScrollTo().performClick()
        ui.onNodeWithContentDescription(he.getString(R.string.more_options)).performClick()
        ui.onNodeWithText(he.getString(R.string.save_sources)).performClick()
        ui.onNodeWithText(he.getString(R.string.save_sources_hint)).assertExists()
        ui.onNodeWithText(he.getString(R.string.cancel)).performClick()
        assertFalse(vm.sourceExportPending)
    }
}
