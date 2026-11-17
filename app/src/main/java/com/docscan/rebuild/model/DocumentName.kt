package com.docscan.rebuild.model

import kotlin.math.max
import kotlin.math.min

/**
 * A name for a document from its first page's recognised words (1.3.3): the title is the line printed largest in
 * the upper part of the page, as long as it has real words in it (not only numbers or signs). Hebrew lines are
 * joined from the right.
 */
object DocumentName {
    fun suggest(words: List<TextBox>, limit: Int = 60): String? {
        val lines = ArrayList<MutableList<TextBox>>()
        for (word in words.filter { it.text.isNotBlank() && it.bottom > it.top }.sortedBy { (it.top + it.bottom) / 2 }) {
            val line = lines.lastOrNull(); val centre = (word.top + word.bottom) / 2
            if (line != null && centre > line.minOf { min(it.top, it.bottom) } && centre < line.maxOf { max(it.top, it.bottom) }) line += word else lines += mutableListOf(word)
        }
        data class Line(val text: String, val height: Float, val top: Float)
        val candidates = lines.mapNotNull { line ->
            val hebrew = line.sumOf { w -> w.text.count { it in '֐'..'׿' } }; val letters = line.sumOf { w -> w.text.count { it.isLetter() } }
            val ordered = if (hebrew * 2 >= letters) line.sortedByDescending { it.right } else line.sortedBy { it.left }
            val text = ordered.joinToString(" ") { it.text }.trim().trim('.', ',', ':', ';', '-', '_', '|', '•', '*', '"', '\'').replace(Regex("\\s+"), " ")
            val heights = line.map { it.bottom - it.top }.sorted()
            if (letters < 3 || text.count { it.isLetter() } * 2 < text.count { !it.isWhitespace() }) null
            else Line(text, heights[heights.size / 2], line.minOf { it.top })
        }.filter { it.top < .6f }
        val best = candidates.maxByOrNull { it.height } ?: return null
        // Among lines nearly as large, the first one (titles come first).
        val title = candidates.filter { it.height >= best.height * .9f }.minBy { it.top }
        return title.text.take(limit).trim().ifEmpty { null }
    }
}
