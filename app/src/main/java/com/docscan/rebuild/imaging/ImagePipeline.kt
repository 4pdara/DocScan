package com.docscan.rebuild.imaging

import com.docscan.rebuild.BuildConfig
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.docscan.rebuild.model.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

/** All edits are derived from the immutable master; only previews are sampled. */
object ImagePipeline {
    val referenceEngineAvailable: Boolean get() = NativeCamScannerEnhancer.available
    private val available by lazy { OpenCVLoader.initLocal() }
    fun ensureAvailable() { check(available) { "OpenCV initialization failed" } }

    fun decode(file: File, previewEdge: Int? = null): Bitmap {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        if (previewEdge != null) {
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, size)
            while (max(size.outWidth, size.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= previewEdge) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Image decode failed" }
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(90f); postScale(1f, -1f) }
                8 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    fun render(file: File, recipe: EditRecipe, previewEdge: Int? = null): Bitmap {
        val bitmap = decode(file, previewEdge)
        val focalToDiagonal = runCatching {
            ExifInterface(file).getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
                .takeIf { it > 0.0 }?.div(hypot(36.0, 24.0))
        }.getOrNull()
        return try { transform(bitmap, recipe, focalToDiagonal) } finally { bitmap.recycle() }
    }

    fun transform(bitmap: Bitmap, recipe: EditRecipe, focalToDiagonal: Double? = null): Bitmap {
        ensureAvailable(); require(recipe.frame.valid())
        check(!recipe.treatment.requiresReferenceEngine || referenceEngineAvailable) { "Reference engine is not bundled for this device" }
        val source = Mat(); val current = Mat(); val result = Mat(); val alpha = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            if (recipe.frame == Frame.FULL && recipe.outputAspect == null) source.copyTo(current) else {
                val p = recipe.frame.points.map { Point((it.x * (source.cols() - 1)).toDouble(), (it.y * (source.rows() - 1)).toDouble()) }
                val dimensions = CropGeometry.dimensions(recipe.frame, source.cols(), source.rows(), recipe.outputAspect, focalToDiagonal)
                val width = dimensions.width; val height = dimensions.height
                val from = MatOfPoint2f(*p.toTypedArray()); val to = MatOfPoint2f(Point(0.0, 0.0), Point((width - 1).toDouble(), 0.0), Point((width - 1).toDouble(), (height - 1).toDouble()), Point(0.0, (height - 1).toDouble()))
                val homography = Imgproc.getPerspectiveTransform(from, to)
                try { Imgproc.warpPerspective(source, current, homography, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE) }
                finally { from.release(); to.release(); homography.release() }
            }
            when (recipe.turns.mod(4)) {
                1 -> Core.rotate(current, current, Core.ROTATE_90_CLOCKWISE)
                2 -> Core.rotate(current, current, Core.ROTATE_180)
                3 -> Core.rotate(current, current, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            when (recipe.treatment) {
                Treatment.ORIGINAL -> current.copyTo(result)
                Treatment.COLOR -> current.convertTo(result, -1, 1.1, -7.0)
                Treatment.LIGHTEN -> {
                    // Independent local Lighten equivalent; not ScannerEngine mode 15.
                    val curve = Mat(1, 256, CvType.CV_8UC1)
                    try {
                        curve.put(0, 0, ByteArray(256) { (255.0 * (it / 255.0).pow(.75)).roundToInt().toByte() })
                        Core.LUT(current, curve, result)
                    } finally { curve.release() }
                }
                Treatment.GRAY -> Imgproc.cvtColor(current, result, Imgproc.COLOR_RGBA2GRAY)
                Treatment.INK, Treatment.ECO -> {
                    val gray = Mat()
                    try {
                        Imgproc.cvtColor(current, gray, Imgproc.COLOR_RGBA2GRAY)
                        Imgproc.adaptiveThreshold(gray, result, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 51, 13.0)
                        // Grey ink on white retains thin strokes; this is our Eco equivalent.
                        if (recipe.treatment == Treatment.ECO) result.convertTo(result, -1, .82, 255.0 * .18)
                    }
                    finally { gray.release() }
                }
                Treatment.PAPER, Treatment.NATIVE_MAGIC_COLOR, Treatment.NATIVE_NO_SHADOW,
                Treatment.NATIVE_INVERT, Treatment.NATIVE_PRO_LOCAL, Treatment.NATIVE_BOARD,
                Treatment.NATIVE_BOARD_PAPER, Treatment.NATIVE_INVERT_BOARD -> {
                    if (referenceEngineAvailable) {
                        val work = Bitmap.createBitmap(current.cols(), current.rows(), Bitmap.Config.ARGB_8888)
                        try {
                            Utils.matToBitmap(current, work)
                            NativeCamScannerEnhancer.applyInPlace(work, recipe.treatment)
                            Utils.bitmapToMat(work, result)
                        } finally { work.recycle() }
                    } else {
                    // Local illumination correction, not a learned restoration or book dewarp model.
                    val background = Mat(); val rgb = Mat(); val reduced = Mat()
                    try {
                        Imgproc.cvtColor(current, rgb, Imgproc.COLOR_RGBA2RGB)
                        val scale = min(1.0, 480.0 / max(rgb.cols(), rgb.rows()))
                        Imgproc.resize(rgb, reduced, Size(max(1.0, rgb.cols() * scale), max(1.0, rgb.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
                        Imgproc.GaussianBlur(reduced, reduced, Size(0.0, 0.0), 15.0)
                        Imgproc.resize(reduced, background, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                        Core.max(background, Scalar(1.0, 1.0, 1.0), background)
                        Core.divide(rgb, background, result, 245.0)
                    } finally { background.release(); rgb.release(); reduced.release() }
                    }
                }
            }
            if (recipe.brightness != 0f || recipe.contrast != 1f) result.convertTo(result, -1, recipe.contrast.toDouble(), recipe.brightness.toDouble())
            // Brightness and contrast operate on color; retain the cropped source alpha.
            if (result.channels() == 4) {
                Core.extractChannel(current, alpha, 3)
                Core.insertChannel(alpha, result, 3)
            }
            return Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(result, it) }
        } finally { source.release(); current.release(); result.release(); alpha.release() }
    }

    fun detect(file: File): Frame? {
        ensureAvailable()
        val bitmap = decode(file, 1000); val rgba = Mat(); val gray = Mat()
        return try { Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); detectGray(gray, rgba) }
        finally { bitmap.recycle(); rgba.release(); gray.release() }
    }

    /** Bright pages, dark covers and colour boundaries. Proposals still require four visible corners. */
    fun detectGray(input: Mat, rgba: Mat? = null): Frame? {
        ensureAvailable()
        val blurred = Mat(); val edges = Mat(); val mask = Mat(); val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.GaussianBlur(input, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 35.0, 110.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.threshold(blurred, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val bright = mutableListOf<MatOfPoint>(); Imgproc.findContours(mask, bright, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE); contours.addAll(bright)
            // Inverse threshold also finds dark book covers on a light table.
            Imgproc.threshold(blurred, mask, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            val dark = mutableListOf<MatOfPoint>(); Imgproc.findContours(mask, dark, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE); contours.addAll(dark)
            if (rgba != null) {
                val channel = Mat()
                try {
                    // Equal-luminance coloured covers may have no boundary in the camera's Y plane.
                    for (index in 0..2) {
                        Core.extractChannel(rgba, channel, index)
                        Imgproc.GaussianBlur(channel, channel, Size(5.0, 5.0), 0.0)
                        Imgproc.Canny(channel, edges, 25.0, 85.0)
                        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
                        val coloured = mutableListOf<MatOfPoint>()
                        Imgproc.findContours(edges, coloured, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                        contours.addAll(coloured)
                    }
                } finally { channel.release() }
            }
            var best: Frame? = null; var bestArea = 0.0
            for (contour in contours) {
                val area = abs(Imgproc.contourArea(contour)) / (input.cols().toDouble() * input.rows())
                if (area !in .06.. .97 || area <= bestArea) continue
                val curve = MatOfPoint2f(*contour.toArray()); val approximation = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * .025, true)
                    if (approximation.total() != 4L) continue
                    val raw = approximation.toArray().map { Vertex((it.x / (input.cols() - 1)).toFloat(), (it.y / (input.rows() - 1)).toFloat()) }
                    val cx = raw.map { it.x }.average(); val cy = raw.map { it.y }.average()
                    val clockwise = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
                    val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
                    val ordered = Frame((0..3).map { clockwise[(start + it) % 4] })
                    if (!ordered.valid() || ordered.points.any { it.x < .005f || it.x > .995f || it.y < .005f || it.y > .995f }) continue
                    best = ordered; bestArea = area
                } finally { curve.release(); approximation.release() }
            }
            return best
        } finally { contours.forEach { it.release() }; blurred.release(); edges.release(); mask.release(); hierarchy.release(); kernel.release() }
    }
}


