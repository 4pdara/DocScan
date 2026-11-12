package com.docscan.rebuild.imaging

import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.model.OcrOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class OcrRequest {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() { cancelled.set(true) }
    fun checkActive() { if (isCancelled) throw CancellationException("OCR cancelled") }
}

data class OcrProgress(val page: Int, val total: Int, val percent: Int)
data class OcrBatchResult(val recognized: Int, val retained: Int, val failedPages: List<Int>)

/** One engine and one full-resolution image at a time; originals are never rewritten. */
class OfflineOcr(private val store: ScanStore) {
    suspend fun recognize(sheet: Sheet, languages: String = "heb+eng", options: OcrOptions = OcrOptions(languages),
        request: OcrRequest = OcrRequest(), progress: (OcrProgress) -> Unit = {}): String {
        recognizePages(listOf(sheet), options, replaceExisting = true, request = request, progress = progress, failFast = true)
        return requireNotNull(store.dao.page(sheet.id)?.currentText())
    }

    suspend fun recognizeBook(bookId: String, options: OcrOptions = OcrOptions(), replaceExisting: Boolean = false,
        request: OcrRequest = OcrRequest(), progress: (OcrProgress) -> Unit = {}): OcrBatchResult =
        recognizePages(store.dao.snapshot(bookId), options, replaceExisting, request, progress)

    private suspend fun recognizePages(pages: List<Sheet>, options: OcrOptions, replaceExisting: Boolean,
        request: OcrRequest, progress: (OcrProgress) -> Unit, failFast: Boolean = false): OcrBatchResult = withContext(Dispatchers.Default) {
        require(pages.isNotEmpty())
        lock.withLock {
            request.checkActive(); coroutineContext.ensureActive()
            val root = prepareModels()
            // Native progress callbacks run on a worker. The callback stops hOCR recognition
            // cooperatively, and never races recycle() on a different thread.
            val jobContext = coroutineContext
            var currentPage = 1
            lateinit var engine: TessBaseAPI
            engine = TessBaseAPI { values ->
                if (request.isCancelled || !jobContext[kotlinx.coroutines.Job]!!.isActive) engine.stop()
                else progress(OcrProgress(currentPage, pages.size, values.percent.coerceIn(0, 100)))
            }
            var recognized = 0
            var retained = 0
            val failed = mutableListOf<Int>()
            try {
                check(engine.init(root.path, options.languages, TessBaseAPI.OEM_LSTM_ONLY)) { "OCR initialization failed" }
                engine.setPageSegMode(options.layout.segmentation)
                engine.setVariable("preserve_interword_spaces", "1")
                pages.forEachIndexed { index, snapshot ->
                    request.checkActive(); coroutineContext.ensureActive()
                    currentPage = index + 1
                    progress(OcrProgress(currentPage, pages.size, 0))
                    try {
                        val sheet = requireNotNull(store.dao.page(snapshot.id))
                        if (!replaceExisting && sheet.currentText() != null) retained++
                        else {
                            val bitmap = ImagePipeline.render(store.source(sheet), sheet.edit())
                            try {
                                request.checkActive(); coroutineContext.ensureActive()
                                engine.setImage(bitmap)
                                // getUTF8Text alone cannot be interrupted in Tesseract4Android 4.9.0.
                                // Run interruptible recognition first; UTF8 extraction then uses its result.
                                engine.getHOCRText(index)
                                request.checkActive(); coroutineContext.ensureActive()
                                val text = engine.getUTF8Text().orEmpty().trim()
                                check(store.dao.recognized(sheet.id, text, sheet.recipe) == 1) { "Page changed during OCR" }
                                recognized++
                            } finally { try { engine.clear() } finally { bitmap.recycle() } }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (failFast) throw failure
                        // No text/source is overwritten on failure; other pages can still complete.
                        failed.add(currentPage)
                    }
                    progress(OcrProgress(currentPage, pages.size, 100))
                }
                request.checkActive(); coroutineContext.ensureActive()
                OcrBatchResult(recognized, retained, failed.toList())
            } finally { engine.recycle() }
        }
    }

    private fun prepareModels(): File {
        val root = File(store.context.filesDir, "ocr")
        val data = File(root, "tessdata").apply { check(isDirectory || mkdirs()) }
        for ((language, expected) in models) {
            val target = File(data, "$language.traineddata")
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!target.isFile || digest(target) != expected) {
                val temp = File(data, "$language.part")
                try {
                    store.context.assets.open("tessdata/$language.traineddata").use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() }
                    }
                    check(digest(temp) == expected); check(temp.renameTo(target))
                } finally { temp.delete() }
            }
        }
        return root
    }

    companion object {
        private val lock = Mutex()
        private val models = mapOf("heb" to "11f9e43ab227f786352a50f75c94c2e9906f1baba86d93276da19da7ce0904db", "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    }
}
