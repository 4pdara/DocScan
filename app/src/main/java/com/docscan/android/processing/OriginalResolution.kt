package com.docscan.android.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import java.io.File
import kotlin.math.roundToInt

class OriginalResolutionUnavailable : Exception("Original-resolution processing exceeds device resources")

object OriginalResolution {
    fun render(file: File, rotation: Int, filter: ScanFilter, crop: CropQuad?, strength: Int,
        adjustments: ImageAdjustments, format: PageFormat): Bitmap {
        try {
            val pdf = PdfSources.isPdf(file)
            val dimensions = if (pdf) {
                PdfSources.dimensionsAtDpi(file, 300)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                bounds.outWidth to bounds.outHeight
            }
            val (rawWidth, rawHeight) = dimensions
            val matrix = if (pdf) Matrix() else ImagePipeline.orientationMatrix(file)
            val rectangle = RectF(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat()).also { matrix.mapRect(it) }
            val width = rectangle.width().roundToInt()
            val height = rectangle.height().roundToInt()
            val maxEdge = maxOf(width, height)
            val (outputWidth, outputHeight) = if (crop != null || format != PageFormat.NATURAL)
                PerspectiveCrop.dimensions(width, height, crop ?: CropQuad.FULL, maxEdge, format)
            else width to height
            requireBudget(rawWidth, rawHeight, outputWidth, outputHeight)
            val raw = if (pdf) PdfSources.renderSized(file, rawWidth, rawHeight)
            else requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = 1; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            }))
            val bitmap = try {
                check(raw.width == rawWidth && raw.height == rawHeight)
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
            } catch (failure: Throwable) { raw.recycle(); throw failure }
            return ImagePipeline.renderDecoded(bitmap, rotation, filter, maxEdge, crop, strength, adjustments, format)
        } catch (_: OutOfMemoryError) { throw OriginalResolutionUnavailable() }
    }

    internal fun requireBudget(width: Int, height: Int, outputWidth: Int, outputHeight: Int,
        heap: Long = Runtime.getRuntime().maxMemory()) {
        val input = width.toLong() * height
        val output = outputWidth.toLong() * outputHeight
        if (listOf(width, height, outputWidth, outputHeight).any { it !in 1..32766 } ||
            input !in 1..100_000_000L || output !in 1..100_000_000L ||
            maxOf(input * 8, output * 10) + 16L * 1024 * 1024 > heap / 5 * 4) {
            throw OriginalResolutionUnavailable()
        }
    }
}
