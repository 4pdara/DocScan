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
        return try { transform(bitmap, recipe) } finally { bitmap.recycle() }
    }

    fun transform(bitmap: Bitmap, recipe: EditRecipe): Bitmap {
        ensureAvailable(); require(recipe.frame.valid())
        val source = Mat(); val current = Mat(); val result = Mat(); val alpha = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            if (recipe.frame == Frame.FULL) source.copyTo(current) else {
                val p = recipe.frame.points.map { Point((it.x * (source.cols() - 1)).toDouble(), (it.y * (source.rows() - 1)).toDouble()) }
                fun length(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
                val width = max(length(p[0], p[1]), length(p[3], p[2])).roundToInt().coerceAtLeast(2)
                val height = max(length(p[0], p[3]), length(p[1], p[2])).roundToInt().coerceAtLeast(2)
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
                Treatment.GRAY -> Imgproc.cvtColor(current, result, Imgproc.COLOR_RGBA2GRAY)
                Treatment.INK -> {
                    val gray = Mat()
                    try { Imgproc.cvtColor(current, gray, Imgproc.COLOR_RGBA2GRAY); Imgproc.adaptiveThreshold(gray, result, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 51, 13.0) }
                    finally { gray.release() }
                }
                Treatment.PAPER -> {
                    if (BuildConfig.CAMSCANNER_NATIVE) {
                        val work = Bitmap.createBitmap(current.cols(), current.rows(), Bitmap.Config.ARGB_8888)
                        try {
                            Utils.matToBitmap(current, work)
                            NativeCamScannerEnhancer.applyInPlace(work)
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
        return try { ensureAvailable(); Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); detectGray(gray) }
        finally { bitmap.recycle(); rgba.release(); gray.release() }
    }

    /** Contour proposals from both gradients and thresholded paper. No proprietary scanner components. */
    fun detectGray(input: Mat): Frame? {
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
            var best: Frame? = null; var bestArea = 0.0
            for (contour in contours) {
                val area = abs(Imgproc.contourArea(contour)) / (input.cols().toDouble() * input.rows())
                if (area !in .15.. .97 || area <= bestArea) continue
                val curve = MatOfPoint2f(*contour.toArray()); val approximation = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * .025, true)
                    if (approximation.total() != 4L) continue
                    val raw = approximation.toArray().map { Vertex((it.x / input.cols()).toFloat(), (it.y / input.rows()).toFloat()) }
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
