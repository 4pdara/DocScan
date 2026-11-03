package com.docscan.android.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.ScanFilter
import java.io.OutputStream

object JpegExporter {
    fun write(page: PageEntity, repository: DocumentRepository, output: OutputStream) {
        val bitmap = ImagePipeline.render(repository.source(page), page.rotation, ScanFilter.valueOf(page.filter),
            2400, CropQuad.decode(page.crop), page.filterStrength)
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "JPEG encoding failed" } }
        finally { bitmap.recycle() }
    }

    /** Create new files in the user-selected folder. Never overwrite a pre-existing file. */
    fun writeFolder(context: Context, tree: Uri, title: String, pages: List<PageEntity>, repository: DocumentRepository): Int {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = mutableListOf<Uri>()
        try {
            pages.forEachIndexed { index, page ->
                val name = ExportNames.pdf(title).removeSuffix(".pdf") + "-${(index + 1).toString().padStart(3, '0')}.jpg"
                val file = requireNotNull(DocumentsContract.createDocument(resolver, parent, "image/jpeg", name))
                created.add(file)
                requireNotNull(resolver.openOutputStream(file, "w")).use { write(page, repository, it) }
            }
            return created.size
        } catch (failure: Exception) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw failure
        }
    }
}
