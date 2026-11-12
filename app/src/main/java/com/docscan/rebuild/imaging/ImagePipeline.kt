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
        ensureAvailable(); require(recipe.frame.valid() && recipe.curvature.valid())
        val source = Mat(); val current = Mat(); val result = Mat(); val alpha = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            if (recipe.frame == Frame.FULL && recipe.outputAspect == null) source.copyTo(current) else {
                val p = recipe.frame.points.map { Point((it.x * (source.cols() - 1)).toDouble(), (it.y * (source.rows() - 1)).toDouble()) }
                val dimensions = CropGeometry.dimensions(recipe.frame, source.cols(), source.rows(), recipe.outputAspect, focalToDiagonal)
                val width = dimensions.width; val height = dimensions.height
                val from = MatOfPoint2f(*p.toTypedArray()); // A detected edge lies on the page border; the outermost pixels then carry the table, shadow or page edge as a
                // dark line. Sample a quarter percent inside the crop (same output size) so the border stays clean.
                val inset = if (recipe.frame == Frame.FULL) 0.0 else max(1.0, min(width, height) * .0025)
                val to = MatOfPoint2f(Point(-inset, -inset), Point(width - 1 + inset, -inset), Point(width - 1 + inset, height - 1 + inset), Point(-inset, height - 1 + inset))
                val homography = Imgproc.getPerspectiveTransform(from, to)
                try { Imgproc.warpPerspective(source, current, homography, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE) }
                finally { from.release(); to.release(); homography.release() }
            }
            if (recipe.curvature.active) BookDewarp.apply(current, recipe.curvature)
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
                    } else OriginalFilters.apply(current, recipe.treatment, result)
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

    fun detectGray(input: Mat, rgba: Mat? = null): Frame? {
        ensureAvailable()
        return BoundaryDetector.detect(input, rgba)
    }
}
