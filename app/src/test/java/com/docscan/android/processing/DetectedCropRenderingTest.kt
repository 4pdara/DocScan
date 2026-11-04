package com.docscan.android.processing

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.data.*
import com.docscan.android.export.PdfExporter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.cos.COSName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetectedCropRenderingTest {
    @Test fun distantPageIsActuallyWarpedAndSavedWithoutDesk(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "detected-${UUID.randomUUID()}.db"
        val db = DocScanDatabase.open(context, name); val repository = DocumentRepository(context, db)
        val file = File(context.cacheDir, "detected-${UUID.randomUUID()}.png")
        val id = repository.create("Distant document")
        try {
            val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(25, 35, 45))
            val path = Path().apply { moveTo(210f, 270f); lineTo(370f, 290f); lineTo(390f, 510f); lineTo(220f, 520f); close() }
            Canvas(bitmap).drawPath(path, Paint().apply { color = Color.WHITE })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val original = file.readBytes()
            val page = repository.importImage(id, Uri.fromFile(file), detectionMode = DetectionMode.ANGLED_DISTANCE)
            assertNotNull(page.crop)
            val output = page.render(repository.source(page), 1000)
            try {
                assertTrue(output.width < 230); assertTrue(output.height < 300)
                assertFalse(output.hasAlpha())
                val pixels = IntArray(output.width * output.height)
                output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                assertTrue("Desk must not remain outside the detected corners", pixels.count { Color.red(it) < 100 } < pixels.size * .01f)
            } finally { output.recycle() }
            assertArrayEquals(original, repository.source(page).readBytes())
            assertEquals(page.crop, db.documents().page(page.id)!!.crop)
        } finally { repository.deleteDocument(id); db.close(); context.deleteDatabase(name); file.delete() }
    }
    @Test fun printedContentSurvivesStrongPerspectiveCrop(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "printed-${UUID.randomUUID()}.db"
        val db = DocScanDatabase.open(context, name); val repository = DocumentRepository(context, db)
        val file = File(context.cacheDir, "printed-${UUID.randomUUID()}.png")
        val id = repository.create("Printed angled page")
        try {
            val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(25, 35, 45))
            val path = Path().apply { moveTo(252f, 160f); lineTo(336f, 184f); lineTo(528f, 688f); lineTo(72f, 616f); close() }
            val canvas = Canvas(bitmap)
            canvas.drawPath(path, Paint().apply { color = Color.WHITE })
            canvas.drawRect(265f, 360f, 340f, 440f, Paint().apply { color = Color.RED })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val page = repository.importImage(id, Uri.fromFile(file), detectionMode = DetectionMode.ANGLED_DISTANCE)
            val output = page.render(repository.source(page), 3200)
            try {
                assertFalse(output.hasAlpha())
                val pixels = IntArray(output.width * output.height)
                output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                val red = pixels.count { Color.red(it) > 200 && Color.green(it) < 80 }
                println("Printed fixture: crop=${page.crop}, size=${output.width}x${output.height}, red=$red, alpha=${output.hasAlpha()}")
                assertTrue("The printed content must stay inside the crop", red > 1000)
            } finally { output.recycle() }
            val pdfFile = File(context.cacheDir, "printed-${UUID.randomUUID()}.pdf")
            try {
                PdfExporter.write(listOf(page), repository, pdfFile)
                PDDocument.load(pdfFile).use { pdf ->
                    val resources = pdf.getPage(0).resources
                    val image = resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
                    assertNull(image.cosObject.getDictionaryObject(COSName.SMASK))
                    val decoded = image.image
                    try {
                        val pixels = IntArray(decoded.width * decoded.height)
                        decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                        assertTrue("The PDF image must retain the printed content", pixels.count { Color.red(it) > 200 && Color.green(it) < 80 } > 1000)
                    } finally { decoded.recycle() }
                }
            } finally { pdfFile.delete() }
        } finally { repository.deleteDocument(id); db.close(); context.deleteDatabase(name); file.delete() }
    }
}
