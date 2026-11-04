package com.docscan.android.processing

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.data.*
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
                val pixels = IntArray(output.width * output.height)
                output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                assertTrue("Desk must not remain outside the detected corners", pixels.count { Color.red(it) < 100 } < pixels.size * .01f)
            } finally { output.recycle() }
            assertArrayEquals(original, repository.source(page).readBytes())
            assertEquals(page.crop, db.documents().page(page.id)!!.crop)
        } finally { repository.deleteDocument(id); db.close(); context.deleteDatabase(name); file.delete() }
    }
}
