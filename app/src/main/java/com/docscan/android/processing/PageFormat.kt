package com.docscan.android.processing

enum class PageFormat(private val pointsWidth: Int, private val pointsHeight: Int) {
    NATURAL(0, 0), A4(595, 842), LETTER(612, 792), ID_CARD(243, 153), SQUARE(595, 595);

    fun aspect(landscape: Boolean): Float? {
        if (this == NATURAL) return null
        val short = minOf(pointsWidth, pointsHeight).toFloat()
        val long = maxOf(pointsWidth, pointsHeight).toFloat()
        return if (landscape) long / short else short / long
    }

    fun pdfSize(landscape: Boolean): Pair<Int, Int> {
        val width = if (this == NATURAL) 595 else minOf(pointsWidth, pointsHeight)
        val height = if (this == NATURAL) 842 else maxOf(pointsWidth, pointsHeight)
        return if (landscape) height to width else width to height
    }

    companion object {
        fun fromKey(value: String): PageFormat = entries.firstOrNull { it.name == value } ?: NATURAL
    }
}
