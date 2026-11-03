package com.docscan.android.processing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.data.DocScanDatabase
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.adjustments
import com.docscan.android.data.render
import com.docscan.android.data.withoutEdits
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
class ProfessionalEditingPersistenceTest {
    @Test fun formatAndAdjustmentsSurviveReopenAndRetakeWithoutChangingTheOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pro-edit-${UUID.randomUUID()}.db"
        var db = DocScanDatabase.open(context, name); var repository = DocumentRepository(context, db)
        val input = File(context.cacheDir, "pro-edit-${UUID.randomUUID()}.png")
        var id: String? = null
        try {
            val image = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888); image.eraseColor(Color.rgb(60, 100, 140))
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            val bytes = input.readBytes(); val doc = repository.create("Editing").also { id = it }
            val page = repository.importImage(doc, Uri.fromFile(input))
            val settings = ImageAdjustments(brightness = 20, contrast = 5, saturation = -100, sharpness = 40)
            repository.setAdjustments(page.id, settings)
            repository.setCropAndFormat(page.id, CropQuad.FULL, PageFormat.SQUARE)
            db.close(); db = DocScanDatabase.open(context, name); repository = DocumentRepository(context, db)
            val saved = requireNotNull(db.documents().page(page.id))
            assertEquals(settings, saved.adjustments()); assertEquals("SQUARE", saved.pageFormat)
            val output = saved.render(repository.source(saved), 1000)
            try {
                assertEquals(output.width, output.height)
                val pixel = output.getPixel(output.width / 2, output.height / 2)
                assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel), Color.blue(pixel))
            } finally { output.recycle() }
            val original = saved.withoutEdits().render(repository.source(saved), 1000)
            try { assertEquals(Color.rgb(60, 100, 140), original.getPixel(60, 80)); assertEquals(160, original.height) }
            finally { original.recycle() }
            assertArrayEquals(bytes, repository.source(saved).readBytes())
            val retaken = repository.replaceImage(saved.id, Uri.fromFile(input))
            assertEquals(settings, retaken.adjustments()); assertEquals("SQUARE", retaken.pageFormat)
            assertEquals(1, db.documents().pages(doc).size)
        } finally { id?.let { repository.deleteDocument(it) }; db.close(); context.deleteDatabase(name); input.delete() }
    }

    @Test fun onlyLocalCameraCapturesReceiveTheAutomaticDocumentPreset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "preset-${UUID.randomUUID()}.db"
        val db = DocScanDatabase.open(context, name); val repository = DocumentRepository(context, db)
        val input = File(context.cacheDir, "preset-${UUID.randomUUID()}.png"); var id: String? = null
        try {
            val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.GRAY)
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val doc = repository.create("Presets").also { id = it }
            val capture = repository.importImage(doc, Uri.fromFile(input), enhanceCapture = true)
            val gallery = repository.importImage(doc, Uri.fromFile(input))
            val processed = repository.importImage(doc, Uri.fromFile(input), detectBounds = false)
            assertEquals("CLEAN_COLOR", capture.filter); assertEquals(35, capture.sharpness)
            for (page in listOf(gallery, processed)) {
                assertEquals("ORIGINAL", page.filter); assertEquals(ImageAdjustments(), page.adjustments())
            }
            for (page in listOf(capture, gallery, processed)) assertArrayEquals(input.readBytes(), repository.source(page).readBytes())
        } finally { id?.let { repository.deleteDocument(it) }; db.close(); context.deleteDatabase(name); input.delete() }
    }

    @Test fun versionThreePagesMigrateWithNeutralAdjustmentsAndTheirExistingEditsIntact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "pro-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val crop = CropQuad(CropPoint(.1f, .1f), CropPoint(.9f, .1f), CropPoint(.9f, .9f), CropPoint(.1f, .9f)).encode()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE documents (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE pages (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, position INTEGER NOT NULL, sourceFile TEXT NOT NULL, rotation INTEGER NOT NULL, filter TEXT NOT NULL, crop TEXT, filterStrength INTEGER NOT NULL DEFAULT 70, FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_pages_documentId ON pages(documentId)")
            old.execSQL("INSERT INTO documents VALUES ('doc', 'Existing', 1, 1)")
            old.execSQL("INSERT INTO pages VALUES ('page', 'doc', 0, 'old.source', 90, 'CLEAN_TEXT', ?, 35)", arrayOf(crop))
            old.version = 3
        }
        val db = DocScanDatabase.open(context, name)
        try {
            val page = requireNotNull(db.documents().page("page"))
            assertEquals(ImageAdjustments(), page.adjustments()); assertEquals("NATURAL", page.pageFormat)
            assertEquals(crop, page.crop); assertEquals(90, page.rotation); assertEquals("CLEAN_TEXT", page.filter); assertEquals(35, page.filterStrength)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
