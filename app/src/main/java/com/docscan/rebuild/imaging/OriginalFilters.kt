package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Treatment
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * DocScan's own document filters. Written from published image-processing techniques
 * (background estimation by morphological closing, illumination division, white balance,
 * tone curves, saturation and unsharp masking). No reference binary, model or resource is used.
 *
 * Every function takes an RGBA [Mat] at full resolution and writes an RGB result of the same size;
 * the pipeline restores the source alpha afterwards.
 */
internal object OriginalFilters {
    /** Board colour used by the Board filter, a dark green chalkboard. */
    val BOARD_GREEN = doubleArrayOf(40.0, 74.0, 70.0)
    private const val BACKGROUND_EDGE = 900.0
    /**
     * Paper or not: the 75th percentile of lightness inside the document, away from its edges (a crop often takes in
     * a thin strip of the table or chair around a cover). Paper pages photographed in shade measure 147..208, dark
     * leather and cloth covers 33..74 (docs/FILTERS_1.2.6_HE.md). One clear switch between them: a blend of a
     * whitened and an unwhitened cover looked bleached and pink.
     */
    private const val PAPER_LIGHTNESS = 128.0

    fun apply(current: Mat, treatment: Treatment, result: Mat) {
        val rgb = Mat()
        try {
            Imgproc.cvtColor(current, rgb, Imgproc.COLOR_RGBA2RGB)
            when (treatment) {
                Treatment.PAPER -> cleanPaper(rgb, result)
                Treatment.NATIVE_NO_SHADOW -> removeShadows(rgb, result)
                Treatment.NATIVE_MAGIC_COLOR -> magicColor(rgb, result)
                Treatment.NATIVE_PRO_LOCAL -> pro(rgb, result)
                Treatment.NATIVE_INVERT -> { cleanPaper(rgb, result); Core.bitwise_not(result, result) }
                Treatment.NATIVE_BOARD -> board(rgb, result, paper = false)
                Treatment.NATIVE_BOARD_PAPER -> board(rgb, result, paper = true)
                // Board polarity is detected automatically, so inverting first would equal Board; this is the light variant.
                Treatment.NATIVE_INVERT_BOARD -> board(rgb, result, paper = false, lightSurface = true)
                else -> error("Not an original document filter: $treatment")
            }
        } finally { rgb.release() }
    }

    /** White paper, dark text, colours kept: illumination division with a black-point stretch. */
    private fun cleanPaper(rgb: Mat, out: Mat) {
        val paper = paperBackground(rgb)
        try {
            divide(rgb, paper.background, out, 255.0)
            levels(out, black = blackPoint(out), white = 238.0, gamma = 1.15)
        } finally { paper.release() }
    }

    /** Shadows and uneven light removed while the paper keeps its own tone. */
    private fun removeShadows(rgb: Mat, out: Mat) {
        val paper = paperBackground(rgb)
        try {
            val tone = paper.tone
            val channels = ArrayList<Mat>(3); val backgrounds = ArrayList<Mat>(3)
            Core.split(rgb, channels); Core.split(paper.background, backgrounds)
            try {
                for (c in 0 until 3) Core.divide(channels[c], backgrounds[c], channels[c], min(255.0, tone[c] * 1.02))
                Core.merge(channels, out)
            } finally { (channels + backgrounds).forEach(Mat::release) }
        } finally { paper.release() }
    }

    /** Bright white page, deeper text and livelier colour for colour documents. */
    private fun magicColor(rgb: Mat, out: Mat) = byDocumentTone(rgb, out) { paperLike ->
        val paper = paperBackground(rgb)
        try {
            divide(rgb, paper.background, paperLike, 255.0)
            levels(paperLike, black = blackPoint(paperLike) * .8, white = 232.0, gamma = 1.1)
            saturate(paperLike, 1.4)
            sCurve(paperLike, .35)
            sharpen(paperLike, sigma = 1.2, amount = .55)
        } finally { paper.release() }
    }

    /** Strongest cleanup: denoise, uniform white background, crisp ink, moderate colour. */
    private fun pro(rgb: Mat, out: Mat) = byDocumentTone(rgb, out) { paperLike ->
        val paper = paperBackground(rgb)
        val denoised = Mat()
        try {
            Imgproc.medianBlur(rgb, denoised, 3)
            divide(denoised, paper.background, paperLike, 255.0)
            levels(paperLike, black = blackPoint(paperLike), white = 226.0, gamma = 1.2)
            saturate(paperLike, 1.2)
            whitenPaper(paperLike)
            sharpen(paperLike, sigma = 1.0, amount = .7)
        } finally { paper.release(); denoised.release() }
    }

    /**
     * Light paper is evened out to white by [paperFilter]; a dark or deeply coloured document (a leather or cloth
     * book cover) is not paper, and dividing by its own surface would bleach it. Neither is a picture printed to its
     * edges (a magazine or booklet cover, a photograph): dividing by its own smoothed picture flattens and tints it.
     * Their colours are kept and only made clearer: their own range stretched, a little more colour and contrast,
     * sharper detail.
     */
    private fun byDocumentTone(rgb: Mat, out: Mat, paperFilter: (Mat) -> Unit) {
        if (isPaper(rgb)) paperFilter(out) else richColour(rgb, out)
    }

    /**
     * Paper is light even in shade (the 75th percentile of lightness inside the document, a tenth of its size away
     * from each edge), and once its print is closed over, most of it is one even tint: white, cream or yellowed, lit
     * warm or cool. A picture's surface keeps changing colour.
     */
    private fun isPaper(rgb: Mat): Boolean {
        val small = Mat(); val gray = Mat(); val surface = Mat(); val lab = Mat(); var kernel: Mat? = null
        try {
            val scale = min(1.0, 400.0 / max(rgb.cols(), rgb.rows()))
            Imgproc.resize(rgb, small, Size(max(1.0, rgb.cols() * scale), max(1.0, rgb.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
            val mx = small.cols() / 10; val my = small.rows() / 10
            val inner = small.submat(Rect(mx, my, max(1, small.cols() - 2 * mx), max(1, small.rows() - 2 * my)))
            try {
                Imgproc.cvtColor(inner, gray, Imgproc.COLOR_RGB2GRAY)
                if (percentile(gray, .75) < PAPER_LIGHTNESS) return false
                // The paper under the print: text and lines closed over, then smoothed.
                val k = max(3, (min(inner.cols(), inner.rows()) * .04).toInt() or 1)
                kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
                Imgproc.morphologyEx(inner, surface, Imgproc.MORPH_CLOSE, kernel)
                Imgproc.medianBlur(surface, surface, k)
                Imgproc.cvtColor(surface, lab, Imgproc.COLOR_RGB2Lab)
            } finally { inner.release() }
            val n = (lab.total()).toInt()
            val bytes = ByteArray(n * 3).also { lab.get(0, 0, it) }
            val l = IntArray(n) { bytes[it * 3].toInt() and 255 }
            val sorted = l.sortedArray()
            val bright = sorted[(n * .7).toInt().coerceAtMost(n - 1)]; val top = sorted[(n * .9).toInt().coerceAtMost(n - 1)]
            // The paper's own tint: the middle of the brightest part's colours.
            val a = ArrayList<Int>(); val b = ArrayList<Int>()
            for (i in 0 until n) if (l[i] >= bright) { a += bytes[i * 3 + 1].toInt() and 255; b += bytes[i * 3 + 2].toInt() and 255 }
            a.sort(); b.sort()
            val ta = a[a.size / 2].toDouble(); val tb = b[b.size / 2].toDouble()
            if (hypot(ta - 128, tb - 128) > 30) return false
            var even = 0
            for (i in 0 until n) if (l[i] >= top - 45 && hypot((bytes[i * 3 + 1].toInt() and 255) - ta, (bytes[i * 3 + 2].toInt() and 255) - tb) <= 8) even++
            return even >= .5 * n
        } finally { small.release(); gray.release(); surface.release(); lab.release(); kernel?.release() }
    }

    /** A document's own colours, clearer: its darkest and brightest half percent stretched, livelier colour, sharper. */
    private fun richColour(rgb: Mat, out: Mat) {
        rgb.copyTo(out)
        val small = Mat(); val gray = Mat()
        val (low, high) = try {
            val scale = min(1.0, 600.0 / max(rgb.cols(), rgb.rows()))
            Imgproc.resize(rgb, small, Size(max(1.0, rgb.cols() * scale), max(1.0, rgb.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
            percentile(gray, .005) to percentile(gray, .995)
        } finally { small.release(); gray.release() }
        levels(out, black = low * .7, white = max(high, low + 60.0), gamma = 1.0)
        saturate(out, 1.15)
        sCurve(out, .2)
        sharpen(out, sigma = 1.2, amount = .5)
    }

    /**
     * Strokes separated from a whiteboard or blackboard photo. [paper] false draws chalk on the board
     * colour ([lightSurface]: board-green strokes on a chalk-white surface instead); true draws dark ink
     * on white, keeping whiteboard marker colours.
     */
    private fun board(rgb: Mat, out: Mat, paper: Boolean, lightSurface: Boolean = false) {
        val gray = Mat(); val background = Mat(); val strength = Mat()
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            smoothBackground(gray, background, median = true)
            val lightBoard = Core.mean(background).`val`[0] >= 128.0
            // Stroke strength 0..255: how far each pixel departs from the board towards the stroke side.
            if (lightBoard) Core.subtract(background, gray, strength) else Core.subtract(gray, background, strength)
            strength.convertTo(strength, CvType.CV_8U, 255.0 / 70.0, -255.0 * 14.0 / 70.0)
            Imgproc.GaussianBlur(strength, strength, Size(0.0, 0.0), .7)
            val channels = ArrayList<Mat>(3)
            try {
                if (!paper) {
                    val chalk = doubleArrayOf(242.0, 242.0, 232.0)
                    val surface = if (lightSurface) chalk else BOARD_GREEN; val stroke = if (lightSurface) BOARD_GREEN else chalk
                    for (c in 0 until 3) Mat().also { strength.convertTo(it, CvType.CV_8U, (stroke[c] - surface[c]) / 255.0, surface[c]); channels += it }
                } else if (!lightBoard) {
                    for (c in 0 until 3) Mat().also { strength.convertTo(it, CvType.CV_8U, -230.0 / 255.0, 255.0); channels += it }
                } else {
                    // Keep the marker's hue: blend from white towards a darkened, illumination-corrected stroke colour.
                    val corrected = Mat(); val rgbBackground = Mat(); val ink = ArrayList<Mat>(3)
                    try {
                        smoothBackground(rgb, rgbBackground, median = true)
                        divide(rgb, rgbBackground, corrected, 255.0)
                        corrected.convertTo(corrected, -1, .7, 0.0)
                        Core.split(corrected, ink)
                        for (c in 0 until 3) {
                            // white - (white - ink) * strength
                            val channel = Mat(ink[c].size(), CvType.CV_8U, Scalar(255.0)); val missing = Mat()
                            try {
                                Core.subtract(channel, ink[c], missing)
                                Core.multiply(missing, strength, missing, 1.0 / 255.0)
                                Core.subtract(channel, missing, channel)
                                channels += channel
                            } catch (failure: Throwable) { channel.release(); throw failure }
                            finally { missing.release() }
                        }
                    } finally { corrected.release(); rgbBackground.release(); ink.forEach(Mat::release) }
                }
                Core.merge(channels, out)
            } finally { channels.forEach(Mat::release) }
        } finally { gray.release(); background.release(); strength.release() }
    }

    private class Paper(val background: Mat, val tone: DoubleArray) { fun release() = background.release() }

    /**
     * Estimates the unprinted paper per channel. Closing (dilation of the light paper) removes text,
     * a median removes the rest, and the result is clamped so that large dark content (photos, black
     * blocks) is not mistaken for shadow and bleached.
     */
    private fun paperBackground(rgb: Mat): Paper {
        val background = Mat()
        smoothBackground(rgb, background, median = false)
        val tone = percentilePerChannel(background, .9)
        Core.max(background, Scalar(max(1.0, tone[0] * .42), max(1.0, tone[1] * .42), max(1.0, tone[2] * .42)), background)
        return Paper(background, tone)
    }

    private fun smoothBackground(source: Mat, target: Mat, median: Boolean) {
        val small = Mat()
        try {
            val scale = min(1.0, BACKGROUND_EDGE / max(source.cols(), source.rows()))
            Imgproc.resize(source, small, Size(max(1.0, source.cols() * scale), max(1.0, source.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
            val shortEdge = min(small.cols(), small.rows())
            if (median) {
                // Board strokes can be light or dark; a wide median follows the board either way.
                Imgproc.medianBlur(small, small, odd((shortEdge / 12).coerceIn(5, 99)))
            } else {
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(odd((shortEdge / 45).coerceIn(5, 31)).toDouble(), odd((shortEdge / 45).coerceIn(5, 31)).toDouble()))
                try { Imgproc.dilate(small, small, kernel) } finally { kernel.release() }
                Imgproc.medianBlur(small, small, odd((shortEdge / 30).coerceIn(5, 41)))
            }
            Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), max(2.0, shortEdge / 120.0))
            Imgproc.resize(small, target, source.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        } finally { small.release() }
    }

    private fun divide(source: Mat, background: Mat, out: Mat, scale: Double) {
        Core.max(background, Scalar.all(1.0), background)
        Core.divide(source, background, out, scale)
    }

    /** Linear levels with gamma on all channels: [black]..[white] maps to 0..255. */
    private fun levels(image: Mat, black: Double, white: Double, gamma: Double) {
        val low = black.coerceIn(0.0, 120.0); val high = max(low + 40.0, white)
        lut(image) { v -> 255.0 * ((v - low) / (high - low)).coerceIn(0.0, 1.0).pow(gamma) }
    }

    /** Mild contrast S-curve centred on mid-grey; [strength] 0..1. */
    private fun sCurve(image: Mat, strength: Double) = lut(image) { v ->
        val x = v / 255.0; val s = x * x * (3 - 2 * x)
        255.0 * (x + (s - x) * strength)
    }

    private fun lut(image: Mat, curve: (Double) -> Double) {
        val table = Mat(1, 256, CvType.CV_8UC1)
        try {
            table.put(0, 0, ByteArray(256) { curve(it.toDouble()).roundToInt().coerceIn(0, 255).toByte() })
            Core.LUT(image, table, image)
        } finally { table.release() }
    }

    private fun saturate(rgb: Mat, factor: Double) {
        val hsv = Mat(); val channels = ArrayList<Mat>(3)
        try {
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, channels)
            channels[1].convertTo(channels[1], -1, factor, 0.0)
            Core.merge(channels, hsv)
            Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        } finally { hsv.release(); channels.forEach(Mat::release) }
    }

    private fun sharpen(rgb: Mat, sigma: Double, amount: Double) {
        val blur = Mat()
        try {
            Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), sigma)
            Core.addWeighted(rgb, 1.0 + amount, blur, -amount, 0.0, rgb)
        } finally { blur.release() }
    }

    /** Near-white, nearly neutral pixels become pure white so the page background is uniform. */
    private fun whitenPaper(rgb: Mat) {
        val hsv = Mat(); val bright = Mat(); val pale = Mat(); val mask = Mat()
        val channels = ArrayList<Mat>(3)
        try {
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, channels)
            Imgproc.threshold(channels[2], bright, 232.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(channels[1], pale, 28.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(bright, pale, mask)
            rgb.setTo(Scalar(255.0, 255.0, 255.0), mask)
        } finally { hsv.release(); bright.release(); pale.release(); mask.release(); channels.forEach(Mat::release) }
    }

    /** Darkest 0.5 % of a downsampled luminance histogram, used as the black point. */
    private fun blackPoint(rgb: Mat): Double {
        val small = Mat(); val gray = Mat()
        try {
            val scale = min(1.0, 600.0 / max(rgb.cols(), rgb.rows()))
            Imgproc.resize(rgb, small, Size(max(1.0, rgb.cols() * scale), max(1.0, rgb.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
            return min(90.0, percentile(gray, .005))
        } finally { small.release(); gray.release() }
    }

    private fun percentilePerChannel(image: Mat, fraction: Double): DoubleArray {
        val small = Mat(); val channels = ArrayList<Mat>(3)
        try {
            val scale = min(1.0, 400.0 / max(image.cols(), image.rows()))
            Imgproc.resize(image, small, Size(max(1.0, image.cols() * scale), max(1.0, image.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
            Core.split(small, channels)
            return DoubleArray(3) { percentile(channels[it], fraction) }
        } finally { small.release(); channels.forEach(Mat::release) }
    }

    private fun percentile(single: Mat, fraction: Double): Double {
        val bytes = ByteArray((single.total() * single.channels()).toInt())
        val continuous = if (single.isContinuous) single else single.clone()
        try { continuous.get(0, 0, bytes) } finally { if (continuous !== single) continuous.release() }
        val histogram = IntArray(256); bytes.forEach { histogram[it.toInt() and 255]++ }
        val target = (bytes.size * fraction).toLong(); var seen = 0L
        for (value in 0 until 256) { seen += histogram[value]; if (seen > target) return value.toDouble() }
        return 255.0
    }

    private fun odd(value: Int) = if (value % 2 == 0) value + 1 else value
}
