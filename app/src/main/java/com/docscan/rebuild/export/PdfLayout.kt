package com.docscan.rebuild.export

import kotlin.math.min

/**
 * Page size, orientation and margins of exported PDFs. [Paper.FIT] gives every page the shape of its scan
 * (595 points wide, as before); a paper size fits each scan inside the page, centred, at that paper's real size.
 * Sizes are in PDF points (1/72 inch).
 */
data class PdfLayout(val paper: Paper = Paper.FIT, val orientation: Orientation = Orientation.AUTO, val margin: Margin = Margin.NONE) {
    enum class Paper(val width: Double, val height: Double) { FIT(595.0, 0.0), A4(595.28, 841.89), LETTER(612.0, 792.0), LEGAL(612.0, 1008.0), A5(419.53, 595.28) }
    /** [AUTO] turns the paper to match each scan: landscape for a wide scan, portrait otherwise. */
    enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }
    /** None, 5 mm or 10 mm of white around the scan. */
    enum class Margin(val points: Double) { NONE(0.0), SMALL(14.17), NORMAL(28.35) }

    /** The page and where the image goes on it: left and bottom (PDF origin at the bottom left), width and height. */
    data class Placement(val pageWidth: Double, val pageHeight: Double, val x: Double, val y: Double, val width: Double, val height: Double) {
        /** The image's top edge measured from the top of the page, for canvases whose origin is the top left. */
        val top: Double get() = pageHeight - y - height
    }

    fun place(pixelWidth: Int, pixelHeight: Int): Placement {
        require(pixelWidth > 0 && pixelHeight > 0)
        val aspect = pixelWidth.toDouble() / pixelHeight
        val m = margin.points
        if (paper == Paper.FIT) {
            val width = paper.width; val inner = width - 2 * m; val innerHeight = inner / aspect
            return Placement(width, innerHeight + 2 * m, m, m, inner, innerHeight)
        }
        val landscape = when (orientation) { Orientation.AUTO -> aspect > 1.0; Orientation.PORTRAIT -> false; Orientation.LANDSCAPE -> true }
        val pageWidth = if (landscape) paper.height else paper.width; val pageHeight = if (landscape) paper.width else paper.height
        val scale = min((pageWidth - 2 * m) / pixelWidth, (pageHeight - 2 * m) / pixelHeight)
        val width = pixelWidth * scale; val height = pixelHeight * scale
        return Placement(pageWidth, pageHeight, (pageWidth - width) / 2, (pageHeight - height) / 2, width, height)
    }
}
