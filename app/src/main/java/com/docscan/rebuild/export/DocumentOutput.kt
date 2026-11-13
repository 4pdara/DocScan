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
    /** Quality 95 keeps every pixel within 4 levels of quality 100 at about half the file size (measured on a scanned page). */
    private val jpegQuality = 95

    suspend fun image(sheet: Sheet, recipe: EditRecipe): File = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        try {
            val bitmap = ImagePipeline.render(store.source(sheet), recipe)
            try { FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)); it.fd.sync() } }
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
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)) { "JPEG encoding failed" }
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

    /** PDF size: [maxEdge] null keeps every page at its full rendered resolution; smaller values make a lighter file. */
    enum class PdfSize(val maxEdge: Int?) { ORIGINAL(null), BALANCED(2000), SMALL(1200) }

    /**
     * Page geometry is in PDF points. Each raster is rendered at source resolution before drawing, then reduced
     * only when a smaller [size] was chosen. [pageIds] limits the file to those pages (page extraction), in order.
     */
    /** The page as rendered for a PDF, scaled down to the chosen size. */
    private fun pdfBitmap(sheet: Sheet, size: PdfSize): Bitmap = ImagePipeline.render(store.source(sheet), sheet.edit()).let { full ->
        val edge = size.maxEdge
        if (edge == null || maxOf(full.width, full.height) <= edge) full else {
            val scale = edge.toDouble() / maxOf(full.width, full.height)
            Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt().coerceAtLeast(1), (full.height * scale).roundToInt().coerceAtLeast(1), true)
                .also { if (it !== full) full.recycle() }
        }
    }

    /**
     * A PDF that opens only with [password] (AES-256, PDF standard security revision 6). Pages are the same as in
     * [pdf]: 595 points wide, each page's image stored as JPEG at quality 90.
     */
    suspend fun protectedPdf(bookId: String, password: String, size: PdfSize = PdfSize.ORIGINAL, pageIds: Set<String>? = null): File = withContext(Dispatchers.IO) {
        val pages = store.dao.snapshot(bookId).filter { pageIds == null || it.id in pageIds }; require(pages.isNotEmpty())
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
        val scope = coroutineContext
        try {
            FileOutputStream(target).use { out ->
                ProtectedPdf.write(object : ProtectedPdf.Pages {
                    override fun count() = pages.size
                    override fun page(index: Int): ProtectedPdf.Page {
                        scope.ensureActive()
                        val bitmap = pdfBitmap(pages[index], size)
                        try {
                            val jpeg = java.io.ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
                            return ProtectedPdf.Page(jpeg, bitmap.width, bitmap.height, 595.0, 595.0 * bitmap.height / bitmap.width)
                        } finally { bitmap.recycle() }
                    }
                }, password, out)
                out.fd.sync()
            }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    suspend fun pdf(bookId: String, size: PdfSize = PdfSize.ORIGINAL, pageIds: Set<String>? = null): File = withContext(Dispatchers.IO) {
        val pages = store.dao.snapshot(bookId).filter { pageIds == null || it.id in pageIds }; require(pages.isNotEmpty())
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, sheet ->
                coroutineContext.ensureActive()
                val bitmap = pdfBitmap(sheet, size)
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

    /**
     * The whole document as one tall JPEG, pages one under the other at a common width. The width keeps the image
     * within 16 million pixels (64 MB while it is drawn) and never wider than 2000 pixels.
     */
    suspend fun longImage(bookId: String): File = withContext(Dispatchers.IO) {
        val pages = store.dao.snapshot(bookId); require(pages.isNotEmpty())
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        val sizes = pages.map { sheet ->
            val recipe = sheet.edit()
            com.docscan.rebuild.model.CropGeometry.dimensions(recipe.frame, sheet.width, sheet.height, recipe.outputAspect).rotated(recipe.turns)
        }
        val heightPerWidth = sizes.sumOf { it.height.toDouble() / it.width }
        val width = minOf(2000.0, kotlin.math.sqrt(16_000_000.0 / heightPerWidth)).roundToInt().coerceAtLeast(1)
        val heights = sizes.map { (width * it.height.toDouble() / it.width).roundToInt().coerceAtLeast(1) }
        val result = Bitmap.createBitmap(width, heights.sum(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(result); canvas.drawColor(android.graphics.Color.WHITE)
            var top = 0
            pages.forEachIndexed { index, sheet ->
                coroutineContext.ensureActive()
                val bitmap = ImagePipeline.render(store.source(sheet), sheet.edit())
                try { canvas.drawBitmap(bitmap, null, RectF(0f, top.toFloat(), width.toFloat(), (top + heights[index]).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
                finally { bitmap.recycle() }
                top += heights[index]
            }
            FileOutputStream(target).use { check(result.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)); it.fd.sync() }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
        finally { result.recycle() }
    }

    /**
     * A Word document of the recognized text, one page per scanned page, right-to-left paragraphs for Hebrew.
     * Written directly as Office Open XML; no third-party library.
     */
    suspend fun word(pageTexts: List<String>): File = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${UUID.randomUUID()}.docx")
        fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .filter { it == '\t' || it == '\n' || it >= ' ' }
        val body = StringBuilder()
        pageTexts.forEachIndexed { index, text ->
            text.lines().forEachIndexed { line, content ->
                val rtl = content.any { it in '\u0590'..'\u05FF' }
                val pageBreak = if (index > 0 && line == 0) "<w:r><w:br w:type=\"page\"/></w:r>" else ""
                val props = if (rtl) "<w:pPr><w:bidi/><w:jc w:val=\"right\"/></w:pPr>" else ""
                val run = if (rtl) "<w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space=\"preserve\">${xml(content)}</w:t></w:r>" else "<w:r><w:t xml:space=\"preserve\">${xml(content)}</w:t></w:r>"
                body.append("<w:p>").append(props).append(pageBreak).append(run).append("</w:p>")
            }
        }
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>""")
        try {
            java.util.zip.ZipOutputStream(FileOutputStream(target)).use { zip ->
                parts.forEach { (name, content) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
    }
}
