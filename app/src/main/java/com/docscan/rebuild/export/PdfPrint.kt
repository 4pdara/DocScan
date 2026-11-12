package com.docscan.rebuild.export

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream

/** Sends an exported PDF to Android's print service (printers, "Save as PDF"); the app needs no extra permission. */
object PdfPrint {
    fun print(context: Context, title: String, pdf: File) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(title, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancel.isCanceled) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(PrintDocumentInfo.Builder("$title.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
            }
            override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
                try {
                    pdf.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    if (cancel.isCanceled) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { callback.onWriteFailed(e.message) }
            }
            override fun onFinish() { pdf.delete() }
        }, null)
    }
}
