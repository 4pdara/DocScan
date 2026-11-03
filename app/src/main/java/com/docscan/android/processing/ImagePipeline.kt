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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Missing or unsupported image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 16_000_000L) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Cannot decode image" }
        val matrix = Matrix()
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        val scale = minOf(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        matrix.postScale(scale, scale)
        var bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()
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
            if (filter == ScanFilter.CLEAN_COLOR || filter == ScanFilter.CLEAN_TEXT) {
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
    }

    fun detectCrop(file: File): CropQuad? {
        val bitmap = render(file, 0, ScanFilter.ORIGINAL, 480)
        return try { detect(bitmap)?.quad } finally { bitmap.recycle() }
    }

    fun detect(bitmap: Bitmap): DocumentDetection? {
        val scale = minOf(1f, 360f / max(bitmap.width, bitmap.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            val pixels = IntArray(resized.width * resized.height)
            resized.getPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = ((p ushr 16 and 255) * 77 + (p ushr 8 and 255) * 150 + (p and 255) * 29) ushr 8
            }
            DocumentDetector.detect(GrayImage(resized.width, resized.height, pixels))
        } finally { if (resized !== bitmap) resized.recycle() }
    }
}
