package com.docscan.android.processing

enum class ScanFilter { ORIGINAL, ENHANCED, GRAYSCALE, MONOCHROME, CLEAN_COLOR, CLEAN_TEXT }

/** Conservative baseline filters. These do not remove wrinkles or bleed-through. */
object PixelFilters {
    fun apply(pixel: Int, filter: ScanFilter): Int {
        if (filter == ScanFilter.ORIGINAL) return pixel
        val alpha = pixel ushr 24 and 255
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        val luminance = (red * 299 + green * 587 + blue * 114 + 500) / 1000
        fun pack(r: Int, g: Int, b: Int) = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        return when (filter) {
            ScanFilter.ORIGINAL -> pixel
            ScanFilter.CLEAN_COLOR, ScanFilter.CLEAN_TEXT -> error("Document filters require a complete image")
            ScanFilter.GRAYSCALE -> pack(luminance, luminance, luminance)
            ScanFilter.MONOCHROME -> (if (luminance >= 160) 255 else 0).let { pack(it, it, it) }
            ScanFilter.ENHANCED -> {
                fun enhance(value: Int) = ((value - 128) * 108 / 100 + 136).coerceIn(0, 255)
                pack(enhance(red), enhance(green), enhance(blue))
            }
        }
    }
}
