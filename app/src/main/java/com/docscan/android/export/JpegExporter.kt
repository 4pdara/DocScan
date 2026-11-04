package com.docscan.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.provider.DocumentsContract
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import java.io.OutputStream

object JpegExporter {
    fun write(page: PageEntity, repository: DocumentRepository, output: OutputStream,
        quality: ExportQuality = ExportQuality.ORIGINAL) {
        var bitmap = page.renderForExport(repository.source(page), quality)
        try {
            if (bitmap.hasAlpha()) {
                if (!bitmap.isMutable) {
                    val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true); bitmap.recycle(); bitmap = mutable
                }
                Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                bitmap.setHasAlpha(false)
            }
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, output)) { "JPEG encoding failed" }
        } finally { bitmap.recycle() }
    }

    /** Create new files in the user-selected folder. Never overwrite a pre-existing file. */
    fun writeFolder(context: Context, tree: Uri, title: String, pages: List<PageEntity>, repository: DocumentRepository,
        quality: ExportQuality = ExportQuality.ORIGINAL): Int {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = mutableListOf<Uri>()
        try {
            pages.forEachIndexed { index, page ->
                val name = ExportNames.pdf(title).removeSuffix(".pdf") + "-${(index + 1).toString().padStart(3, '0')}.jpg"
                val file = requireNotNull(DocumentsContract.createDocument(resolver, parent, "image/jpeg", name))
                created.add(file)
                requireNotNull(resolver.openOutputStream(file, "w")).use { write(page, repository, it, quality) }
            }
            return created.size
        } catch (failure: Exception) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw failure
        }
    }
}
