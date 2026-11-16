package com.docscan.rebuild.model

import kotlin.math.max
import kotlin.math.min

/** A recognised word and its box, in page fractions. */
data class TextBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Personal details in the recognised words of a page, for hiding them: Israeli ID numbers (nine digits whose
 * check digit is right), phone numbers (Israeli, or international with a +), e-mail addresses and payment card
 * numbers (13 to 19 digits whose Luhn check is right). The words of each printed line are joined left to right, as
 * numbers and addresses are written even in a Hebrew line, so a number split into several words is still found.
 */
object SensitiveText {
    enum class Kind { ID, PHONE, EMAIL, CARD }
    data class Found(val kind: Kind, val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** The area to hide: the words' box with a margin, so no edge of the print shows. */
        fun hide(kind: HideKind = HideKind.BLACK): HideMark {
            val h = bottom - top; val padX = h * .25f; val padY = h * .25f
            return HideMark(kind, Vertex((left - padX).coerceIn(-.1f, 1.1f), (top - padY).coerceIn(-.1f, 1.1f)), Vertex((right + padX).coerceIn(-.1f, 1.1f), (bottom + padY).coerceIn(-.1f, 1.1f)))
        }
    }

    private val email = Regex("[A-Za-z0-9._%+\\-]+ ?@ ?[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}")
    private val israeliPhone = Regex("(?<![\\d+])(?:\\+ ?972[ \\-]?\\(?0?\\)?|0)(?:5\\d|7\\d|[2-489])[ \\-]?\\d{3}[ \\-]?\\d{4}(?!\\d)")
    private val internationalPhone = Regex("(?<![\\d+])\\+ ?\\d[\\d ()\\-]{6,18}\\d(?!\\d)")
    private val cardNumber = Regex("(?<!\\d)\\d(?:[ \\-]?\\d){12,18}(?!\\d)")
    /** Nine digits together, or eight and the check digit after a dash (leading zeros are sometimes left out). */
    private val idNumber = Regex("(?<![\\d\\-])(?:\\d{7,8}-\\d|\\d{8,9})(?![\\d\\-])")

    /** Israeli ID check: digits weighted 1, 2, 1, 2 ...; a product over 9 counts its digit sum. */
    fun israeliId(digits: String): Boolean {
        if (digits.length !in 5..9 || digits.any { !it.isDigit() }) return false
        val d = digits.padStart(9, '0')
        if (d.all { it == '0' }) return false
        return d.indices.sumOf { i -> val v = (d[i] - '0') * (if (i % 2 == 0) 1 else 2); if (v > 9) v - 9 else v } % 10 == 0
    }

    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.any { !it.isDigit() }) return false
        return digits.reversed().mapIndexed { i, c -> val v = (c - '0') * (if (i % 2 == 1) 2 else 1); if (v > 9) v - 9 else v }.sum() % 10 == 0
    }

    fun find(words: List<TextBox>): List<Found> {
        val found = ArrayList<Found>()
        for (line in lines(words)) {
            // The line as one string, remembering which characters came from which word.
            val text = StringBuilder(); val owner = ArrayList<Int>()
            line.forEachIndexed { i, w -> if (i > 0) { text.append(' '); owner += -1 }; w.text.forEach { c -> text.append(c); owner += i } }
            val s = text.toString(); val taken = BooleanArray(s.length)
            fun add(kind: Kind, range: IntRange) {
                if (range.any { taken[it] }) return
                range.forEach { taken[it] = true }
                val parts = range.mapNotNull { owner[it].takeIf { o -> o >= 0 } }.distinct().map { line[it] }
                if (parts.isEmpty()) return
                found += Found(kind, s.substring(range), parts.minOf { it.left }, parts.minOf { it.top }, parts.maxOf { it.right }, parts.maxOf { it.bottom })
            }
            email.findAll(s).forEach { add(Kind.EMAIL, it.range) }
            israeliPhone.findAll(s).forEach { add(Kind.PHONE, it.range) }
            internationalPhone.findAll(s).forEach { m -> if (m.value.count { it.isDigit() } in 8..15) add(Kind.PHONE, m.range) }
            cardNumber.findAll(s).forEach { m -> if (luhn(m.value.filter { it.isDigit() })) add(Kind.CARD, m.range) }
            idNumber.findAll(s).forEach { m -> if (israeliId(m.value.filter { it.isDigit() })) add(Kind.ID, m.range) }
        }
        return found
    }

    /** Words grouped into printed lines (a word whose middle lies within a line's height joins it), each left to right. */
    private fun lines(words: List<TextBox>): List<List<TextBox>> {
        val lines = ArrayList<MutableList<TextBox>>()
        for (word in words.sortedBy { (it.top + it.bottom) / 2 }) {
            val line = lines.lastOrNull(); val centre = (word.top + word.bottom) / 2
            if (line != null && centre > line.minOf { min(it.top, it.bottom) } && centre < line.maxOf { max(it.top, it.bottom) }) line += word else lines += mutableListOf(word)
        }
        return lines.map { it.sortedBy { w -> w.left } }
    }
}
