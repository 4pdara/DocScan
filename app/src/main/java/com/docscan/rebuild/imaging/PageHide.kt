package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.HideKind
import com.docscan.rebuild.model.HideMark
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hidden areas, painted over the finished page (after the filter, before the marks): a solid black box, or a coarse
 * mosaic whose tiles (2.5% of the page's short side, larger than ordinary print) average away what is under them.
 * For sensitive details the black box is the safe choice: it leaves nothing of the original pixels.
 */
object PageHide {
    fun apply(image: Mat, marks: List<HideMark>) {
        if (marks.isEmpty() || image.empty()) return
        val w = image.cols(); val h = image.rows()
        val tile = max(6, (min(w, h) * .025).roundToInt())
        for (m in marks) {
            // Whole pixels that the area touches, so no partly covered edge pixel stays readable.
            val x0 = floor(min(m.a.x, m.b.x) * w).toInt().coerceIn(0, w); val x1 = ceil(max(m.a.x, m.b.x) * w).toInt().coerceIn(0, w)
            val y0 = floor(min(m.a.y, m.b.y) * h).toInt().coerceIn(0, h); val y1 = ceil(max(m.a.y, m.b.y) * h).toInt().coerceIn(0, h)
            if (x1 - x0 < 1 || y1 - y0 < 1) continue
            val area = image.submat(Rect(x0, y0, x1 - x0, y1 - y0))
            try {
                when (m.kind) {
                    HideKind.BLACK -> area.setTo(if (image.channels() == 4) Scalar(0.0, 0.0, 0.0, 255.0) else Scalar.all(0.0))
                    HideKind.MOSAIC -> {
                        val small = Mat(); val big = Mat()
                        try {
                            val cols = max(1, ceil((x1 - x0).toDouble() / tile).toInt()); val rows = max(1, ceil((y1 - y0).toDouble() / tile).toInt())
                            Imgproc.resize(area, small, Size(cols.toDouble(), rows.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                            Imgproc.resize(small, big, Size((x1 - x0).toDouble(), (y1 - y0).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
                            big.copyTo(area)
                        } finally { small.release(); big.release() }
                    }
                }
            } finally { area.release() }
        }
    }
}
