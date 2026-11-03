package com.docscan.android.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ScanFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

    suspend fun recover() = withContext(Dispatchers.IO) {
        lock.withLock {
            staging.listFiles()?.forEach { it.delete() }
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

    suspend fun importImage(documentId: String, uri: Uri): PageEntity = withContext(Dispatchers.IO) {
        lock.withLock {
            val input = requireNotNull(appContext.contentResolver.openInputStream(uri)) { "Image unavailable" }
            input.use { publishImage(documentId, it) }
        }
    }

    private suspend fun publishImage(documentId: String, input: InputStream): PageEntity {
        requireNotNull(dao.document(documentId)) { "Document no longer exists" }
        val id = UUID.randomUUID().toString()
        val temporary = File(staging, "$id.pending")
        val destination = File(originals, "$id.source")
        try {
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
            val crop = try { ImagePipeline.detectCrop(temporary) } catch (_: Exception) { null }
            check(temporary.renameTo(destination)) { "Cannot save original" }
            return database.withTransaction {
                val position = (dao.pages(documentId).maxOfOrNull { it.position } ?: -1) + 1
                val page = PageEntity(id, documentId, position, destination.name, crop = crop?.encode())
                dao.insertPage(page)
                dao.touch(documentId, System.currentTimeMillis())
                page
            }
        } catch (failure: Exception) {
            temporary.delete(); destination.delete()
            throw failure
        }
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        require(title.isNotBlank())
        dao.rename(id, title.trim().take(120), System.currentTimeMillis())
    }

    suspend fun rotate(pageId: String) = updatePage(pageId) { it.copy(rotation = (it.rotation + 90) % 360) }
    suspend fun setFilter(pageId: String, filter: ScanFilter) = updatePage(pageId) { it.copy(filter = filter.name) }
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
