package com.docscan.android.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.processing.ScanFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
class DocumentRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: DocScanDatabase
    private lateinit var repository: DocumentRepository
    private lateinit var databaseName: String

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "test-${UUID.randomUUID()}.db"
        database = DocScanDatabase.open(context, databaseName)
        repository = DocumentRepository(context, database)
    }

    @After fun teardown() { database.close(); context.deleteDatabase(databaseName) }

    private fun image(): File = File(context.cacheDir, "source-${UUID.randomUUID()}.png").apply {
        Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(0xFFCCCCCC.toInt())
            outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun originalsAndEditsSurviveDatabaseReopen() = runBlocking {
        val id = repository.create("בדיקת סריקה")
        val original = image()
        val bytes = original.readBytes()
        val page = repository.importImage(id, Uri.fromFile(original))
        repository.rotate(page.id); repository.setFilter(page.id, ScanFilter.GRAYSCALE)
        assertArrayEquals(bytes, repository.source(page).readBytes())
        database.close()
        database = DocScanDatabase.open(context, databaseName)
        repository = DocumentRepository(context, database)
        val reopened = database.documents().pages(id).single()
        assertEquals(90, reopened.rotation)
        assertEquals("GRAYSCALE", reopened.filter)
        assertArrayEquals(bytes, repository.source(reopened).readBytes())
    }

    @Test fun retakeReplacesOnlyTheChosenPageAfterTheNewSourceIsSafe(): Unit = runBlocking {
        val id = repository.create("Retake")
        val first = repository.importImage(id, Uri.fromFile(image()))
        val second = repository.importImage(id, Uri.fromFile(image()))
        repository.rotate(first.id); repository.setFilter(first.id, ScanFilter.CLEAN_COLOR); repository.setFilterStrength(first.id, 35)
        val replacement = File(context.cacheDir, "retake-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(40, 50, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF112233.toInt())
        replacement.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val old = repository.source(first)
        val updated = repository.replaceImage(first.id, Uri.fromFile(replacement))
        assertEquals(first.id, updated.id); assertEquals(first.position, updated.position)
        assertEquals(0, updated.rotation); assertEquals("CLEAN_COLOR", updated.filter); assertEquals(35, updated.filterStrength)
        assertEquals(listOf(first.id, second.id), database.documents().pages(id).map { it.id })
        assertFalse(old.exists()); assertArrayEquals(replacement.readBytes(), repository.source(updated).readBytes())
        assertTrue(repository.source(second).exists())
        repository.deleteDocument(id); replacement.delete()
    }

    @Test fun failedRetakePreservesOldSourceAndAllEdits(): Unit = runBlocking {
        val id = repository.create("Failed retake")
        val page = repository.importImage(id, Uri.fromFile(image()))
        repository.rotate(page.id)
        val original = repository.source(page).readBytes()
        val broken = File(context.cacheDir, "bad-retake-${UUID.randomUUID()}.png").apply { writeText("not an image") }
        assertTrue(runCatching { repository.replaceImage(page.id, Uri.fromFile(broken)) }.isFailure)
        val retained = requireNotNull(database.documents().page(page.id))
        assertEquals(90, retained.rotation); assertEquals(page.sourceFile, retained.sourceFile)
        assertArrayEquals(original, repository.source(retained).readBytes())
        assertEquals(1, database.documents().pages(id).size)
        repository.deleteDocument(id); broken.delete()
    }

    @Test fun invalidInputDoesNotPublishAPage() = runBlocking {
        val id = repository.create("Draft")
        val broken = File(context.cacheDir, "invalid.png").apply { writeText("not an image") }
        val failure = runCatching { repository.importImage(id, Uri.fromFile(broken)) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(database.documents().pages(id).isEmpty())
        assertTrue(File(context.filesDir, "pending").listFiles().orEmpty().isEmpty())
    }

    @Test fun orderingAndDeletionKeepSourcesConsistent() = runBlocking {
        val id = repository.create("Pages")
        val first = repository.importImage(id, Uri.fromFile(image()))
        val second = repository.importImage(id, Uri.fromFile(image()))
        repository.move(second.id, -1)
        assertEquals(listOf(second.id, first.id), database.documents().pages(id).map { it.id })
        repository.deletePage(first.id)
        assertFalse(repository.source(first).exists())
        repository.deleteDocument(id)
        assertFalse(repository.source(second).exists())
        assertNull(database.documents().document(id))
        assertTrue(database.documents().pages(id).isEmpty())
    }

    @Test fun recoveryRemovesOnlyUnpublishedFiles() = runBlocking {
        val id = repository.create("Recovery")
        val page = repository.importImage(id, Uri.fromFile(image()))
        val orphan = File(repository.source(page).parentFile, "orphan.source").apply { writeText("orphan") }
        val pending = File(context.filesDir, "pending/abandoned.pending").apply { writeText("partial") }
        repository.recover()
        assertFalse(orphan.exists()); assertFalse(pending.exists())
        assertTrue(repository.source(page).exists())
        assertEquals(page.id, database.documents().pages(id).single().id)
    }
}
