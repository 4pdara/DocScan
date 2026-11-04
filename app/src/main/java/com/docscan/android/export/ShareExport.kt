package com.docscan.android.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import java.io.File
import java.util.UUID

enum class ShareFormat { PDF, IMAGES, BOTH }

object ShareExport {
    fun prepare(context: Context, title: String, pages: List<PageEntity>, repository: DocumentRepository,
        format: ShareFormat, quality: ExportQuality = ExportQuality.ORIGINAL): List<File> {
        require(pages.isNotEmpty())
        val directory = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val files = mutableListOf<File>()
        try {
            val name = ExportNames.pdf(title)
            if (format != ShareFormat.IMAGES) File(directory, name).also {
                PdfExporter.write(pages, repository, it, quality); files.add(it)
            }
            if (format != ShareFormat.PDF) pages.forEachIndexed { index, page ->
                File(directory, name.removeSuffix(".pdf") + "-${(index + 1).toString().padStart(3, '0')}.jpg").also {
                    it.outputStream().use { output -> JpegExporter.write(page, repository, output, quality) }; files.add(it)
                }
            }
            return files
        } catch (failure: Exception) { directory.deleteRecursively(); throw failure }
    }

    fun intent(context: Context, files: List<File>): Intent {
        require(files.isNotEmpty())
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        val types = files.map { if (it.extension == "pdf") "application/pdf" else "image/jpeg" }.distinct()
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = types.singleOrNull() ?: "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(context.contentResolver, "DocScan", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
