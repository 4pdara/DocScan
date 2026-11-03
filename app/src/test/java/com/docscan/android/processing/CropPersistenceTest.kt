package com.docscan.android.processing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.data.DocScanDatabase
import com.docscan.android.data.DocumentRepository
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
class CropPersistenceTest {
    @Test fun perspectiveMappingKeepsAllFourCornerColorsInOrder() {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val quad = CropQuad(CropPoint(.2f, .1f), CropPoint(.85f, .2f), CropPoint(.8f, .9f), CropPoint(.1f, .8f))
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val canvas = Canvas(bitmap)
        quad.points.zip(colors).forEach { (p, color) ->
            canvas.drawCircle(p.x * 320, p.y * 240, 25f, Paint().apply { this.color = color })
        }
        val result = PerspectiveCrop.apply(bitmap, quad, 1000)
        try {
            val samples = listOf(8 to 8, result.width - 9 to 8, result.width - 9 to result.height - 9, 8 to result.height - 9)
            samples.zip(colors).forEach { (p, color) -> assertEquals(color, result.getPixel(p.first, p.second)) }
        } finally { bitmap.recycle(); result.recycle() }
    }

    @Test fun autoCropManualOverrideResetAndReopenPreserveOriginalBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "crop-${UUID.randomUUID()}.db"
        var database = DocScanDatabase.open(context, name)
        var repository = DocumentRepository(context, database)
        val input = File(context.cacheDir, "crop-source-${UUID.randomUUID()}.png")
        var id: String? = null
        try {
            val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.DKGRAY)
            Canvas(bitmap).drawRect(40f, 40f, 260f, 360f, Paint().apply { color = Color.WHITE })
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val original = input.readBytes()
            val document = repository.create("Crop").also { id = it }
            val page = repository.importImage(document, Uri.fromFile(input))
            assertNotNull(CropQuad.decode(page.crop))
            val manual = CropQuad(CropPoint(.25f, .2f), CropPoint(.75f, .2f), CropPoint(.75f, .8f), CropPoint(.25f, .8f))
            repository.setCrop(page.id, manual); repository.rotate(page.id)
            database.close(); database = DocScanDatabase.open(context, name); repository = DocumentRepository(context, database)
            val reopened = database.documents().pages(document).single()
            assertEquals(manual, CropQuad.decode(reopened.crop)); assertEquals(90, reopened.rotation)
            val rendered = ImagePipeline.render(repository.source(reopened), 90, ScanFilter.ORIGINAL, 1000, manual)
            assertEquals(240, rendered.width); assertEquals(150, rendered.height); rendered.recycle()
            repository.setCrop(page.id, null)
            assertNull(database.documents().page(page.id)?.crop)
            assertArrayEquals(original, repository.source(reopened).readBytes())
        } finally {
            id?.let { repository.deleteDocument(it) }; database.close(); context.deleteDatabase(name); input.delete()
        }
    }

    @Test fun versionOneDraftMigratesWithoutLosingPagesOrSources() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val source = File(context.filesDir, "originals/migration-${UUID.randomUUID()}.source").apply { parentFile?.mkdirs(); writeText("unchanged-original") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE documents (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE pages (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, position INTEGER NOT NULL, sourceFile TEXT NOT NULL, rotation INTEGER NOT NULL, filter TEXT NOT NULL, FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_pages_documentId ON pages(documentId)")
            old.execSQL("INSERT INTO documents VALUES ('doc', 'Existing', 1, 1)")
            old.execSQL("INSERT INTO pages VALUES ('page', 'doc', 0, ?, 90, 'GRAYSCALE')", arrayOf(source.name))
            old.version = 1
        }
        val database = DocScanDatabase.open(context, name)
        try {
            val page = requireNotNull(database.documents().page("page"))
            assertNull(page.crop); assertEquals(90, page.rotation); assertEquals("GRAYSCALE", page.filter)
            assertEquals("unchanged-original", DocumentRepository(context, database).source(page).readText())
        } finally { database.close(); context.deleteDatabase(name); source.delete() }
    }
}
