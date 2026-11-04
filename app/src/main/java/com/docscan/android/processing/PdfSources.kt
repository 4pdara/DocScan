package com.docscan.android.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

object PdfSources {
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_PAGES = 200
    fun isPdf(file: File) = file.extension == "pdf"
    fun memory(directory: File) = MemoryUsageSetting.setupTempFileOnly(256L * 1024 * 1024).setTempDir(directory)

    fun copyBounded(input: InputStream, target: File, checkpoint: () -> Unit = {}) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "PDF exceeds 50 MB" }
                output.write(buffer, 0, count)
            }
        }
    }

    /** Store independent one-page PDFs; retain text and vectors until an image edit is requested. */
    fun split(input: File, directory: File, checkpoint: () -> Unit = {}): List<File> {
        val created = mutableListOf<File>()
        try {
            PDDocument.load(input, memory(directory)).use { source ->
                require(!source.isEncrypted) { "Encrypted PDF is unsupported" }
                require(source.numberOfPages in 1..MAX_PAGES) { "PDF page count exceeds limit" }
                var total = 0L
                for (index in 0 until source.numberOfPages) {
                    checkpoint()
                    val box = source.getPage(index).cropBox
                    require(box.width.isFinite() && box.height.isFinite() && box.width in 1f..20000f && box.height in 1f..20000f)
                    val splitter = Splitter().apply {
                        setStartPage(index + 1); setEndPage(index + 1); setMemoryUsageSetting(memory(directory))
                    }
                    val pieces = splitter.split(source)
                    try {
                        val file = File(directory, "${UUID.randomUUID()}.pdf")
                        created.add(file)
                        pieces.single().save(file)
                        total += file.length()
                        require(total <= 200L * 1024 * 1024) { "Expanded PDF exceeds limit" }
                    } finally { pieces.forEach { it.close() } }
                }
            }
            return created
        } catch (failure: Exception) { created.forEach { it.delete() }; throw failure }
    }

    fun dimensions(file: File): Pair<Int, Int> = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer -> renderer.openPage(0).use { it.width to it.height } }
    }

    /** PdfRenderer exposes whole points; use the PDF box to avoid losing pixels at export DPI. */
    fun dimensionsAtDpi(file: File, dpi: Int): Pair<Int, Int> {
        require(dpi in 72..600)
        return PDDocument.load(file, memory(requireNotNull(file.parentFile))).use { document ->
            val page = document.getPage(0)
            val box = page.cropBox
            val width = (box.width.toDouble() * dpi / 72).roundToInt().coerceAtLeast(1)
            val height = (box.height.toDouble() * dpi / 72).roundToInt().coerceAtLeast(1)
            if ((page.rotation % 180 + 180) % 180 == 90) height to width else width to height
        }
    }

    fun render(file: File, maxEdge: Int): Bitmap = renderPage(file) { width, height ->
        val scale = maxEdge.toFloat() / maxOf(width, height)
        (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    fun renderSized(file: File, width: Int, height: Int): Bitmap = renderPage(file) { _, _ -> width to height }

    private fun renderPage(file: File, size: (Int, Int) -> Pair<Int, Int>): Bitmap {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val (width, height) = size(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        bitmap.setHasAlpha(false)
                        return bitmap
                    } catch (failure: Exception) { bitmap.recycle(); throw failure }
                }
            }
        }
    }
}
