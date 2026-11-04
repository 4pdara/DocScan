package com.docscan.android.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.DetectionMode
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ScanFilter
import com.docscan.android.processing.ImageAdjustments
import com.docscan.android.processing.PageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.docscan.android.processing.PdfSources
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class DocumentRepository(context: Context, private val database: DocScanDatabase) {
    private val appContext = context.applicationContext
    private val dao = database.documents()
    private val lock = Mutex()
    private val originals = File(appContext.filesDir, "originals").apply { mkdirs() }
    private val staging = File(appContext.filesDir, "pending").apply { mkdirs() }

    fun observeDocuments() = dao.observeDocuments()
    fun observeDocument(id: String) = dao.observeDocument(id)
    fun observePages(id: String) = dao.observePages(id)
    fun source(page: PageEntity) = File(originals, page.sourceFile)

    suspend fun backup(output: OutputStream, checkpoint: () -> Unit = {}, progress: (ArchiveProgress) -> Unit = {}): ArchiveSummary =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            lock.withLock {
                val snapshot = database.withTransaction {
                    val documents = dao.allDocuments()
                    require(documents.size <= DocumentArchive.MAX_DOCUMENTS)
                    var count = 0
                    documents.map {
                        val pages = dao.pages(it.id)
                        count += pages.size; require(count <= DocumentArchive.MAX_PAGES)
                        ArchiveDocument(it, pages)
                    }
                }
                DocumentArchive.write(snapshot, output, ::source, { coroutine.ensureActive(); checkpoint() }, progress)
            }
        }

    suspend fun restore(input: InputStream, checkpoint: () -> Unit = {}, progress: (ArchiveProgress) -> Unit = {}): ArchiveSummary =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            lock.withLock {
                val directory = File(staging, "restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val published = mutableListOf<File>()
                var committed = false
                fun checkActive() { coroutine.ensureActive(); checkpoint() }
                try {
                    val documents = DocumentArchive.read(input, directory, ::checkActive, progress)
                    documents.flatMap { it.pages }.forEach { page ->
                        checkActive()
                        if (PdfSources.isPdf(page.file)) {
                            com.tom_roush.pdfbox.pdmodel.PDDocument.load(page.file, PdfSources.memory(directory)).use {
                                require(!it.isEncrypted && it.numberOfPages == 1)
                            }
                        } else ImagePipeline.validate(page.file)
                    }
                    checkActive()
                    // A cancellation cannot separate the database commit from publication/cleanup.
                    withContext(NonCancellable) {
                        progress(ArchiveProgress(finishing = true))
                        val pages = documents.flatMap { it.pages }.map { page ->
                            val target = File(originals, "${page.settings.id}.${page.file.extension}")
                            check(!target.exists() && page.file.renameTo(target)); published.add(target)
                            page.settings.copy(sourceFile = target.name)
                        }
                        database.withTransaction {
                            documents.forEach { dao.insertDocument(it.metadata) }
                            pages.forEach { dao.insertPage(it) }
                        }
                        committed = true
                        ArchiveSummary(documents.size, pages.size)
                    }
                } catch (failure: Exception) {
                    if (!committed) published.forEach { it.delete() }
                    throw failure
                } finally { directory.deleteRecursively() }
            }
        }

    suspend fun recover() = withContext(Dispatchers.IO) {
        lock.withLock {
            staging.listFiles()?.forEach { it.deleteRecursively() }
            val retained = dao.sourceFiles().toSet()
            originals.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
        }
    }

    suspend fun create(title: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertDocument(DocumentEntity(id, title.trim().take(120), now, now))
        id
    }

    suspend fun importImage(documentId: String, uri: Uri, detectBounds: Boolean = true, enhanceCapture: Boolean = false, detectionMode: DetectionMode = DetectionMode.STANDARD): PageEntity = withContext(Dispatchers.IO) {
        lock.withLock {
            val input = requireNotNull(appContext.contentResolver.openInputStream(uri)) { "Image unavailable" }
            input.use { publishImage(documentId, it, detectBounds = detectBounds, enhanceCapture = enhanceCapture, detectionMode = detectionMode) }
        }
    }

    suspend fun replaceImage(pageId: String, uri: Uri, detectionMode: DetectionMode = DetectionMode.STANDARD): PageEntity = withContext(Dispatchers.IO) {
        lock.withLock {
            val page = requireNotNull(dao.page(pageId))
            requireNotNull(appContext.contentResolver.openInputStream(uri)).use { publishImage(page.documentId, it, page, detectionMode = detectionMode) }
        }
    }

    private suspend fun publishImage(documentId: String, input: InputStream, replacement: PageEntity? = null,
        detectBounds: Boolean = true, enhanceCapture: Boolean = false, detectionMode: DetectionMode = DetectionMode.STANDARD): PageEntity {
        requireNotNull(dao.document(documentId)) { "Document no longer exists" }
        val id = UUID.randomUUID().toString()
        val temporary = File(staging, "$id.pending")
        val destination = File(originals, "$id.source")
        val published = try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    total += size
                    require(total <= ImagePipeline.MAX_SOURCE_BYTES) { "Image too large" }
                    output.write(buffer, 0, size)
                }
                output.fd.sync()
            }
            ImagePipeline.validate(temporary)
            val crop = if (detectBounds) try { ImagePipeline.detectCrop(temporary, detectionMode) } catch (_: Exception) { null } else null
            check(temporary.renameTo(destination)) { "Cannot save original" }
            database.withTransaction {
                val position = (dao.pages(documentId).maxOfOrNull { it.position } ?: -1) + 1
                val page = replacement?.copy(sourceFile = destination.name, rotation = 0, crop = crop?.encode())
                    ?: PageEntity(id, documentId, position, destination.name, crop = crop?.encode(),
                        filter = if (enhanceCapture) ScanFilter.CLEAN_COLOR.name else ScanFilter.ORIGINAL.name,
                        sharpness = if (enhanceCapture) 35 else 0)
                if (replacement == null) dao.insertPage(page) else dao.updatePage(page)
                dao.touch(documentId, System.currentTimeMillis())
                page
            }
        } catch (failure: Exception) {
            temporary.delete(); destination.delete()
            throw failure
        }
        // The old source remains available until both the new file and database update succeed.
        if (replacement != null) runCatching { source(replacement).delete() }
        return published
    }

    suspend fun importPdf(documentId: String, uri: Uri): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            requireNotNull(dao.document(documentId))
            val coroutine = currentCoroutineContext()
            val directory = File(staging, UUID.randomUUID().toString()).apply { check(mkdirs()) }
            val published = mutableListOf<File>()
            try {
                val input = File(directory, "input.pdf")
                requireNotNull(appContext.contentResolver.openInputStream(uri)).use {
                    PdfSources.copyBounded(it, input) { coroutine.ensureActive() }
                }
                val pieces = PdfSources.split(input, directory) { coroutine.ensureActive() }
                val position = (dao.pages(documentId).maxOfOrNull { it.position } ?: -1) + 1
                val pages = pieces.mapIndexed { index, piece ->
                    coroutine.ensureActive()
                    FileOutputStream(piece, true).use { it.fd.sync() }
                    val id = UUID.randomUUID().toString()
                    val target = File(originals, "$id.pdf")
                    check(piece.renameTo(target)); published.add(target)
                    PageEntity(id, documentId, position + index, target.name)
                }
                database.withTransaction {
                    pages.forEach { dao.insertPage(it) }
                    dao.touch(documentId, System.currentTimeMillis())
                }
                pages.size
            } catch (failure: Exception) { published.forEach { it.delete() }; throw failure }
            finally { directory.deleteRecursively() }
        }
    }

    suspend fun mergeDocuments(ids: List<String>, title: String): String = withContext(Dispatchers.IO) {
        require(ids.size >= 2 && ids.distinct().size == ids.size)
        require(title.isNotBlank())
        lock.withLock {
            ids.forEach { requireNotNull(dao.document(it)) }
            val pages = ids.flatMap { dao.pages(it) }
            require(pages.isNotEmpty() && pages.size <= 1000)
            val id = UUID.randomUUID().toString()
            val copies = mutableListOf<File>()
            try {
                val newPages = pages.mapIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    val pageId = UUID.randomUUID().toString()
                    val target = File(originals, "$pageId.${source(page).extension}")
                    copies.add(target)
                    source(page).inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
                    page.copy(id = pageId, documentId = id, position = index, sourceFile = target.name)
                }
                database.withTransaction {
                    val now = System.currentTimeMillis()
                    dao.insertDocument(DocumentEntity(id, title.trim().take(120), now, now))
                    newPages.forEach { dao.insertPage(it) }
                }
                id
            } catch (failure: Exception) { copies.forEach { it.delete() }; throw failure }
        }
    }

    fun selectedPages(pages: List<PageEntity>, ids: List<String>?): List<PageEntity> {
        if (ids == null) return pages
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        val selected = pages.filter { it.id in ids }
        require(selected.size == ids.size) { "Selection is no longer available" }
        return selected
    }

    private suspend fun updatePages(documentId: String, ids: List<String>, transform: (PageEntity) -> PageEntity) = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                val pages = selectedPages(dao.pages(documentId), ids)
                pages.forEach { dao.updatePage(transform(it)) }
                dao.touch(documentId, System.currentTimeMillis())
            }
        }
    }
    suspend fun filterPages(documentId: String, ids: List<String>, filter: ScanFilter, strength: Int) {
        require(strength in 0..100)
        updatePages(documentId, ids) { it.copy(filter = filter.name, filterStrength = strength) }
    }
    suspend fun rotatePages(documentId: String, ids: List<String>) = updatePages(documentId, ids) { it.copy(rotation = (it.rotation + 90) % 360) }
    suspend fun copyFilterToDocument(pageId: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                val source = requireNotNull(dao.page(pageId))
                val pages = dao.pages(source.documentId)
                pages.forEach { dao.updatePage(it.copy(filter = source.filter, filterStrength = source.filterStrength)) }
                dao.touch(source.documentId, System.currentTimeMillis()); pages.size
            }
        }
    }
    suspend fun deletePages(documentId: String, ids: List<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = dao.pages(documentId)
            val removed = selectedPages(all, ids)
            database.withTransaction {
                removed.forEach { dao.deletePage(it.id) }
                all.filter { it.id !in ids }.forEachIndexed { index, page -> dao.updatePage(page.copy(position = index)) }
                dao.touch(documentId, System.currentTimeMillis())
            }
            removed.forEach { source(it).delete() }
        }
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        require(title.isNotBlank())
        dao.rename(id, title.trim().take(120), System.currentTimeMillis())
    }

    suspend fun rotate(pageId: String) = updatePage(pageId) { it.copy(rotation = (it.rotation + 90) % 360) }
    suspend fun setFilter(pageId: String, filter: ScanFilter) = updatePage(pageId) { it.copy(filter = filter.name) }
    suspend fun setFilterStrength(pageId: String, strength: Int) {
        require(strength in 0..100)
        updatePage(pageId) { it.copy(filterStrength = strength) }
    }
    suspend fun setAdjustments(pageId: String, settings: ImageAdjustments) = updatePage(pageId) {
        it.copy(brightness = settings.brightness, contrast = settings.contrast,
            saturation = settings.saturation, sharpness = settings.sharpness)
    }
    suspend fun setCropAndFormat(pageId: String, crop: CropQuad?, format: PageFormat) {
        require(crop == null || crop.isValid())
        updatePage(pageId) { it.copy(crop = crop?.encode(), pageFormat = format.name) }
    }
    suspend fun setCrop(pageId: String, crop: CropQuad?) {
        require(crop == null || crop.isValid())
        updatePage(pageId) { it.copy(crop = crop?.encode()) }
    }

    private suspend fun updatePage(id: String, transform: (PageEntity) -> PageEntity) = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                val current = requireNotNull(dao.page(id))
                dao.updatePage(transform(current)); dao.touch(current.documentId, System.currentTimeMillis())
            }
        }
    }

    suspend fun move(pageId: String, delta: Int) = withContext(Dispatchers.IO) {
        require(delta == -1 || delta == 1)
        lock.withLock {
            database.withTransaction {
                val page = requireNotNull(dao.page(pageId))
                val pages = dao.pages(page.documentId).toMutableList()
                val from = pages.indexOfFirst { it.id == pageId }
                val to = from + delta
                if (to in pages.indices) {
                    java.util.Collections.swap(pages, from, to)
                    pages.forEachIndexed { index, item -> dao.updatePage(item.copy(position = index)) }
                    dao.touch(page.documentId, System.currentTimeMillis())
                }
            }
        }
    }

    suspend fun deletePage(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val page = requireNotNull(dao.page(id))
            database.withTransaction { dao.deletePage(id); dao.touch(page.documentId, System.currentTimeMillis()) }
            source(page).delete()
        }
    }

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val pages = dao.pages(id)
            dao.deleteDocument(id)
            pages.forEach { source(it).delete() }
        }
    }

    /** Hold the mutation lock so removal cannot invalidate files during an export. */
    suspend fun <T> readSnapshot(id: String, block: (DocumentEntity, List<PageEntity>) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val snapshot = database.withTransaction {
                    requireNotNull(dao.document(id)) to dao.pages(id)
                }
                require(snapshot.second.isNotEmpty()) { "Document has no pages" }
                block(snapshot.first, snapshot.second)
            }
        }
}
