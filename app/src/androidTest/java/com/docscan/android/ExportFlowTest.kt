package com.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscan.android.data.DocScanDatabase
import com.docscan.android.data.DocumentRepository
import com.docscan.android.export.PdfExporter
import com.docscan.android.processing.ScanFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires an emulator/device; validates the actual Android PDF/bitmap stack. */
@RunWith(AndroidJUnit4::class)
class ExportFlowTest {
    @Test fun twoImportedPagesProduceReadablePdfAndKeepOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "export-test-${UUID.randomUUID()}.db"
        val database = DocScanDatabase.open(context, databaseName)
        val repository = DocumentRepository(context, database)
        var documentId: String? = null
        val input = File(context.cacheDir, "fixture-${UUID.randomUUID()}.png")
        val output = File(context.cacheDir, "result-${UUID.randomUUID()}.pdf")
        try {
            Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(0xFFBB9977.toInt())
                input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val original = input.readBytes()
            val id = repository.create("מסמך בדיקה").also { documentId = it }
            val first = repository.importImage(id, Uri.fromFile(input))
            val second = repository.importImage(id, Uri.fromFile(input))
            repository.rotate(second.id); repository.setFilter(first.id, ScanFilter.GRAYSCALE)
            repository.readSnapshot(id) { _, pages -> PdfExporter.write(pages, repository, output) }
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { assertEquals(595, it.width); assertEquals(842, it.height) }
                renderer.openPage(1).use { assertEquals(842, it.width); assertEquals(595, it.height) }
            }
            assertArrayEquals(original, repository.source(first).readBytes())
            assertArrayEquals(original, repository.source(second).readBytes())
        } finally {
            documentId?.let { repository.deleteDocument(it) }
            database.close(); context.deleteDatabase(databaseName)
            input.delete(); output.delete()
        }
    }
}
