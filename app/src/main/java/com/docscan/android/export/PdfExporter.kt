package com.docscan.android.export

import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.data.adjustments
import com.docscan.android.processing.ImageAdjustments
import com.docscan.android.processing.PageFormat
import com.docscan.android.processing.PdfSources
import com.docscan.android.processing.ScanFilter
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

object PdfExporter {
    fun write(pages: List<PageEntity>, repository: DocumentRepository, destination: File,
        quality: ExportQuality = ExportQuality.ORIGINAL) {
        require(pages.isNotEmpty())
        val directory = requireNotNull(destination.parentFile).apply { mkdirs() }
        try {
            PDDocument(PdfSources.memory(directory)).use { pdf ->
                for (page in pages) {
                    val source = repository.source(page)
                    if (PdfSources.isPdf(source) && page.crop == null && page.filter == ScanFilter.ORIGINAL.name &&
                        page.adjustments() == ImageAdjustments() && page.pageFormat == PageFormat.NATURAL.name) {
                        PDDocument.load(source, PdfSources.memory(directory)).use { input ->
                            PDFMergerUtility().appendDocument(pdf, input)
                            val added = pdf.getPage(pdf.numberOfPages - 1)
                            added.rotation = (added.rotation + page.rotation) % 360
                        }
                    } else {
                        val temporary = File.createTempFile("docscan-page-", ".jpg", directory)
                        try {
                            temporary.outputStream().use { JpegExporter.write(page, repository, it, quality) }
                            val image = temporary.inputStream().use { JPEGFactory.createFromStream(pdf, it) }
                            val format = PageFormat.fromKey(page.pageFormat)
                            val (width, height) = format.pdfSize(image.width > image.height)
                            val output = PDPage(PDRectangle(width.toFloat(), height.toFloat()))
                            pdf.addPage(output)
                            val margin = if (format == PageFormat.NATURAL) 36f else 0f
                            val scale = minOf((width - margin) / image.width, (height - margin) / image.height)
                            val w = image.width * scale; val h = image.height * scale
                            PDPageContentStream(pdf, output).use { stream -> stream.drawImage(image, (width - w) / 2, (height - h) / 2, w, h) }
                        } finally { temporary.delete() }
                    }
                }
                pdf.save(destination)
            }
        } catch (failure: Exception) { destination.delete(); throw failure }
    }
}
