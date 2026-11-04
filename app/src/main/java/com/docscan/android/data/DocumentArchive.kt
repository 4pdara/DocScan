package com.docscan.android.data

import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ImageAdjustments
import com.docscan.android.processing.PageFormat
import com.docscan.android.processing.ScanFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ArchiveSummary(val documents: Int, val pages: Int)
data class ArchiveProgress(val completed: Int = 0, val total: Int = 0, val finishing: Boolean = false)
internal data class ArchiveDocument(val metadata: DocumentEntity, val pages: List<PageEntity>)
internal data class RestoredPage(val settings: PageEntity, val file: File)
internal data class RestoredDocument(val metadata: DocumentEntity, val pages: List<RestoredPage>)

/** A versioned exchange format, independent of Room's schema and Android's automatic backup. */
internal object DocumentArchive {
    const val MAX_DOCUMENTS = 1_000
    const val MAX_PAGES = 10_000
    const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES + 16L * 1024 * 1024
    private const val MANIFEST = "manifest.json"
    private val pageKeys = setOf("entry", "bytes", "sha256", "rotation", "filter", "crop", "filterStrength",
        "brightness", "contrast", "saturation", "sharpness", "pageFormat")

    fun write(documents: List<ArchiveDocument>, output: OutputStream, source: (PageEntity) -> File,
        checkpoint: () -> Unit, progress: (ArchiveProgress) -> Unit): ArchiveSummary {
        require(documents.size in 1..MAX_DOCUMENTS)
        val pages = documents.flatMap { it.pages }
        require(pages.size <= MAX_PAGES)
        var total = 0L
        var index = 0
        val entries = mutableListOf<Pair<String, File>>()
        val docs = JSONArray()
        documents.forEach { document ->
            checkpoint()
            val encodedPages = JSONArray()
            document.pages.forEach { page ->
                val file = source(page)
                val bytes = file.length()
                require(file.isFile && bytes in 1..MAX_SOURCE_BYTES)
                total += bytes; require(total <= MAX_TOTAL_BYTES)
                val name = "sources/${index}.${if (file.extension == "pdf") "pdf" else "source"}"
                val hash = MessageDigest.getInstance("SHA-256")
                require(file.inputStream().use { copy(it, null, bytes, checkpoint, hash) } == bytes)
                encodedPages.put(encodePage(page).put("entry", name).put("bytes", bytes).put("sha256", hex(hash.digest())))
                entries.add(name to file)
                index++; progress(ArchiveProgress(index, pages.size * 2))
            }
            docs.put(JSONObject().put("title", document.metadata.title).put("createdAt", document.metadata.createdAt)
                .put("updatedAt", document.metadata.updatedAt).put("pages", encodedPages))
        }
        val manifest = JSONObject().put("format", "docscan-archive").put("version", 1)
            .put("createdAt", System.currentTimeMillis()).put("documents", docs).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES)
        // The caller owns the output and can sync it after ZIP finalization.
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
            override fun close() { flush() }
        }).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
            entries.forEachIndexed { i, (name, file) ->
                checkpoint(); zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { copy(it, zip, file.length(), checkpoint) }
                zip.closeEntry(); progress(ArchiveProgress(pages.size + i + 1, pages.size * 2))
            }
        }
        return ArchiveSummary(documents.size, pages.size)
    }

    /** Uses ZipFile to reject truncated archives, never extracts an input-controlled path. */
    fun read(input: InputStream, directory: File, checkpoint: () -> Unit,
        progress: (ArchiveProgress) -> Unit): List<RestoredDocument> {
        require(directory.isDirectory && directory.listFiles().orEmpty().isEmpty())
        val archive = File(directory, "input.zip")
        FileOutputStream(archive).use { copy(input, it, MAX_ARCHIVE_BYTES, checkpoint); it.fd.sync() }
        validateDirectoryRecord(archive)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().take(MAX_PAGES + 2).toList()
            require(entries.size in 1..MAX_PAGES + 1 && entries.none { it.isDirectory })
            require(entries.map { it.name }.toSet().size == entries.size)
            val manifestEntry = requireNotNull(zip.getEntry(MANIFEST))
            require(manifestEntry.size in 1..MAX_MANIFEST_BYTES)
            val bytes = zip.getInputStream(manifestEntry).use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                copy(stream, buffer, MAX_MANIFEST_BYTES, checkpoint); buffer.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            boundedDepth(text)
            val root = JSONObject(text).keysExactly(setOf("format", "version", "createdAt", "documents"))
            require(root.string("format") == "docscan-archive" && root.integer("version") == 1L)
            require(root.integer("createdAt") >= 0)
            val docs = root.getJSONArray("documents")
            require(docs.length() in 1..MAX_DOCUMENTS)
            var total = 0L
            var count = 0
            val expected = linkedMapOf<String, Pair<Long, String>>()
            val documents = (0 until docs.length()).map { d ->
                checkpoint()
                val doc = docs.getJSONObject(d).keysExactly(setOf("title", "createdAt", "updatedAt", "pages"))
                val title = doc.string("title"); require(title.length <= 120)
                val createdAt = doc.integer("createdAt"); val updatedAt = doc.integer("updatedAt")
                require(createdAt >= 0 && updatedAt >= 0)
                val documentId = UUID.randomUUID().toString()
                val pageArray = doc.getJSONArray("pages")
                require(pageArray.length() <= MAX_PAGES - count)
                val pages = (0 until pageArray.length()).map { p ->
                    val item = pageArray.getJSONObject(p).keysExactly(pageKeys)
                    val entry = item.string("entry")
                    require(entry == "sources/$count.source" || entry == "sources/$count.pdf")
                    val size = item.integer("bytes"); require(size in 1..MAX_SOURCE_BYTES)
                    total += size; require(total <= MAX_TOTAL_BYTES)
                    val hash = item.string("sha256"); require(hash.matches(Regex("[0-9a-f]{64}")))
                    expected[entry] = size to hash
                    val target = File(directory, "page-${count++}.${if (entry.endsWith(".pdf")) "pdf" else "source"}")
                    RestoredPage(decodePage(item, documentId, p, target.name), target)
                }
                RestoredDocument(DocumentEntity(documentId, title, createdAt, updatedAt), pages)
            }
            require(entries.map { it.name }.toSet() == expected.keys + MANIFEST)
            val pages = documents.flatMap { it.pages }
            expected.entries.zip(pages).forEachIndexed { i, (item, page) ->
                checkpoint()
                val entry = requireNotNull(zip.getEntry(item.key))
                require(entry.size == item.value.first)
                val hash = MessageDigest.getInstance("SHA-256")
                val size = zip.getInputStream(entry).use { stream ->
                    FileOutputStream(page.file).use { output ->
                        copy(stream, output, item.value.first, checkpoint, hash).also { output.fd.sync() }
                    }
                }
                require(size == item.value.first && hex(hash.digest()) == item.value.second)
                progress(ArchiveProgress(i + 1, pages.size))
            }
            return documents
        }
    }

    private fun encodePage(page: PageEntity) = JSONObject().put("rotation", page.rotation).put("filter", page.filter)
        .put("crop", page.crop ?: JSONObject.NULL).put("filterStrength", page.filterStrength)
        .put("brightness", page.brightness).put("contrast", page.contrast).put("saturation", page.saturation)
        .put("sharpness", page.sharpness).put("pageFormat", page.pageFormat)

    private fun decodePage(item: JSONObject, documentId: String, position: Int, source: String): PageEntity {
        val rotation = item.integer("rotation"); require(rotation in listOf(0L, 90L, 180L, 270L))
        val filter = item.string("filter"); require(ScanFilter.entries.any { it.name == filter })
        val format = item.string("pageFormat"); require(PageFormat.entries.any { it.name == format })
        val crop = if (item.get("crop") === JSONObject.NULL) null else item.string("crop").also {
            require(it.length <= 200 && CropQuad.decode(it) != null)
        }
        fun setting(name: String, range: LongRange): Int = item.integer(name).also { require(it in range) }.toInt()
        val strength = setting("filterStrength", 0L..100L)
        val adjustments = ImageAdjustments(setting("brightness", -100L..100L), setting("contrast", -100L..100L),
            setting("saturation", -100L..100L), setting("sharpness", 0L..100L))
        return PageEntity(UUID.randomUUID().toString(), documentId, position, source, rotation.toInt(), filter, crop,
            strength, adjustments.brightness, adjustments.contrast, adjustments.saturation, adjustments.sharpness, format)
    }

    internal fun copy(input: InputStream, output: OutputStream?, limit: Long, checkpoint: () -> Unit,
        digest: MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n < 0) break
            total += n; require(total <= limit) { "Archive size limit exceeded" }
            digest?.update(buffer, 0, n); output?.write(buffer, 0, n)
        }
        return total
    }

    private fun JSONObject.string(key: String) = requireNotNull(get(key) as? String)
    private fun JSONObject.integer(key: String): Long {
        val value = get(key); require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    private fun JSONObject.keysExactly(expected: Set<String>): JSONObject {
        require(keys().asSequence().toSet() == expected); return this
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    // Our format has no ZIP comments, multi-disk or ZIP64 records. Bound metadata before ZipFile allocates it.
    private fun validateDirectoryRecord(file: File) {
        require(file.length() >= 22)
        RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - 22)
            val end = ByteArray(22); input.readFully(end)
            val data = java.nio.ByteBuffer.wrap(end).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            require(data.int == 0x06054b50 && data.short.toInt() == 0 && data.short.toInt() == 0)
            val count = data.short.toInt() and 0xffff
            require(count in 1..MAX_PAGES + 1 && (data.short.toInt() and 0xffff) == count)
            val size = data.int.toLong() and 0xffffffffL
            val offset = data.int.toLong() and 0xffffffffL
            require(size in 1..MAX_MANIFEST_BYTES && offset + size == file.length() - 22 && data.short.toInt() == 0)
        }
    }

    private fun boundedDepth(text: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (escaped) { escaped = false; continue }
            if (quoted && c == '\\') { escaped = true; continue }
            if (c == '"') quoted = !quoted
            if (!quoted) {
                if (c == '{' || c == '[') { depth++; require(depth <= 12) }
                if (c == '}' || c == ']') { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }
}
