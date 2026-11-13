package com.docscan.rebuild.export

/** A recognized word and its box in page pixels. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height get() = bottom - top
    val centreY get() = (top + bottom) / 2.0
}

/**
 * Rows and columns from recognized words, for the Excel export. Words sharing a line become a row; a gap wider
 * than about one and a half letter heights starts a new cell; cells line up into columns by their reading-side
 * edge (the right edge in Hebrew). Plain text gives one cell per line.
 */
object TableLayout {
    fun rows(words: List<OcrWord>, rightToLeft: Boolean): List<List<String>> {
        val clean = words.filter { it.text.isNotBlank() && it.bottom > it.top && it.right > it.left }
        if (clean.isEmpty()) return emptyList()
        // Lines: words whose vertical centres fall inside the line's band.
        val lines = ArrayList<MutableList<OcrWord>>()
        for (word in clean.sortedBy { it.centreY }) {
            val line = lines.lastOrNull()
            val top = line?.minOf { it.top } ?: 0; val bottom = line?.maxOf { it.bottom } ?: 0
            if (line != null && word.centreY in top.toDouble()..bottom.toDouble() && word.centreY - line.map { it.centreY }.average() < (bottom - top) * .5) line += word
            else lines += mutableListOf(word)
        }
        val unit = clean.map { it.height }.sorted()[clean.size / 2].coerceAtLeast(1)
        // Cells: in reading order, split where the gap is wide.
        data class Cell(val text: String, val anchor: Int)
        val rows = lines.map { line ->
            val ordered = if (rightToLeft) line.sortedByDescending { it.right } else line.sortedBy { it.left }
            val cells = ArrayList<MutableList<OcrWord>>()
            for (word in ordered) {
                val last = cells.lastOrNull()?.last()
                val gap = if (last == null) 0 else if (rightToLeft) last.left - word.right else word.left - last.right
                if (last == null || gap > unit * 1.5) cells += mutableListOf(word) else cells.last() += word
            }
            cells.map { c -> Cell(c.joinToString(" ") { it.text }, if (rightToLeft) c.maxOf { it.right } else c.minOf { it.left }) }
        }
        // Columns: cluster the anchors; a new column wherever anchors are more than two letter heights apart.
        val anchors = rows.flatten().map { it.anchor }.sorted().let { if (rightToLeft) it.reversed() else it }
        val starts = ArrayList<Int>()
        for (a in anchors) if (starts.isEmpty() || kotlin.math.abs(a - starts.last()) > unit * 2) starts += a
        fun column(anchor: Int) = starts.indices.minByOrNull { kotlin.math.abs(starts[it] - anchor) } ?: 0
        return rows.map { cells ->
            val out = ArrayList<String>()
            for (cell in cells) {
                var at = column(cell.anchor)
                // Two cells claiming one column: keep reading order rather than overwrite.
                while (at < out.size && out[at].isNotEmpty()) at++
                while (out.size <= at) out += ""
                out[at] = cell.text
            }
            out
        }
    }
}
