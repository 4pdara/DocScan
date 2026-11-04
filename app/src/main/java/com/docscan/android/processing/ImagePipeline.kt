package com.docscan.android.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImagePipeline {
    const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    fun validate(file: File) {
        require(file.length() in 1..MAX_SOURCE_BYTES) { "Invalid image size" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) { "Image dimensions too large" }
    }

    fun render(file: File, rotation: Int, filter: ScanFilter, maxEdge: Int, crop: CropQuad? = null, filterStrength: Int = 70,
        adjustments: ImageAdjustments = ImageAdjustments(), format: PageFormat = PageFormat.NATURAL): Bitmap {
        require(maxEdge in 64..4000)
        val bitmap = if (PdfSources.isPdf(file)) PdfSources.render(file, maxEdge) else decodeImage(file, maxEdge)
        return renderDecoded(bitmap, rotation, filter, maxEdge, crop, filterStrength, adjustments, format)
    }

    /** Takes ownership, including cleanup if a transform or allocation fails. */
    internal fun renderDecoded(initial: Bitmap, rotation: Int, filter: ScanFilter, maxEdge: Int, crop: CropQuad?,
        filterStrength: Int, adjustments: ImageAdjustments, format: PageFormat): Bitmap {
        var bitmap = initial
        try {
        if (crop != null || format != PageFormat.NATURAL) {
            val cropped = PerspectiveCrop.apply(bitmap, crop ?: CropQuad.FULL, maxEdge, format)
            bitmap.recycle(); bitmap = cropped
        }
        if (rotation % 360 != 0) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (filter != ScanFilter.ORIGINAL || adjustments != ImageAdjustments()) {
            if (!bitmap.isMutable) {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle(); bitmap = copy
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (filter == ScanFilter.AUTO_DOCUMENT) {
                AutoDocumentFilter.apply(pixels, bitmap.width, bitmap.height, filterStrength)
            } else if (filter == ScanFilter.CLEAN_COLOR || filter == ScanFilter.CLEAN_TEXT) {
                DocumentFilters.apply(pixels, bitmap.width, bitmap.height, filter, filterStrength)
            } else if (filter in ProDocumentFilters.supported) {
                ProDocumentFilters.apply(pixels, bitmap.width, bitmap.height, filter, filterStrength)
            } else {
                for (index in pixels.indices) pixels[index] = PixelFilters.apply(pixels[index], filter)
            }
            ImageEnhancer.apply(pixels, bitmap.width, bitmap.height, adjustments)
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private fun decodeImage(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Missing or unsupported image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 16_000_000L) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Cannot decode image" }
        val matrix = orientationMatrix(file)
        val scale = minOf(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        matrix.postScale(scale, scale)
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()
        return bitmap
    }

    internal fun orientationMatrix(file: File): Matrix {
        val matrix = Matrix()
        val orientation = try { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (_: java.io.IOException) { ExifInterface.ORIENTATION_NORMAL }
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        return matrix
    }

    fun detectCrop(file: File, mode: DetectionMode = DetectionMode.STANDARD): CropQuad? {
        val bitmap = render(file, 0, ScanFilter.ORIGINAL, 960)
        return try { StillDocumentDetector.detect(detectionImage(bitmap), mode)?.quad } finally { bitmap.recycle() }
    }

    fun detect(bitmap: Bitmap, mode: DetectionMode = DetectionMode.STANDARD): DocumentDetection? {
        val scale = minOf(1f, mode.stillEdge.toFloat() / max(bitmap.width, bitmap.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            DocumentDetector.detect(detectionImage(resized), mode)
        } finally { if (resized !== bitmap) resized.recycle() }
    }

    private fun detectionImage(bitmap: Bitmap): GrayImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val contrast = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            contrast[i] = PageContrast.rgb(p ushr 16 and 255, p ushr 8 and 255, p and 255)
            pixels[i] = ((p ushr 16 and 255) * 77 + (p ushr 8 and 255) * 150 + (p and 255) * 29) ushr 8
        }
        return GrayImage(bitmap.width, bitmap.height, pixels, contrast)
    }
}
