package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.EraseKind
import com.docscan.rebuild.model.EraseMark
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smart erase without a learned model: the painted areas are filled with the paper around them. The paper is
 * estimated on a small copy of the page with dark strokes closed away (so neighbouring text is not smeared in),
 * the hole is filled from its border (Telea inpainting), and the result is blended in with a soft edge.
 * Tapping a word or blot ([EraseKind.SHAPE]) erases the dark shape under the finger: its letters, joined into
 * the word they belong to.
 */
object PageEraser {
    fun apply(image: Mat, marks: List<EraseMark>) {
        if (marks.isEmpty() || image.empty()) return
        val w = image.cols(); val h = image.rows(); val short = min(w, h)
        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        try {
            for (mark in marks) when (mark.kind) {
                EraseKind.STROKE -> {
                    val thickness = max(1, (mark.width * short).roundToInt())
                    val p = mark.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }
                    if (p.size == 1) Imgproc.circle(mask, p[0], max(1, thickness / 2), Scalar(255.0), -1)
                    else for (i in 1 until p.size) Imgproc.line(mask, p[i - 1], p[i], Scalar(255.0), thickness)
                }
                EraseKind.RECT -> {
                    val a = mark.points[0]; val b = mark.points[1]
                    Imgproc.rectangle(mask, Point(min(a.x, b.x).toDouble() * w, min(a.y, b.y).toDouble() * h), Point(max(a.x, b.x).toDouble() * w, max(a.y, b.y).toDouble() * h), Scalar(255.0), -1)
                }
                EraseKind.SHAPE -> shape(image, mask, mark.points[0].x, mark.points[0].y, mark.width)
            }
            if (Core.countNonZero(mask) > 0) fill(image, mask)
        } finally { mask.release() }
    }

    /** Adds the dark word or blot nearest to ([x], [y]) to [mask], looking within [radius] of the short side. */
    private fun shape(image: Mat, mask: Mat, x: Float, y: Float, radius: Float) {
        val w = image.cols(); val h = image.rows(); val short = min(w, h)
        val r = max(8, (radius * short).roundToInt()); val sx = (x * w).roundToInt().coerceIn(0, w - 1); val sy = (y * h).roundToInt().coerceIn(0, h - 1)
        val area = Rect(max(0, sx - r), max(0, sy - r), 0, 0).let { Rect(it.x, it.y, min(w, sx + r) - it.x, min(h, sy + r) - it.y) }
        if (area.width < 3 || area.height < 3) return
        val view = image.submat(area); val gray = Mat(); val dark = Mat(); val labels = Mat(); val stats = Mat(); val centres = Mat()
        val joined = Mat(); val joinedLabels = Mat(); val word = Mat(); val kernel = Mat(); val grow = Mat(); val target = mask.submat(area)
        try {
            Imgproc.cvtColor(view, gray, Imgproc.COLOR_RGBA2GRAY)
            val block = max(15, (short * .03).roundToInt()) or 1
            Imgproc.adaptiveThreshold(gray, dark, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 12.0)
            Imgproc.connectedComponentsWithStats(dark, labels, stats, centres, 8, CvType.CV_32S)
            val px = sx - area.x; val py = sy - area.y
            // The tapped pixel, or the nearest dark pixel close to it.
            var hit: Pair<Int, Int>? = if (labels.get(py, px)[0].toInt() != 0) px to py else null
            if (hit == null) {
                val reach = max(3, (short * .03).roundToInt())
                val x0 = max(0, px - reach); val y0 = max(0, py - reach); val x1 = min(area.width, px + reach + 1); val y1 = min(area.height, py + reach + 1)
                val window = labels.submat(y0, y1, x0, x1).clone()
                val values = IntArray((x1 - x0) * (y1 - y0)); window.get(0, 0, values); window.release()
                var best = Long.MAX_VALUE
                for (j in 0 until y1 - y0) for (i in 0 until x1 - x0) if (values[j * (x1 - x0) + i] != 0) {
                    val d = (x0 + i - px).toLong() * (x0 + i - px) + (y0 + j - py).toLong() * (y0 + j - py)
                    if (d < best) { best = d; hit = (x0 + i) to (y0 + j) }
                }
            }
            val (hx, hy) = hit ?: return
            val label = labels.get(hy, hx)[0].toInt()
            // Letters close together form the word: join them across gaps up to about a quarter of the letter height
            // (letter spacing), but not across the wider space between words.
            val letter = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt().coerceAtLeast(1)
            val across = max(1, (letter * .12).roundToInt()); val down = max(1, (letter * .12).roundToInt())
            val join = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0 * across + 1, 2.0 * down + 1))
            try { Imgproc.dilate(dark, joined, join) } finally { join.release() }
            Imgproc.connectedComponents(joined, joinedLabels, 8, CvType.CV_32S)
            val wordLabel = joinedLabels.get(hy, hx)[0]
            Core.compare(joinedLabels, Scalar(wordLabel), word, Core.CMP_EQ)
            Core.bitwise_and(dark, word, word)
            // A little around the ink, so its soft edge goes too.
            val edge = max(3, (short * .004).roundToInt()) or 1
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(edge.toDouble(), edge.toDouble()))
            try { Imgproc.dilate(word, grow, k) } finally { k.release() }
            Core.bitwise_or(target, grow, target)
        } finally {
            listOf(view, gray, dark, labels, stats, centres, joined, joinedLabels, word, kernel, grow, target).forEach { it.release() }
        }
    }

    /** Fills the masked pixels of [image] (RGBA) with the surrounding paper. */
    private fun fill(image: Mat, mask: Mat) {
        val w = image.cols(); val h = image.rows()
        val scale = min(1.0, 700.0 / max(w, h))
        val sw = max(1, (w * scale).roundToInt()); val sh = max(1, (h * scale).roundToInt())
        val smallRgba = Mat(); val small = Mat(); val smallMask = Mat(); val closed = Mat(); val filled = Mat(); val soft = Mat()
        try {
            Imgproc.resize(image, smallRgba, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(smallRgba, small, Imgproc.COLOR_RGBA2RGB)
            Imgproc.resize(mask, smallMask, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(smallMask, smallMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
            // Paper only: strokes up to about 6% of the page are closed away, even bold titles.
            val k = max(3, (min(sw, sh) * .06).roundToInt()) or 1
            val paper = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
            val one = Mat.ones(3, 3, CvType.CV_8UC1)
            try { Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, paper); Imgproc.dilate(smallMask, smallMask, one) } finally { paper.release(); one.release() }
            Photo.inpaint(closed, smallMask, filled, 5.0, Photo.INPAINT_TELEA)
            // A soft edge of about 0.3% of the page.
            val feather = max(1, (min(w, h) * .003).roundToInt())
            Imgproc.GaussianBlur(mask, soft, Size(2.0 * feather + 1, 2.0 * feather + 1), 0.0)
            val area = Imgproc.boundingRect(soft)
            if (area.width <= 0 || area.height <= 0) return
            val sx = sw.toDouble() / w; val sy = sh.toDouble() / h
            // Blend strip by strip so a whole-page erase never needs another full-size copy of the page.
            var top = area.y
            while (top < area.y + area.height) {
                val rows = min(256, area.y + area.height - top)
                val strip = Rect(area.x, top, area.width, rows)
                val view = image.submat(strip); val alpha = soft.submat(strip)
                val back = Mat(); val front = Mat(); val alpha3 = Mat(); val rest3 = Mat(); val a = Mat(); val b = Mat(); val mix = Mat()
                val map = Mat(2, 3, CvType.CV_64F)
                try {
                    // Paper for this strip: the small filled page scaled up with the same pixel-centre mapping as resize.
                    map.put(0, 0, sx, 0.0, (area.x + .5) * sx - .5, 0.0, sy, (top + .5) * sy - .5)
                    Imgproc.warpAffine(filled, back, map, Size(area.width.toDouble(), rows.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                    Imgproc.cvtColor(view, front, Imgproc.COLOR_RGBA2RGB)
                    Core.merge(listOf(alpha, alpha, alpha), alpha3)
                    Core.bitwise_not(alpha3, rest3)
                    Core.multiply(back, alpha3, a, 1.0 / 255)
                    Core.multiply(front, rest3, b, 1.0 / 255)
                    Core.add(a, b, mix)
                    Imgproc.cvtColor(mix, front, Imgproc.COLOR_RGB2RGBA)
                    front.copyTo(view)
                } finally { listOf(view, alpha, back, front, alpha3, rest3, a, b, mix, map).forEach { it.release() } }
                top += rows
            }
        } finally { listOf(smallRgba, small, smallMask, closed, filled, soft).forEach { it.release() } }
    }
}
