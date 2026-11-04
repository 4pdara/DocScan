package com.docscan.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docscan.android.data.PageEntity
import com.docscan.android.export.GalleryExporter
import com.docscan.android.export.ShareExport
import com.docscan.android.export.ShareFormat
import android.content.Intent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import com.docscan.android.export.PdfExporter
import com.docscan.android.export.JpegExporter
import com.docscan.android.processing.ScanFilter
import com.docscan.android.processing.DetectionMode
import com.docscan.android.processing.CropQuad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(SavedStateHandleSaveableApi::class)
class DocScanViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    // Routes and pending picker targets must survive Activity replacement independently of UI loading.
    var screen by savedState.saveable { mutableStateOf("home") }
    var documentId by savedState.saveable { mutableStateOf<String?>(null) }
    var pageId by savedState.saveable { mutableStateOf<String?>(null) }
    var retakePageId by savedState.saveable { mutableStateOf<String?>(null) }
    var returnToCamera by savedState.saveable { mutableStateOf(false) }
    var importTarget by savedState.saveable { mutableStateOf<String?>(null) }
    var exportTarget by savedState.saveable { mutableStateOf<String?>(null) }
    var imageExportTarget by savedState.saveable { mutableStateOf<String?>(null) }
    var cropPoints by savedState.saveable { mutableStateOf<String?>(null) }
    var cropFormat by savedState.saveable { mutableStateOf("NATURAL") }
    var cropRectangle by savedState.saveable { mutableStateOf(false) }
    var cropDetectOnOpen by savedState.saveable { mutableStateOf(false) }
    var googleReviewDocumentId by savedState.saveable { mutableStateOf<String?>(null) }

    var outputPageIds by savedState.saveable { mutableStateOf<List<String>>(arrayListOf()) }
    var galleryTarget by savedState.saveable { mutableStateOf<String?>(null) }
    var selectedDocuments by savedState.saveable { mutableStateOf<List<String>>(arrayListOf()) }
    var selectedPageIds by savedState.saveable { mutableStateOf<List<String>>(arrayListOf()) }
    var selectingDocuments by savedState.saveable { mutableStateOf(false) }
    var selectingPages by savedState.saveable { mutableStateOf(false) }
    var libraryQuery by savedState.saveable { mutableStateOf("") }
    var librarySort by savedState.saveable { mutableStateOf("UPDATED") }
    var libraryGrid by savedState.saveable { mutableStateOf(false) }
    var documentGrid by savedState.saveable { mutableStateOf(false) }

    fun openDocument(id: String) {
        documentId = id; screen = "document"; returnToCamera = false
        selectedPageIds = arrayListOf(); selectingPages = false
    }
    fun togglePage(id: String) { selectedPageIds = ArrayList(if (id in selectedPageIds) selectedPageIds - id else selectedPageIds + id) }
    fun toggleDocument(id: String) { selectedDocuments = ArrayList(if (id in selectedDocuments) selectedDocuments - id else selectedDocuments + id) }

    fun beginCrop(page: PageEntity, detectBounds: Boolean = false) {
        cropPoints = page.crop; cropFormat = page.pageFormat; cropRectangle = false
        cropDetectOnOpen = detectBounds
        pageId = page.id; documentId = page.documentId; screen = "crop"
    }

    fun clearCropDraft() {
        cropPoints = null; cropFormat = "NATURAL"; cropRectangle = false; cropDetectOnOpen = false
    }

    val repository = (application as DocScanApplication).repository
    private val app = application
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val documents = repository.observeDocuments().catch {
        _message.value = app.getString(R.string.load_failed); emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        task {
            repository.recover()
            withContext(Dispatchers.IO) {
                val expiration = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                File(app.cacheDir, "exports").listFiles()?.filter { it.lastModified() < expiration }?.forEach { it.deleteRecursively() }
                app.cacheDir.listFiles()?.filter { it.name.startsWith("capture-") && it.lastModified() < expiration }?.forEach { it.delete() }
            }
        }
    }

    fun clearMessage() { _message.value = null }
    fun cameraError() { _message.value = app.getString(R.string.camera_failed) }
    fun googleScannerError() { _message.value = app.getString(R.string.google_scan_failed) }

    fun create(onCreated: (String) -> Unit) = task { onCreated(repository.create(defaultTitle())) }

    fun importImages(uris: List<Uri>, documentId: String?, detectBounds: Boolean = true, onLoaded: (String) -> Unit) {
        if (uris.isEmpty()) return
        task {
            val id = documentId ?: repository.create(defaultTitle())
            var imported = 0
            for (uri in uris) {
                try { repository.importImage(id, uri, detectBounds); imported++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep successfully published pages. No document contents in logs. */ }
            }
            onLoaded(id)
            if (imported != uris.size) _message.value = app.getString(R.string.import_partial, imported, uris.size)
        }
    }

    fun importPdfs(uris: List<Uri>, target: String?, onLoaded: (String) -> Unit) {
        if (uris.isEmpty()) return
        task {
            val id = target ?: repository.create(defaultTitle())
            var imported = 0
            try {
                for (uri in uris) {
                    try { repository.importPdf(id, uri); imported++ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                if (imported > 0) onLoaded(id)
                if (imported != uris.size) _message.value = app.getString(R.string.import_pdf_failed, imported, uris.size)
            } finally {
                if (target == null && imported == 0) withContext(NonCancellable) { repository.deleteDocument(id) }
            }
        }
    }
    fun mergeDocuments(ids: List<String>, title: String) = task {
        val id = repository.mergeDocuments(ids, title.ifBlank { app.getString(R.string.merged_document) })
        selectedDocuments = arrayListOf(); selectingDocuments = false; openDocument(id)
    }
    fun batchFilter(id: String, ids: List<String>, filter: ScanFilter, strength: Int) = task { repository.filterPages(id, ids, filter, strength) }
    fun batchRotate(id: String, ids: List<String>) = task { repository.rotatePages(id, ids) }
    fun batchDelete(id: String, ids: List<String>) = task {
        repository.deletePages(id, ids); selectedPageIds = arrayListOf(); selectingPages = false
    }
    fun applyFilterToDocument(pageId: String) = task {
        val count = repository.copyFilterToDocument(pageId)
        _message.value = app.getString(R.string.applied_to_pages, count)
    }
    fun saveGallery(id: String, ids: List<String>? = null) = task {
        val coroutine = currentCoroutineContext()
        val count = repository.readSnapshot(id) { document, pages ->
            GalleryExporter.write(app, document.title, repository.selectedPages(pages, ids), repository) { coroutine.ensureActive() }.size
        }
        _message.value = app.getString(R.string.saved_gallery, count)
    }
    fun share(id: String, format: ShareFormat, ids: List<String>? = null, onReady: (Intent) -> Unit) = prepareShare(id, format, ids) { files -> onReady(ShareExport.intent(app, files)) }
    fun print(id: String, ids: List<String>? = null, onReady: (File) -> Unit) = prepareShare(id, ShareFormat.PDF, ids) { onReady(it.single()) }
    private fun prepareShare(id: String, format: ShareFormat, ids: List<String>?, onReady: (List<File>) -> Unit) = task {
        val files = repository.readSnapshot(id) { document, pages -> ShareExport.prepare(app, document.title, repository.selectedPages(pages, ids), repository, format) }
        try { onReady(files) } catch (failure: Exception) { files.first().parentFile?.deleteRecursively(); throw failure }
    }

    fun capture(documentId: String, file: File, detectionMode: DetectionMode = DetectionMode.STANDARD, onSaved: (Boolean) -> Unit) {
        var saved = false
        task(finallyAction = { file.delete(); onSaved(saved) }) {
            val page = repository.importImage(documentId, Uri.fromFile(file), enhanceCapture = true, detectionMode = detectionMode)
            saved = true
            if (page.crop == null) _message.value = app.getString(R.string.capture_crop_review)
        }
    }

    fun rename(id: String, title: String) = task { repository.rename(id, title) }
    fun retake(pageId: String, file: File, detectionMode: DetectionMode = DetectionMode.STANDARD, onSaved: (Boolean) -> Unit) {
        var saved = false
        task(finallyAction = { file.delete(); onSaved(saved) }) {
            val page = repository.replaceImage(pageId, Uri.fromFile(file), detectionMode)
            saved = true
            if (page.crop == null) _message.value = app.getString(R.string.capture_crop_review)
        }
    }
    fun rotate(id: String) = task { repository.rotate(id) }
    fun filter(id: String, filter: ScanFilter) = task { repository.setFilter(id, filter) }
    fun filterStrength(id: String, strength: Int) = task { repository.setFilterStrength(id, strength) }
    fun adjustments(id: String, settings: com.docscan.android.processing.ImageAdjustments) = task { repository.setAdjustments(id, settings) }
    fun crop(id: String, crop: CropQuad?, format: com.docscan.android.processing.PageFormat, onSaved: () -> Unit) = task {
        repository.setCropAndFormat(id, crop, format); onSaved()
    }
    fun move(id: String, delta: Int) = task { repository.move(id, delta) }
    fun deletePage(id: String) = task { repository.deletePage(id) }
    fun deleteDocument(id: String) = task { repository.deleteDocument(id) }

    fun export(documentId: String, destination: Uri? = null, onShare: ((File) -> Unit)? = null, ids: List<String>? = null) = task {
        val file = File(app.cacheDir, "exports/${UUID.randomUUID()}.pdf")
        file.parentFile?.mkdirs()
        try {
            repository.readSnapshot(documentId) { _, pages -> PdfExporter.write(repository.selectedPages(pages, ids), repository, file) }
            if (destination != null) {
                withContext(Dispatchers.IO) {
                    requireNotNull(app.contentResolver.openOutputStream(destination, "w")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
                _message.value = app.getString(R.string.saved_pdf)
            } else { requireNotNull(onShare)(file) }
        } finally { if (destination != null) file.delete() }
    }

    fun exportImages(documentId: String, folder: Uri, ids: List<String>? = null) = task {
        val count = repository.readSnapshot(documentId) { document, pages ->
            JpegExporter.writeFolder(app, folder, document.title, repository.selectedPages(pages, ids), repository)
        }
        _message.value = app.getString(R.string.saved_images, count)
    }

    private fun defaultTitle(): String = app.getString(
        R.string.new_document, SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
    )

    private fun task(finallyAction: () -> Unit = {}, operation: suspend () -> Unit) {
        if (_busy.value) { finallyAction(); return }
        _busy.value = true
        viewModelScope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _message.value = app.getString(R.string.operation_failed) }
            finally { _busy.value = false; finallyAction() }
        }
    }
}
