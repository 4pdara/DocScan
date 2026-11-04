package com.docscan.android.processing

/** A second, colour-opponent view for a neutral page against a similarly bright, warm background.
 * Used only for boundary detection; image pixels and page fingerprints are never changed.
 */
object PageContrast {
    fun rgb(red: Int, green: Int, blue: Int): Int {
        val luma = (red * 77 + green * 150 + blue * 29) ushr 8
        return (luma + 3 * (blue - red)).coerceIn(0, 255)
    }

    fun yuv(luma: Int, u: Int, v: Int): Int =
        (luma + ((1361 * (u - 128) - 1077 * (v - 128)) shr 8)).coerceIn(0, 255)
}
