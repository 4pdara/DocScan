package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import com.docscan.rebuild.model.Vertex
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * DocScan's own page network (1.3.6): a small encoder-decoder (about 131 thousand weights) trained here, on synthetic
 * scenes and on the Mendeley "Document Corner Localization" photos (CC BY 4.0). From a 256 x 256 picture it gives,
 * at 128 x 128, how likely each point is to be on the page and on the page's outline. It runs on the phone through
 * OpenCV's DNN module; nothing leaves the phone.
 *
 * It does not replace the detector: [refine] keeps the detector's outline when the two agree, or when the network's runs
 * off the picture around it (one page of an open book), and otherwise takes the one whose four sides lie better on the
 * outline the network sees. The rule and its settings were chosen on half of
 * the held-out Mendeley photos and checked on the other half and on DocScan's own photo sets.
 */
object PageNet {
    /** Where the build puts the network's file among the app's assets. */
    const val ASSET = "pagenet/pagenet-v1.onnx"
    private const val SIDE = 256
    private const val OUT = 128
    /** Above this overlap the two outlines agree, and the detector's (sharper on the edges it finds) is kept. */
    private const val AGREE = .9

    @Volatile private var model: ByteArray? = null
    private var net: Net? = null
    private val lock = Any()

    /** The network's file (ONNX), from the app's assets; without it, detection is the detector's alone. */
    fun install(bytes: ByteArray?) { synchronized(lock) { model = bytes; net = null } }
    val available get() = model != null

    /** What the network sees: page and outline likelihoods, 128 x 128 each, row by row. */
    class Look(val page: FloatArray, val outline: FloatArray)

    fun look(rgba: Mat): Look? {
        val bytes = model ?: return null
        val bgr = Mat(); val small = Mat(); var blob: Mat? = null; var out: Mat? = null
        try {
            Imgproc.cvtColor(rgba, bgr, if (rgba.channels() == 4) Imgproc.COLOR_RGBA2BGR else if (rgba.channels() == 1) Imgproc.COLOR_GRAY2BGR else Imgproc.COLOR_RGB2BGR)
            Imgproc.resize(bgr, small, Size(SIDE.toDouble(), SIDE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            // The exported network takes BGR values 0..255 and gives probabilities.
            blob = Dnn.blobFromImage(small, 1.0, Size(SIDE.toDouble(), SIDE.toDouble()), Scalar(0.0, 0.0, 0.0), false, false, CvType.CV_32F)
            val values = FloatArray(2 * OUT * OUT)
            synchronized(lock) {
                val n = net ?: Dnn.readNetFromONNX(MatOfByte(*bytes)).also { net = it }
                n.setInput(blob)
                out = n.forward()
                val flat = out!!.reshape(1, 1); flat.get(0, 0, values); flat.release()
            }
            return Look(values.copyOfRange(0, OUT * OUT), values.copyOfRange(OUT * OUT, 2 * OUT * OUT))
        } finally { bgr.release(); small.release(); blob?.release(); out?.release() }
    }

    /**
     * The page's four corners from the network's page likelihood, for a picture of [width] x [height]: the largest
     * region above one half, its convex hull simplified to four points (or its smallest enclosing rectangle).
     */
    fun outline(look: Look, width: Int, height: Int): Frame? {
        // Worked on at most 640 pixels on the long side, as measured.
        val f = minOf(1.0, 640.0 / maxOf(width, height))
        val w = (width * f).roundToInt().coerceAtLeast(8); val h = (height * f).roundToInt().coerceAtLeast(8)
        val page = Mat(OUT, OUT, CvType.CV_32F); val prob = Mat(); val mask = Mat(); val labels = Mat(); val stats = Mat(); val centres = Mat(); val region = Mat()
        val contours = ArrayList<MatOfPoint>(); val hierarchy = Mat()
        try {
            page.put(0, 0, look.page)
            Imgproc.resize(page, prob, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(prob, mask, .5, 1.0, Imgproc.THRESH_BINARY); mask.convertTo(mask, CvType.CV_8U)
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centres, 8, CvType.CV_32S)
            if (n < 2) return null
            val largest = (1 until n).maxBy { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            Core.compare(labels, Scalar(largest.toDouble()), region, Core.CMP_EQ)
            Imgproc.findContours(region, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val hullIndex = MatOfInt(); Imgproc.convexHull(biggest, hullIndex)
            val all = biggest.toArray(); val hull = MatOfPoint2f(*hullIndex.toArray().map { Point(all[it].x, all[it].y) }.toTypedArray()); hullIndex.release()
            try {
                val perimeter = Imgproc.arcLength(hull, true)
                var corners: Array<Point>? = null
                for (k in 0 until 30) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(hull, approx, (.01 + .09 * k / 29) * perimeter, true)
                    val pts = approx.toArray(); approx.release()
                    if (pts.size == 4) { corners = pts; break }
                    if (pts.size < 4) break
                }
                if (corners == null) { corners = arrayOf(Point(), Point(), Point(), Point()); Imgproc.minAreaRect(hull).points(corners) }
                val frame = Frame(order(corners.map { Vertex((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }))
                return frame.takeIf { it.valid() }
            } finally { hull.release() }
        } finally {
            listOf(page, prob, mask, labels, stats, centres, region, hierarchy).forEach { it.release() }; contours.forEach { it.release() }
        }
    }

    /** The detector's outline, or the network's when they disagree and the network's sides lie better on its outline. */
    fun refine(detected: Frame?, rgba: Mat): Frame? {
        if (!available) return detected
        val look = look(rgba) ?: return detected
        val mine = outline(look, rgba.cols(), rgba.rows()) ?: return detected
        if (detected == null || detected == Frame.FULL) return mine
        if (overlap(detected, mine) >= AGREE) return detected
        // The network takes an open book's two pages as one; where its outline runs off the picture and the detector's
        // lies inside it, the detector chose one page on purpose (the whole page of an open book whose facing page
        // runs off the frame, as CamScanner does), and that is kept.
        if (runsOff(mine) && within(detected, mine) >= AGREE) return detected
        return if (onOutline(mine, look) > onOutline(detected, look)) mine else detected
    }

    /** A side of [frame] lies along the picture's border (both its corners within 2% of the same edge). */
    private fun runsOff(frame: Frame): Boolean = frame.points.indices.any { i ->
        val a = frame.points[i]; val b = frame.points[(i + 1) % 4]
        (a.x <= .02f && b.x <= .02f) || (a.x >= .98f && b.x >= .98f) || (a.y <= .02f && b.y <= .02f) || (a.y >= .98f && b.y >= .98f)
    }

    /** The share of [inner]'s area that lies inside [outer], on a 500 x 500 grid. */
    private fun within(inner: Frame, outer: Frame): Double {
        val mi = Mat.zeros(500, 500, CvType.CV_8U); val mo = Mat.zeros(500, 500, CvType.CV_8U); val both = Mat()
        try {
            fun draw(m: Mat, f: Frame) = Imgproc.fillPoly(m, listOf(MatOfPoint(*f.points.map { Point((it.x * 500).toInt().toDouble(), (it.y * 500).toInt().toDouble()) }.toTypedArray())), Scalar(1.0))
            draw(mi, inner); draw(mo, outer)
            Core.bitwise_and(mi, mo, both)
            return Core.countNonZero(both).toDouble() / maxOf(1, Core.countNonZero(mi))
        } finally { mi.release(); mo.release(); both.release() }
    }

    /** Overlap (intersection over union) of two outlines, drawn on a 500 x 500 grid. */
    fun overlap(a: Frame, b: Frame): Double {
        val ma = Mat.zeros(500, 500, CvType.CV_8U); val mb = Mat.zeros(500, 500, CvType.CV_8U); val both = Mat()
        try {
            fun draw(m: Mat, f: Frame) = Imgproc.fillPoly(m, listOf(MatOfPoint(*f.points.map { Point((it.x * 500).toInt().toDouble(), (it.y * 500).toInt().toDouble()) }.toTypedArray())), Scalar(1.0))
            draw(ma, a); draw(mb, b)
            Core.bitwise_and(ma, mb, both); val inter = Core.countNonZero(both)
            Core.bitwise_or(ma, mb, both); val union = Core.countNonZero(both)
            return inter.toDouble() / maxOf(1, union)
        } finally { ma.release(); mb.release(); both.release() }
    }

    /** The mean outline likelihood along the four sides of [frame] (lines 2 points wide on the 128 x 128 map). */
    fun onOutline(frame: Frame, look: Look): Double {
        val line = Mat.zeros(OUT, OUT, CvType.CV_8U)
        try {
            Imgproc.polylines(line, listOf(MatOfPoint(*frame.points.map { Point(Math.round(it.x * OUT.toDouble()).toDouble(), Math.round(it.y * OUT.toDouble()).toDouble()) }.toTypedArray())), true, Scalar(1.0), 2)
            val on = ByteArray(OUT * OUT); line.get(0, 0, on)
            var sum = 0.0; var n = 0
            for (i in on.indices) if (on[i].toInt() != 0) { sum += look.outline[i]; n++ }
            return if (n == 0) -1.0 else sum / n
        } finally { line.release() }
    }

    /** Clockwise from the top-left, as [Frame] keeps its corners. */
    private fun order(raw: List<Vertex>): List<Vertex> {
        val cx = raw.sumOf { it.x.toDouble() } / 4; val cy = raw.sumOf { it.y.toDouble() } / 4
        val points = raw.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = points.indices.minBy { points[it].x + points[it].y }
        return (0..3).map { points[(start + it) % 4] }
    }
}
