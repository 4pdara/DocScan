package com.docscan.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.docscan.android.processing.*
import com.docscan.android.export.PdfExporter
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: DocScanDatabase
    private lateinit var repository: DocumentRepository
    private val name = "archive-${UUID.randomUUID()}.db"
    private val temporary = mutableListOf<File>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = DocScanDatabase.open(context, name)
        repository = DocumentRepository(context, database)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    }
    @After fun cleanup(): Unit = runBlocking {
        database.documents().allDocuments().forEach { repository.deleteDocument(it.id) }
        temporary.forEach { it.deleteRecursively() }; database.close(); context.deleteDatabase(name); Unit
    }
    private fun file(extension: String) = File(context.cacheDir, "${UUID.randomUUID()}.$extension").also { temporary.add(it) }
    private suspend fun image(id: String): PageEntity {
        val input = file("png")
        val bitmap = Bitmap.createBitmap(90, 140, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 20..100) for (x in 20..50) setPixel(x, y, Color.BLUE)
        }
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return repository.importImage(id, Uri.fromFile(input), detectBounds = false)
    }
    private suspend fun fixture(): String {
        val id = repository.create("ספר עברי 123")
        val page = image(id); image(id)
        repository.rotate(page.id); repository.setFilter(page.id, ScanFilter.CLEAN_COLOR)
        repository.setFilterStrength(page.id, 37)
        repository.setAdjustments(page.id, ImageAdjustments(-8, 12, -15, 42))
        repository.setCropAndFormat(page.id, CropQuad.from(listOf(CropPoint(.08f,.04f), CropPoint(.91f,.09f),
            CropPoint(.95f,.94f), CropPoint(.06f,.96f))), PageFormat.A4)
        repository.move(page.id, 1)
        return id
    }
    private suspend fun backup(): ByteArray = ByteArrayOutputStream().also { repository.backup(it) }.toByteArray()
    private fun rewrite(bytes: ByteArray, edit: (MutableMap<String, ByteArray>) -> Unit): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        edit(entries)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> entries.forEach { (key, data) ->
                zip.putNextEntry(ZipEntry(key)); zip.write(data); zip.closeEntry()
            } }
        }.toByteArray()
    }
    private fun changeManifest(bytes: ByteArray, edit: (JSONObject) -> Unit) = rewrite(bytes) { entries ->
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        edit(manifest); entries["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
    }
    private fun originalNames() = File(context.filesDir, "originals").listFiles().orEmpty().map { it.name }.toSet()
    private suspend fun assertRejected(bytes: ByteArray) {
        val before = database.documents().allDocuments()
        val sources = originalNames()
        assertTrue(runCatching { repository.restore(bytes.inputStream()) }.isFailure)
        assertEquals(before, database.documents().allDocuments()); assertEquals(sources, originalNames())
        assertTrue(File(context.filesDir, "pending").listFiles().orEmpty().isEmpty())
    }

    @Test fun roundTripPreservesOriginalBytesEditsOrderAndEmptyDocuments(): Unit = runBlocking {
        val id = fixture(); repository.create("ספר עברי 123")
        val original = database.documents().document(id)!!
        val pages = database.documents().pages(id)
        val content = pages.map { repository.source(it).readBytes() }
        val archive = backup()
        assertEquals(ArchiveSummary(2, 2), repository.restore(archive.inputStream()))
        val restored = database.documents().allDocuments().filter { it.id != id && it.createdAt == original.createdAt }
            .first { database.documents().pages(it.id).isNotEmpty() }
        val copies = database.documents().pages(restored.id)
        assertEquals(original.copy(id = restored.id), restored)
        pages.zip(copies).forEachIndexed { index, (a, b) ->
            assertNotEquals(a.id, b.id); assertNotEquals(a.sourceFile, b.sourceFile)
            assertEquals(a.copy(id = b.id, documentId = b.documentId, position = index, sourceFile = b.sourceFile), b)
            assertArrayEquals(content[index], repository.source(b).readBytes())
            val before = a.render(repository.source(a), 300)
            val after = b.render(repository.source(b), 300)
            assertEquals(before.width, after.width); assertEquals(before.height, after.height)
            assertTrue(before.sameAs(after)); before.recycle(); after.recycle()
        }
        assertEquals(4, database.documents().allDocuments().size)
        repository.deleteDocument(id)
        assertTrue(copies.all { repository.source(it).exists() })
        assertEquals(ArchiveSummary(2, 2), repository.restore(archive.inputStream()))
        assertEquals(5, database.documents().allDocuments().size)
    }
    @Test fun pdfOriginalAndTextSurviveArchiveAndExport(): Unit = runBlocking {
        val input = file("pdf")
        PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 18f); it.newLineAtOffset(30f, 500f)
                it.showText("SEARCHABLE ORIGINAL"); it.endText()
            }; pdf.save(input)
        }
        val id = repository.create("PDF"); repository.importPdf(id, Uri.fromFile(input))
        val source = repository.source(database.documents().pages(id).single()).readBytes()
        repository.restore(backup().inputStream())
        val restored = database.documents().allDocuments().first { it.id != id }
        val page = database.documents().pages(restored.id).single()
        assertArrayEquals(source, repository.source(page).readBytes())
        val output = file("pdf"); PdfExporter.write(listOf(page), repository, output)
        PDDocument.load(output).use { assertTrue(PDFTextStripper().getText(it).contains("SEARCHABLE ORIGINAL")) }
    }
    @Test fun corruptedPayloadFailsBeforePublishing(): Unit = runBlocking {
        fixture(); val bytes = rewrite(backup()) { entries ->
            val key = entries.keys.first { it.startsWith("sources/") }
            entries[key] = entries.getValue(key).clone().apply { this[lastIndex / 2] = (this[lastIndex / 2].toInt() xor 4).toByte() }
        }; assertRejected(bytes)
    }
    @Test fun invalidMetadataAndFutureVersionsAreRejected(): Unit = runBlocking {
        fixture(); val good = backup()
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) }, { it.put("version", "1") }, { it.put("format", "other") },
            { it.getJSONArray("documents").getJSONObject(0).put("title", "x".repeat(121)) },
            { it.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0).put("rotation", 91) },
            { it.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0).put("filter", "UNKNOWN") },
            { it.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0).put("crop", "NaN,0,1,0,1,1,0,1") },
            { it.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0).put("sharpness", 101) },
            { it.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0).put("pageFormat", "UNKNOWN") },
            { it.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0).put("bytes", DocumentArchive.MAX_SOURCE_BYTES + 1) }
        )
        for (edit in edits) assertRejected(changeManifest(good, edit))
    }
    @Test fun traversalExtraMissingDuplicateAndTruncatedEntriesAreRejected(): Unit = runBlocking {
        fixture(); val good = backup()
        assertRejected(rewrite(good) { it["../escaped.source"] = byteArrayOf(1) })
        assertRejected(rewrite(good) { it["unexpected"] = byteArrayOf(1) })
        assertRejected(rewrite(good) { it.remove("sources/1.source") })
        val duplicate = good.toString(Charsets.ISO_8859_1).replace("sources/1.source", "sources/0.source").toByteArray(Charsets.ISO_8859_1)
        assertRejected(duplicate); assertRejected(good.copyOf(good.size - 10))
        assertFalse(File(context.filesDir, "escaped.source").exists())
    }
    @Test fun aMatchingHashCannotMakeAnInvalidImagePublishable(): Unit = runBlocking {
        fixture(); val broken = rewrite(backup()) { entries ->
            val payload = "not an image".toByteArray()
            entries["sources/0.source"] = payload
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            val page = manifest.getJSONArray("documents").getJSONObject(0).getJSONArray("pages").getJSONObject(0)
            page.put("bytes", payload.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
            entries["manifest.json"] = manifest.toString().toByteArray()
        }; assertRejected(broken)
    }
    @Test fun cancelBeforePublicationRemovesStagingAndKeepsLibrary(): Unit = runBlocking {
        fixture(); val bytes = backup(); val before = database.documents().allDocuments(); val files = originalNames()
        var cancel = false
        assertTrue(runCatching { repository.restore(bytes.inputStream(),
            checkpoint = { if (cancel) throw CancellationException("test") }, progress = { cancel = true }) }.isFailure)
        assertEquals(before, database.documents().allDocuments()); assertEquals(files, originalNames())
        assertTrue(File(context.filesDir, "pending").listFiles().orEmpty().isEmpty())
    }
    @Test fun lifecycleCancellationAtCommitCannotLeaveMissingOriginals(): Unit = runBlocking {
        fixture(); val bytes = backup()
        lateinit var restoreJob: Job
        restoreJob = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            repository.restore(bytes.inputStream(), progress = { if (it.finishing) restoreJob.cancel() })
        }
        restoreJob.start(); restoreJob.join()
        assertEquals(2, database.documents().allDocuments().size)
        repository.recover()
        assertTrue(database.documents().allDocuments().flatMap { database.documents().pages(it.id) }.all { repository.source(it).isFile })
    }
    @Test fun oversizedDeepAndInterruptedInputsLeaveNoChanges(): Unit = runBlocking {
        fixture(); val good = backup()
        assertRejected(rewrite(good) { it["manifest.json"] = ("[".repeat(100) + "]".repeat(100)).toByteArray() })
        assertRejected(rewrite(good) { it["manifest.json"] = ByteArray((DocumentArchive.MAX_MANIFEST_BYTES + 1).toInt()) })
        val before = originalNames()
        val broken = object : InputStream() { override fun read(): Int = throw IOException("Disconnected") }
        assertTrue(runCatching { repository.restore(broken) }.isFailure)
        assertEquals(before, originalNames()); assertEquals(1, database.documents().allDocuments().size)
    }
}
