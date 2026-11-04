package com.docscan.android.export

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.processing.PdfSources
import java.io.File
import java.io.OutputStream

object SourceExporter {
    fun mimeType(source: File): String = if (PdfSources.isPdf(source)) "application/pdf" else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        options.outMimeType ?: "application/octet-stream"
    }

    fun write(page: PageEntity, repository: DocumentRepository, output: OutputStream, checkpoint: () -> Unit = {}) {
        repository.source(page).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
        }
    }

    fun writeFolder(context: Context, tree: Uri, title: String, pages: List<PageEntity>,
        repository: DocumentRepository, checkpoint: () -> Unit = {}): Int {
        require(pages.isNotEmpty())
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = mutableListOf<Uri>()
        try {
            pages.forEachIndexed { index, page ->
                checkpoint()
                val mime = mimeType(repository.source(page))
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                val name = ExportNames.pdf(title).removeSuffix(".pdf") + "-source-${(index + 1).toString().padStart(3, '0')}.$extension"
                val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, mime, name))
                created.add(uri)
                requireNotNull(resolver.openOutputStream(uri, "w")).use { write(page, repository, it, checkpoint) }
            }
            return created.size
        } catch (failure: Exception) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw failure
        }
    }
}
