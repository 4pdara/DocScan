package com.docscan.rebuild.model

/** Tesseract layouts without orientation detection: the bundled languages do not include OSD. */
enum class OcrLayout(val segmentation: Int) {
    AUTO(3), COLUMN(4), BLOCK(6), SPARSE(11)
}

data class OcrOptions(val languages: String = "heb+eng", val layout: OcrLayout = OcrLayout.AUTO) {
    init { require(languages in setOf("heb+eng", "heb", "eng")) }
}

object TextSearch {
    /** Escape SQL LIKE metacharacters so user input is always a literal substring. */
    fun pattern(query: String): String = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
