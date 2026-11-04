package com.docscan.android.processing

import kotlin.math.roundToInt

/** More accurate saved-photo refinement, without increasing the cost of live analysis. */
internal object StillDocumentDetector {
    fun detect(image: GrayImage, mode: DetectionMode): DocumentDetection? {
        val scale = minOf(1f, 480f / maxOf(image.width, image.height))
        val w = (image.width * scale).roundToInt().coerceAtLeast(1)
        val h = (image.height * scale).roundToInt().coerceAtLeast(1)
        val coarse = if (scale == 1f) image else {
            fun sample(values: IntArray) = IntArray(w * h) { i ->
                val x = ((i % w).toFloat() * (image.width - 1) / (w - 1).coerceAtLeast(1)).roundToInt()
                val y = ((i / w).toFloat() * (image.height - 1) / (h - 1).coerceAtLeast(1)).roundToInt()
                values[y * image.width + x]
            }
            GrayImage(w, h, sample(image.pixels), image.pageContrast?.let(::sample))
        }
        // A manual shot still deserves the wider search even if the live outline
        // was missing. A failed search leaves the source uncropped.
        val found = DocumentDetector.detect(coarse, mode)
            ?: if (mode == DetectionMode.STANDARD) DocumentDetector.detect(coarse, DetectionMode.ANGLED_DISTANCE) else null
        return found?.let { it.copy(quad = PageBoundary.refine(image, it.quad, searchRadius = 9)) }
    }
}
