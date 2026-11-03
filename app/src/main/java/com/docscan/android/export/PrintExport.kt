package com.docscan.android.export

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.*
import com.docscan.android.processing.PdfSources
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

object PrintExport {
    fun launch(context: Context, file: File, title: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(title, Adapter(file, title), PrintAttributes.Builder().build())
    }
    fun writeRanges(file: File, output: OutputStream, ranges: Array<out PageRange>, checkpoint: () -> Unit = {}) {
        val directory = requireNotNull(file.parentFile)
        PDDocument.load(file, PdfSources.memory(directory)).use { source ->
            PDDocument(PdfSources.memory(directory)).use { target ->
                for (index in 0 until source.numberOfPages) {
                    checkpoint()
                    if (ranges.any { index in it.start..it.end }) {
                        val original = source.getPage(index)
                        target.importPage(original).resources = original.resources
                    }
                }
                require(target.numberOfPages > 0)
                target.save(output)
            }
        }
    }
    private class Adapter(private val file: File, private val title: String) : PrintDocumentAdapter() {
        private val executor = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?) {
            executor.execute {
                try {
                    signal.throwIfCanceled()
                    val count = PDDocument.load(file, PdfSources.memory(requireNotNull(file.parentFile))).use { it.numberOfPages }
                    main.post { if (signal.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFinished(
                        PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build(), old != new) }
                } catch (_: Exception) { main.post { if (signal.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFailed(null) } }
            }
        }
        override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
            executor.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(destination).use { writeRanges(file, it, ranges) { signal.throwIfCanceled() } }
                    main.post { if (signal.isCanceled) callback.onWriteCancelled() else callback.onWriteFinished(ranges) }
                } catch (_: Exception) { main.post { if (signal.isCanceled) callback.onWriteCancelled() else callback.onWriteFailed(null) } }
            }
        }
        override fun onFinish() { executor.shutdown(); file.parentFile?.deleteRecursively() }
    }
}
