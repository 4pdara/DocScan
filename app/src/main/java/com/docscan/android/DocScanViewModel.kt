package com.docscan.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscan.android.export.PdfExporter
import com.docscan.android.export.JpegExporter
import com.docscan.android.processing.ScanFilter
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

class DocScanViewModel(application: Application) : AndroidViewModel(application) {
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
                File(app.cacheDir, "exports").listFiles()?.filter { it.lastModified() < expiration }?.forEach { it.delete() }
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

    fun capture(documentId: String, file: File, onSaved: (Boolean) -> Unit) {
        var saved = false
        task(finallyAction = { file.delete(); onSaved(saved) }) {
            repository.importImage(documentId, Uri.fromFile(file)); saved = true
        }
    }

    fun rename(id: String, title: String) = task { repository.rename(id, title) }
    fun retake(pageId: String, file: File, onSaved: (Boolean) -> Unit) {
        var saved = false
        task(finallyAction = { file.delete(); onSaved(saved) }) {
            repository.replaceImage(pageId, Uri.fromFile(file)); saved = true
        }
    }
    fun rotate(id: String) = task { repository.rotate(id) }
    fun filter(id: String, filter: ScanFilter) = task { repository.setFilter(id, filter) }
    fun filterStrength(id: String, strength: Int) = task { repository.setFilterStrength(id, strength) }
    fun crop(id: String, crop: CropQuad?, onSaved: () -> Unit) = task {
        repository.setCrop(id, crop); onSaved()
    }
    fun move(id: String, delta: Int) = task { repository.move(id, delta) }
    fun deletePage(id: String) = task { repository.deletePage(id) }
    fun deleteDocument(id: String) = task { repository.deleteDocument(id) }

    fun export(documentId: String, destination: Uri? = null, onShare: ((File) -> Unit)? = null) = task {
        val file = File(app.cacheDir, "exports/${UUID.randomUUID()}.pdf")
        file.parentFile?.mkdirs()
        try {
            repository.readSnapshot(documentId) { _, pages -> PdfExporter.write(pages, repository, file) }
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

    fun exportImages(documentId: String, folder: Uri) = task {
        val count = repository.readSnapshot(documentId) { document, pages ->
            JpegExporter.writeFolder(app, folder, document.title, pages, repository)
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
