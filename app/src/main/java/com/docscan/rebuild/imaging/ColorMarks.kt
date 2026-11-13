package com.docscan.rebuild.imaging

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Removes pen and highlighter colour from a page (blue, red, green, yellow ink) and keeps black and grey print,
 * including print under a highlighter. Strongly coloured pixels become the surrounding paper; the soft edge
 * around them becomes paper where it is light and neutral grey of the same darkness where it is dark.
 * Coloured print (a red heading, a logo) is removed too, which is why it is a per-page option and off by default.
 * Black handwriting cannot be told from print this way and stays.
 */
object ColorMarks {
    private const val CHROMA = 22.0

    fun erase(rgba: Mat) {
        val rgb = Mat(); val lab = Mat(); val channels = ArrayList<Mat>(); val a = Mat(); val b = Mat(); val chroma = Mat()
        val strong = Mat(); val bright = Mat(); val mask = Mat(); val ring = Mat(); val gray = Mat(); val dark = Mat(); val light = Mat()
        val small = Mat(); val paper = Mat(); val paperRgba = Mat(); val grayRgba = Mat(); val target = Mat()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)
            channels[1].convertTo(a, CvType.CV_32F, 1.0, -128.0); channels[2].convertTo(b, CvType.CV_32F, 1.0, -128.0)
            Core.magnitude(a, b, chroma)
            Imgproc.threshold(chroma, chroma, CHROMA, 255.0, Imgproc.THRESH_BINARY); chroma.convertTo(strong, CvType.CV_8U)
            Imgproc.threshold(channels[0], bright, 25.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(strong, bright, strong)
            if (Core.countNonZero(strong) == 0) return
            Imgproc.dilate(strong, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            Core.subtract(mask, strong, ring)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, dark, 109.0, 255.0, Imgproc.THRESH_BINARY_INV)
            // The paper around: close away dark print at a quarter size, smooth, back to full size, neutral grey.
            Imgproc.resize(gray, small, Size(maxOf(1, gray.cols() / 4).toDouble(), maxOf(1, gray.rows() / 4).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
            Imgproc.medianBlur(small, small, 9)
            Imgproc.resize(small, paper, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(paper, paperRgba, Imgproc.COLOR_GRAY2RGBA)
            Imgproc.cvtColor(gray, grayRgba, Imgproc.COLOR_GRAY2RGBA)
            paperRgba.copyTo(rgba, strong)
            Core.bitwise_not(dark, light); Core.bitwise_and(ring, light, target); paperRgba.copyTo(rgba, target)
            Core.bitwise_and(ring, dark, target); grayRgba.copyTo(rgba, target)
        } finally {
            channels.forEach { it.release() }
            listOf(rgb, lab, a, b, chroma, strong, bright, mask, ring, gray, dark, light, small, paper, paperRgba, grayRgba, target).forEach { it.release() }
        }
    }
}
