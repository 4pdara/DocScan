package com.docscan.rebuild

import com.docscan.rebuild.export.OcrWord
import com.docscan.rebuild.export.TableLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class TableLayoutTest {
    private fun w(text: String, left: Int, top: Int, width: Int = 60, height: Int = 20) = OcrWord(text, left, top, left + width, top + height)

    @Test fun hebrewTableKeepsColumnsFromTheRight() {
        // Three columns, right to left: name (two words), quantity, price; the second row lacks a quantity.
        val words = listOf(
            w("תפוחים", 900, 100), w("אדומים", 830, 101), w("3", 600, 99, 20), w("12.50", 300, 100),
            w("בננות", 900, 150), w("8.90", 300, 151))
        assertEquals(listOf(listOf("תפוחים אדומים", "3", "12.50"), listOf("בננות", "", "8.90")), TableLayout.rows(words, rightToLeft = true))
    }

    @Test fun plainTextIsOneCellPerLine() {
        val words = listOf(w("Hello", 10, 10), w("world", 75, 11), w("Second", 10, 40), w("line", 75, 40))
        assertEquals(listOf(listOf("Hello world"), listOf("Second line")), TableLayout.rows(words, rightToLeft = false))
    }

    @Test fun nothingRecognizedGivesNoRows() = assertEquals(emptyList<List<String>>(), TableLayout.rows(emptyList(), true))
}
