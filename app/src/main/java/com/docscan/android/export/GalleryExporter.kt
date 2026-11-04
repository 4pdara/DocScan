package com.docscan.android.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import java.io.File
import java.util.UUID

object GalleryExporter {
    /** Publish the entire batch after encoding, and remove only files created by this call on failure. */
    @Suppress("DEPRECATION")
    fun write(context: Context, title: String, pages: List<PageEntity>, repository: DocumentRepository,
        quality: ExportQuality = ExportQuality.ORIGINAL,
        checkpoint: () -> Unit = {}): List<Uri> {
        require(pages.isNotEmpty())
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        val legacyFiles = mutableListOf<File>()
        val prefix = ExportNames.pdf(title).removeSuffix(".pdf") + "-" + UUID.randomUUID()
        try {
            pages.forEachIndexed { index, page ->
                checkpoint()
                val name = "$prefix-${(index + 1).toString().padStart(3, '0')}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScan")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocScan").apply { mkdirs() }
                        val target = File(directory, name)
                        check(target.createNewFile()) { "Image already exists" }
                        legacyFiles.add(target)
                        put(MediaStore.Images.Media.DATA, target.absolutePath)
                    }
                }
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                created.add(uri)
                requireNotNull(resolver.openOutputStream(uri, "w")).use { JpegExporter.write(page, repository, it, quality) }
            }
            checkpoint()
            if (Build.VERSION.SDK_INT >= 29) created.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            }
            return created
        } catch (failure: Exception) {
            created.forEach { runCatching { resolver.delete(it, null, null) } }
            legacyFiles.forEach { it.delete() }
            throw failure
        }
    }
}
