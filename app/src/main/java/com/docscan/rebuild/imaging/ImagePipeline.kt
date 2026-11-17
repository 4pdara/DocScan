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
                val curves = recipe.edgeCurves.takeIf { recipe.frame != Frame.FULL }
                if (curves != null) {
                    // A book page's curved top and bottom edges: the page is taken from the surface between them.
                    val back = Imgproc.getPerspectiveTransform(to, from)
                    try { PageEdges.warp(source, back, width, height, inset, curves, current) } finally { from.release(); to.release(); back.release() }
                } else {
                    val homography = Imgproc.getPerspectiveTransform(from, to)
                    try { Imgproc.warpPerspective(source, current, homography, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE) }
                    finally { from.release(); to.release(); homography.release() }
                }
            }
            // Automatic straightening from the page's own text lines, then the manual book curve.
            if (recipe.flatten.isNotEmpty()) PageFlattener.apply(current, recipe.flatten)
            if (recipe.curvature.active) BookDewarp.apply(current, recipe.curvature)
            // Clean-up chosen for the page: the regular pattern first (it would confuse the others), then the reflection, then fingers.
            if (recipe.moire) PageCleanup.moire(current)
            if (recipe.glare) PageCleanup.glare(current)
            if (recipe.fingers) PageCleanup.fingers(current)
            if (recipe.eraseColor) ColorMarks.erase(current)
            when (recipe.turns.mod(4)) {
                1 -> Core.rotate(current, current, Core.ROTATE_90_CLOCKWISE)
                2 -> Core.rotate(current, current, Core.ROTATE_180)
                3 -> Core.rotate(current, current, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            // Smart erase on the turned page (where its marks were placed), before the filter evens the paper.
            if (recipe.erase.isNotEmpty()) PageEraser.apply(current, recipe.erase)
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
            if (recipe.detail > 0f) bringOutDetail(result, recipe.detail)
            // Brightness and contrast operate on color; retain the cropped source alpha.
            if (result.channels() == 4) {
                Core.extractChannel(current, alpha, 3)
                Core.insertChannel(alpha, result, 3)
            }
            // Hidden areas over the finished page, under anything drawn on it.
            if (recipe.hides.isNotEmpty()) PageHide.apply(result, recipe.hides)
            return Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(result, it); PageMarks.draw(it, recipe) }
        } finally { source.release(); current.release(); result.release(); alpha.release() }
    }

    /**
     * Unsharp masking scaled to the page: the blur radius grows with the image (about a pen stroke's width), and
     * [amount] 0..1 adds up to 1.6 times the fine detail back. Flat paper, which has no detail, stays as it is.
     */
    internal fun bringOutDetail(image: Mat, amount: Float) {
        val blurred = Mat()
        try {
            val sigma = max(1.0, min(image.cols(), image.rows()) / 1400.0)
            Imgproc.GaussianBlur(image, blurred, Size(0.0, 0.0), sigma)
            val gain = 1.6 * amount.coerceIn(0f, 1f)
            Core.addWeighted(image, 1 + gain, blurred, -gain, 0.0, image)
        } finally { blurred.release() }
    }

    /** A book page's curved top and bottom edges ([PageEdges]) on the photo in [file] for the page at [frame], or null. */
    fun pageEdges(file: File, frame: Frame): PageEdgeCurves? {
        ensureAvailable()
        if (frame == Frame.FULL || !frame.valid()) return null
        val bitmap = decode(file, 1600); val rgba = Mat()
        return try { Utils.bitmapToMat(bitmap, rgba); PageEdges.estimate(rgba, frame) } finally { bitmap.recycle(); rgba.release() }
    }

    fun detect(file: File): Frame? {
        ensureAvailable()
        val bitmap = decode(file, 1000); val rgba = Mat(); val gray = Mat()
        return try { Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); detectGray(gray, rgba) }
        finally { bitmap.recycle(); rgba.release(); gray.release() }
    }

    /** Page outline on a picture already in memory (Doc Radar's gallery thumbnails). */
    fun detectBitmap(bitmap: Bitmap): Frame? {
        ensureAvailable()
        val rgba = Mat(); val gray = Mat()
        return try { Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); detectGray(gray, rgba) }
        finally { rgba.release(); gray.release() }
    }

    fun detectGray(input: Mat, rgba: Mat? = null): Frame? {
        ensureAvailable()
        return BoundaryDetector.detect(input, rgba)
    }
}
