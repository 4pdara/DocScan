package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Passport and ID photos: a crop at an official size, an optional plain background, and print sheets at real
 * size (300 dpi). The background is separated with GrabCut, a classical OpenCV method (no learned model); it
 * works best on a plain wall.
 */
object IdPhoto {
    /** Photo sizes in millimetres. */
    enum class Size(val widthMm: Double, val heightMm: Double) { MM35X45(35.0, 45.0), IN2X2(50.8, 50.8), MM30X40(30.0, 40.0), MM40X50(40.0, 50.0);
        val aspect: Float get() = (widthMm / heightMm).toFloat()
        fun pixels(dpi: Int = 300) = (widthMm / 25.4 * dpi).roundToInt() to (heightMm / 25.4 * dpi).roundToInt()
    }
    enum class Backdrop(val rgb: Int?) { KEEP(null), WHITE(0xFFFFFF), BLUE(0xD8E8F6), GREY(0xE4E4E4) }
    /** Print sheets at 300 dpi: portrait A4, and a 10 x 15 cm photo print. */
    enum class Sheet(val widthPx: Int, val heightPx: Int, val marginPx: Int) { A4(2480, 3508, 118), PHOTO_10X15(1181, 1772, 47) }

    /** The crop ([crop] in fractions of [source]) at the size's pixels, with the background replaced when asked. */
    fun photo(source: Bitmap, crop: RectF, size: Size, backdrop: Backdrop): Bitmap {
        val (w, h) = size.pixels()
        val from = Rect((crop.left * source.width).roundToInt().coerceIn(0, source.width - 1), (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1),
            (crop.right * source.width).roundToInt().coerceIn(1, source.width), (crop.bottom * source.height).roundToInt().coerceIn(1, source.height))
        require(from.width() > 4 && from.height() > 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, from, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val colour = backdrop.rgb ?: return out
        return try { replaceBackground(out, colour) } finally { out.recycle() }
    }

    /** The person in front: GrabCut from a box around the middle of the photo, the largest part kept, edges softened. */
    fun replaceBackground(photo: Bitmap, rgb: Int): Bitmap {
        ImagePipeline.ensureAvailable()
        val scale = min(1.0, 360.0 / max(photo.width, photo.height))
        val sw = max(8, (photo.width * scale).roundToInt()); val sh = max(8, (photo.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(photo, sw, sh, true)
        val rgba = Mat(); val image = Mat(); val mask = Mat(); val back = Mat(); val front = Mat(); val sure = Mat(); val maybe = Mat()
        val labels = Mat(); val stats = Mat(); val centres = Mat(); val kept = Mat(); val alpha = Mat(); val full = Mat()
        try {
            Utils.bitmapToMat(small, rgba); Imgproc.cvtColor(rgba, image, Imgproc.COLOR_RGBA2RGB)
            // The head and shoulders are in the middle and reach the bottom edge.
            val box = org.opencv.core.Rect((sw * .1).roundToInt(), (sh * .04).roundToInt(), (sw * .8).roundToInt(), sh - (sh * .04).roundToInt() - 1)
            Imgproc.grabCut(image, mask, box, back, front, 5, Imgproc.GC_INIT_WITH_RECT)
            Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), sure, Core.CMP_EQ)
            Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), maybe, Core.CMP_EQ)
            Core.bitwise_or(sure, maybe, sure)
            // Specks of the wall are dropped; every large part is kept (head and shoulders can come out apart).
            val count = Imgproc.connectedComponentsWithStats(sure, labels, stats, centres, 8, CvType.CV_32S)
            val areas = (1 until count).associateWith { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            val largest = areas.values.maxOrNull() ?: return photo.copy(Bitmap.Config.ARGB_8888, false)
            kept.create(labels.size(), CvType.CV_8UC1); kept.setTo(Scalar(0.0))
            val part = Mat()
            try { areas.filterValues { it >= largest * .08 }.keys.forEach { Core.compare(labels, Scalar(it.toDouble()), part, Core.CMP_EQ); Core.bitwise_or(kept, part, kept) } } finally { part.release() }
            val close = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            try { Imgproc.morphologyEx(kept, kept, Imgproc.MORPH_CLOSE, close) } finally { close.release() }
            Imgproc.GaussianBlur(kept, alpha, Size(5.0, 5.0), 0.0)
            Imgproc.resize(alpha, full, Size(photo.width.toDouble(), photo.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val a = ByteArray(photo.width * photo.height); full.get(0, 0, a)
            val pixels = IntArray(photo.width * photo.height); photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
            val br = rgb shr 16 and 255; val bg = rgb shr 8 and 255; val bb = rgb and 255
            for (i in pixels.indices) {
                val f = (a[i].toInt() and 255) / 255f; val p = pixels[i]
                val r = ((p shr 16 and 255) * f + br * (1 - f)).roundToInt(); val g = ((p shr 8 and 255) * f + bg * (1 - f)).roundToInt(); val b = ((p and 255) * f + bb * (1 - f)).roundToInt()
                pixels[i] = Color.rgb(r, g, b)
            }
            return Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height) }
        } finally {
            small.recycle(); listOf(rgba, image, mask, back, front, sure, maybe, labels, stats, centres, kept, alpha, full).forEach { it.release() }
        }
    }

    /** How many copies fit in a row and a column of [sheet], 3 mm apart inside its margins. */
    fun grid(size: Size, sheet: Sheet): Pair<Int, Int> {
        val (w, h) = size.pixels(); val gap = 35
        val columns = (sheet.widthPx - 2 * sheet.marginPx + gap) / (w + gap); val rows = (sheet.heightPx - 2 * sheet.marginPx + gap) / (h + gap)
        // On A4, two rows are enough and save ink; the 10 x 15 print is filled.
        return columns.coerceAtLeast(1) to (if (sheet == Sheet.A4) min(rows, 2) else rows).coerceAtLeast(1)
    }

    /** Copies of [photo] on a white [sheet] at real size, each with a thin grey line to cut along. */
    fun sheet(photo: Bitmap, size: Size, sheet: Sheet): Bitmap {
        val (w, h) = size.pixels(); val (columns, rows) = grid(size, sheet); val gap = 35
        val out = Bitmap.createBitmap(sheet.widthPx, sheet.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out); canvas.drawColor(Color.WHITE)
        val line = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(200, 200, 200); strokeWidth = 2f }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val left = (sheet.widthPx - (columns * w + (columns - 1) * gap)) / 2
        val top = if (sheet == Sheet.A4) sheet.marginPx else (sheet.heightPx - (rows * h + (rows - 1) * gap)) / 2
        for (r in 0 until rows) for (c in 0 until columns) {
            val x = left + c * (w + gap); val y = top + r * (h + gap)
            canvas.drawBitmap(photo, null, Rect(x, y, x + w, y + h), paint)
            canvas.drawRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f, line)
        }
        return out
    }
}
