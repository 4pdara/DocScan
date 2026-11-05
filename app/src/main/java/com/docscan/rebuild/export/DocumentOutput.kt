package com.docscan.rebuild.export

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.data.RecipeCodec
import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.imaging.ImagePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class DocumentOutput(private val context: Context, private val store: ScanStore) {
    suspend fun image(sheet: Sheet, recipe: EditRecipe): File = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        try {
            val bitmap = ImagePipeline.render(store.source(sheet), recipe)
            try { FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)); it.fd.sync() } }
            finally { bitmap.recycle() }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    /** Full-resolution edited JPEGs in Pictures/DocScan. Pending rows are removed on failure. */
    suspend fun gallery(bookId: String): List<Uri> = withContext(Dispatchers.IO) {
        val pages = store.dao.snapshot(bookId); require(pages.isNotEmpty())
        writeGallery(pages, requireNotNull(store.dao.book(bookId)).title)
    }

    suspend fun galleryPage(sheet: Sheet, recipe: EditRecipe): List<Uri> = withContext(Dispatchers.IO) {
        writeGallery(listOf(sheet.copy(recipe = RecipeCodec.encode(recipe))), requireNotNull(store.dao.book(sheet.notebookId)).title)
    }

    private suspend fun writeGallery(pages: List<Sheet>, documentTitle: String): List<Uri> = withContext(Dispatchers.IO) {
        val title = documentTitle
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60).ifBlank { "DocScan" }
        val batch = UUID.randomUUID().toString().take(8)
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        val legacyFiles = mutableListOf<File>()
        try {
            pages.forEachIndexed { index, sheet ->
                coroutineContext.ensureActive()
                val bitmap = ImagePipeline.render(store.source(sheet), sheet.edit())
                try {
                    val name = "$title-${index + 1}-$batch.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.WIDTH, bitmap.width)
                        put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DocScan")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        } else {
                            @Suppress("DEPRECATION")
                            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocScan")
                            check(directory.isDirectory || directory.mkdirs()) { "Cannot create gallery album" }
                            val file = File(directory, name); legacyFiles.add(file)
                            @Suppress("DEPRECATION")
                            put(MediaStore.Images.Media.DATA, file.absolutePath)
                        }
                    }
                    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Cannot create gallery image" }
                    created.add(uri)
                    requireNotNull(resolver.openOutputStream(uri, "w")).use {
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) { "JPEG encoding failed" }
                    }
                } finally { bitmap.recycle() }
            }
            coroutineContext.ensureActive()
            if (Build.VERSION.SDK_INT >= 29) created.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            }
            created.toList()
        } catch (failure: Throwable) {
            created.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            legacyFiles.forEach { it.delete() }
            throw failure
        }
    }

    /** Page geometry is in PDF points. Each raster is rendered at source resolution before drawing. */
    suspend fun pdf(bookId: String): File = withContext(Dispatchers.IO) {
        val pages = store.dao.snapshot(bookId); require(pages.isNotEmpty())
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, sheet ->
                val bitmap = ImagePipeline.render(store.source(sheet), sheet.edit())
                try {
                    val width = 595; val height = (width * bitmap.height.toDouble() / bitmap.width).roundToInt().coerceAtLeast(1)
                    val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                    page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                } finally { bitmap.recycle() }
            }
            FileOutputStream(target).use { document.writeTo(it); it.fd.sync() }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
        finally { document.close() }
    }
}

