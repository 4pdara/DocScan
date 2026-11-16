package com.docscan.rebuild.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clean-up of a cropped page (1.3.1), each one chosen per page in the editor and applied before the filter.
 * - [glare]: a reflection adds light over the page, so the print under it fades: its depth below the paper,
 *   relative to the paper, drops. A shadow darkens print and paper alike and leaves that ratio as it is. Where the
 *   paper is brighter than usual and its print has faded, the paper is brought down to the page's even lighting and
 *   the print gets back the depth it has elsewhere on the page. Where the photo is burnt out to white, nothing is
 *   left to restore and the paper is filled in.
 * - [fingers]: skin-coloured shapes that reach in from the page's edge (the hand holding a book open) are filled
 *   with the paper around them. Colours close to the paper's own (cream or yellowed paper) are never taken for skin.
 * - [moire]: a printed raster or a photographed screen leaves a fine regular pattern, which shows as sharp isolated
 *   peaks in the page's spectrum. They are removed tile by tile; print has no such isolated peaks, and the two axes
 *   (where the regular spacing of text lines and columns lives) are left alone.
 * Each returns whether it changed the page. All work on RGBA in place.
 */
object PageCleanup {
    private fun percentile(values: FloatArray, q: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        return sorted[((sorted.size - 1) * q).roundToInt().coerceIn(0, sorted.size - 1)]
    }

    private fun floats(m: Mat) = FloatArray(m.rows() * m.cols() * m.channels()).also { m.get(0, 0, it) }
    private fun bytes(m: Mat) = ByteArray(m.rows() * m.cols() * m.channels()).also { m.get(0, 0, it) }
    private fun floatMat(values: FloatArray, w: Int, h: Int) = Mat(h, w, CvType.CV_32FC1).also { it.put(0, 0, values) }

    /** The middle value of a 0..255 histogram. */
    private fun median(hist: IntArray, offset: Int = 0): Int {
        var total = 0; for (i in 0 until 256) total += hist[offset + i]
        var seen = 0; for (i in 0 until 256) { seen += hist[offset + i]; if (seen * 2 >= total) return i }
        return 255
    }

    /** Weighted least squares; null when singular. */
    private fun solve(rows: List<DoubleArray>, z: DoubleArray, w: DoubleArray): DoubleArray? {
        val n = rows[0].size; val a = Array(n) { DoubleArray(n + 1) }
        for (i in rows.indices) { val r = rows[i]; for (p in 0 until n) { a[p][n] += w[i] * r[p] * z[i]; for (q in 0 until n) a[p][q] += w[i] * r[p] * r[q] } }
        for (c in 0 until n) {
            val pivot = (c until n).maxBy { abs(a[it][c]) }; if (abs(a[pivot][c]) < 1e-9) return null
            val t = a[c]; a[c] = a[pivot]; a[pivot] = t
            for (r in 0 until n) if (r != c) { val f = a[r][c] / a[c][c]; for (k in c..n) a[r][k] -= f * a[c][k] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /** The page's even lighting: a quadratic surface through the paper level, leaving out the [skip] share (0..1). */
    private fun evenLighting(level: FloatArray, skip: FloatArray, w: Int, h: Int): FloatArray {
        fun terms(x: Int, y: Int): DoubleArray { val u = x / max(1.0, w - 1.0) - .5; val t = y / max(1.0, h - 1.0) - .5; return doubleArrayOf(1.0, u, t, u * u, u * t, t * t) }
        val rows = ArrayList<DoubleArray>(); val z = ArrayList<Double>(); val base = ArrayList<Double>()
        for (y in 0 until h step 3) for (x in 0 until w step 3) { rows += terms(x, y); z += level[y * w + x].toDouble(); base += 1.0 - min(1f, skip[y * w + x]) + 1e-3 }
        val zs = z.toDoubleArray(); val b = base.toDoubleArray(); var weights = b.copyOf()
        var coef = doubleArrayOf(level.average(), 0.0, 0.0, 0.0, 0.0, 0.0)
        repeat(4) {
            coef = solve(rows, zs, weights) ?: return@repeat
            weights = DoubleArray(zs.size) { i -> val r = abs(zs[i] - rows[i].indices.sumOf { j -> rows[i][j] * coef[j] }); b[i] * if (r > 8) 8 / r else 1.0 }
        }
        return FloatArray(w * h) { i -> val t = terms(i % w, i / w); t.indices.sumOf { j -> t[j] * coef[j] }.toFloat() }
    }

    /**
     * Rows [top, top + rows) of a small single-channel float map scaled up to a w × h page, with the same
     * pixel-centre mapping as resize, so a full-size copy of the map is never needed.
     */
    private fun stripOf(small: Mat, w: Int, h: Int, top: Int, rows: Int): FloatArray {
        val out = Mat(); val map = Mat(2, 3, CvType.CV_64F)
        try {
            val sx = small.cols().toDouble() / w; val sy = small.rows().toDouble() / h
            map.put(0, 0, sx, 0.0, .5 * sx - .5, 0.0, sy, (top + .5) * sy - .5)
            Imgproc.warpAffine(small, out, map, Size(w.toDouble(), rows.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            return floats(out)
        } finally { out.release(); map.release() }
    }

    /** Takes the reflection off the page and gives the print under it back its depth. */
    fun glare(rgba: Mat): Boolean {
        val w = rgba.cols(); val h = rgba.rows(); if (w < 16 || h < 16) return false
        val gray = Mat(); val small = Mat(); val closed = Mat(); val closedF = Mat(); val mid = Mat(); val midLevel = Mat()
        val rel = Mat(); val depthMid = Mat(); val depthMat = Mat(); val maps = mutableListOf<Mat>()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            // The paper level, with print closed away, on a small copy.
            val f = min(1.0, 500.0 / max(w, h)); val sw = max(8, (w * f).roundToInt()); val sh = max(8, (h * f).roundToInt())
            Imgproc.resize(gray, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val k = max(5, (min(sw, sh) * .04).toInt() or 1)
            val ellipse = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
            try { Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, ellipse) } finally { ellipse.release() }
            Imgproc.medianBlur(closed, closed, k)
            closed.convertTo(closedF, CvType.CV_32F)
            val level = floats(closedF)
            // How deep the print goes below the paper, as a share of the paper, measured on a finer copy so thin
            // print counts: the deepest print within about 3% of the page around each point.
            val f2 = min(1.0, 1000.0 / max(w, h)); val mw = max(8, (w * f2).roundToInt()); val mh = max(8, (h * f2).roundToInt())
            Imgproc.resize(gray, mid, Size(mw.toDouble(), mh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(closedF, midLevel, Size(mw.toDouble(), mh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val g = bytes(mid); val l = floats(midLevel)
            rel.create(mh, mw, CvType.CV_32FC1); rel.put(0, 0, FloatArray(mw * mh) { ((l[it] - (g[it].toInt() and 255)) / max(l[it], 1f)).coerceIn(0f, 1f) })
            val kd = max(5, (min(mw, mh) * .03).toInt() or 1)
            val rect = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kd.toDouble(), kd.toDouble()))
            try { Imgproc.dilate(rel, depthMid, rect) } finally { rect.release() }
            Imgproc.resize(depthMid, depthMat, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val three = Mat.ones(3, 3, CvType.CV_8UC1)
            try { Imgproc.erode(depthMat, depthMat, three) } finally { three.release() }
            val depth = floats(depthMat)
            val paper = percentile(level, .35)
            val printed = BooleanArray(depth.size) { depth[it] >= .08f }
            if (printed.count { it } < 50) return false
            val usual = FloatArray(depth.size) { if (printed[it] && level[it] <= paper + 4) depth[it] else -1f }.filter { it >= 0f }
            val reference = if (usual.size > 50) percentile(usual.toFloatArray(), .5) else percentile(depth.filterIndexed { i, _ -> printed[i] }.toFloatArray(), .5)
            if (reference < .15f) return false
            // Glare: paper brighter than the page's usual with faded print, or, without print, burnt out to white.
            val weight = FloatArray(depth.size) { i ->
                val bright = ((level[i] - paper - 4) / 12f).coerceIn(0f, 1f)
                val faded = if (printed[i]) ((1 - depth[i] / reference - .15f) / .3f).coerceIn(0f, 1f) else if (level[i] >= 248f) 1f else 0f
                bright * faded
            }
            val weightMat = floatMat(weight, sw, sh).also { maps += it }
            val kk = max(3, (min(sw, sh) * .03).toInt() or 1)
            val round = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kk.toDouble(), kk.toDouble()))
            try { Imgproc.dilate(weightMat, weightMat, round) } finally { round.release() }
            Imgproc.GaussianBlur(weightMat, weightMat, Size(0.0, 0.0), kk / 2.0)
            val spread = floats(weightMat)
            if ((spread.maxOrNull() ?: 0f) < .5f) return false
            val even = evenLighting(level, FloatArray(spread.size) { 2 * spread[it] }, sw, sh)
            val gain = FloatArray(depth.size) { if (printed[it]) (reference / max(depth[it], 1e-3f)).coerceIn(1f, 3f) else 1f }
            val evenMat = floatMat(even, sw, sh).also { maps += it }; val gainMat = floatMat(gain, sw, sh).also { maps += it }
            // Applied strip by strip at full size.
            var top = 0
            while (top < h) {
                val rows = min(128, h - top)
                val wm = stripOf(weightMat, w, h, top, rows)
                if (wm.any { it >= .004f }) {
                    val view = rgba.submat(Rect(0, top, w, rows)); val grayView = gray.submat(Rect(0, top, w, rows))
                    try {
                        val lv = stripOf(closedF, w, h, top, rows); val ev = stripOf(evenMat, w, h, top, rows); val gn = stripOf(gainMat, w, h, top, rows)
                        val px = bytes(view); val gv = bytes(grayView)
                        for (i in wm.indices) {
                            val m = wm[i]; if (m < .004f) continue
                            val v = (gv[i].toInt() and 255).toFloat()
                            val r = ((lv[i] - v) / max(lv[i], 1f)).coerceIn(0f, 1f)
                            val delta = (ev[i] * (1 - min(1f, r * gn[i])) - v) * m
                            for (c in 0..2) px[i * 4 + c] = ((px[i * 4 + c].toInt() and 255) + delta).roundToInt().coerceIn(0, 255).toByte()
                        }
                        view.put(0, 0, px)
                    } finally { view.release(); grayView.release() }
                }
                top += rows
            }
            return true
        } finally { listOf(gray, small, closed, closedF, mid, midLevel, rel, depthMid, depthMat).forEach(Mat::release); maps.forEach(Mat::release) }
    }

    /** Fills skin-coloured shapes reaching in from the page's edge (fingers holding the page) with paper. */
    fun fingers(rgba: Mat): Boolean {
        val w = rgba.cols(); val h = rgba.rows(); if (w < 16 || h < 16) return false
        val small = Mat(); val rgb = Mat(); val ycc = Mat(); val lab = Mat(); val gray = Mat(); val skin = Mat()
        val labels = Mat(); val stats = Mat(); val centroids = Mat(); val mask = Mat(); val full = Mat()
        try {
            val f = min(1.0, 1000.0 / max(w, h)); val sw = max(8, (w * f).roundToInt()); val sh = max(8, (h * f).roundToInt())
            Imgproc.resize(rgba, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Core.inRange(ycc, Scalar(40.0, 138.0, 77.0), Scalar(250.0, 178.0, 127.0), skin)
            val n = sw * sh
            val g = bytes(gray); val lb = bytes(lab); val sk = bytes(skin)
            // The paper's colour: the median of the lightest third of the page.
            val grays = IntArray(256); for (v in g) grays[v.toInt() and 255]++
            var light = 255; run { var seen = 0; for (i in 255 downTo 0) { seen += grays[i]; if (seen * 3 >= n) { light = i; break } } }
            val paperHist = IntArray(3 * 256)
            for (i in 0 until n) if ((g[i].toInt() and 255) >= light) for (c in 0..2) paperHist[c * 256 + (lb[i * 3 + c].toInt() and 255)]++
            val paper = IntArray(3) { median(paperHist, it * 256) }
            for (i in 0 until n) {
                val l = lb[i * 3].toInt() and 255; val a = lb[i * 3 + 1].toInt() and 255; val b = lb[i * 3 + 2].toInt() and 255
                if (hypot((a - paper[1]).toDouble(), (b - paper[2]).toDouble()) < 10 && l > paper[0] - 30) sk[i] = 0
            }
            skin.put(0, 0, sk)
            val k = max(3, (min(sw, sh) * .006).toInt() or 1).toDouble()
            val open = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k, k)); val close = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k * 3, k * 3))
            try { Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_OPEN, open); Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_CLOSE, close) } finally { open.release(); close.release() }
            val count = Imgproc.connectedComponentsWithStats(skin, labels, stats, centroids, 8, CvType.CV_32S)
            if (count < 2) return false
            val s = IntArray(count * 5).also { stats.get(0, 0, it) }
            val reach = (min(sw, sh) * .02).roundToInt()
            // A candidate comes in from an edge: it touches one, stays within the outer 30% on some side, and is
            // neither a speck nor most of the page.
            val slot = IntArray(count) { -1 }; var slots = 0
            for (i in 1 until count) {
                val x = s[i * 5]; val y = s[i * 5 + 1]; val bw = s[i * 5 + 2]; val bh = s[i * 5 + 3]; val area = s[i * 5 + 4]
                val touches = x <= reach || y <= reach || x + bw >= sw - reach || y + bh >= sh - reach
                val outer = x + bw <= sw * .3 || x >= sw * .7 || y + bh <= sh * .3 || y >= sh * .7
                if (touches && outer && area >= n * .001 && area <= n * .08) slot[i] = slots++
            }
            if (slots == 0) return false
            val ids = IntArray(n).also { labels.get(0, 0, it) }
            val hist = IntArray(slots * 3 * 256)
            for (i in 0 until n) { val sl = slot[ids[i]]; if (sl >= 0) for (c in 0..2) hist[(sl * 3 + c) * 256 + (lb[i * 3 + c].toInt() and 255)]++ }
            val paperChroma = hypot(paper[1] - 128.0, paper[2] - 128.0)
            // Its colour is clearly not the paper's: more saturated and of another hue, or much darker.
            val keep = BooleanArray(count) { i ->
                val sl = slot[i]; if (sl < 0) return@BooleanArray false
                val l = median(hist, (sl * 3) * 256); val a = median(hist, (sl * 3 + 1) * 256); val b = median(hist, (sl * 3 + 2) * 256)
                val apart = hypot((a - paper[1]).toDouble(), (b - paper[2]).toDouble())
                (apart > 10 && hypot(a - 128.0, b - 128.0) > paperChroma + 6) || paper[0] - l > 25
            }
            if (keep.none { it }) return false
            mask.create(sh, sw, CvType.CV_8UC1); mask.put(0, 0, ByteArray(n) { if (keep[ids[it]]) -1 else 0 })
            // Its soft edge and its shadow go too.
            val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k * 4, k * 4))
            try { Imgproc.dilate(mask, mask, grow) } finally { grow.release() }
            Imgproc.resize(mask, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.threshold(full, full, 0.0, 255.0, Imgproc.THRESH_BINARY)
            PageEraser.fill(rgba, full)
            return true
        } finally { listOf(small, rgb, ycc, lab, gray, skin, labels, stats, centroids, mask, full).forEach(Mat::release) }
    }

    /** Removes a fine regular pattern (a printed raster or a photographed screen): isolated peaks of the spectrum. */
    fun moire(rgba: Mat): Boolean {
        val w = rgba.cols(); val h = rgba.rows()
        val size = if (min(w, h) >= 1024) 512 else 256
        if (min(w, h) < size) return false
        val gray = Mat(); val win = Mat(); val notch = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val s = window(size)
            win.create(size, size, CvType.CV_32FC1); win.put(0, 0, FloatArray(size * size) { s[it / size] * s[it % size] })
            // The pattern's frequencies: the spectrum averaged over tiles spread across the page.
            val spectrum = FloatArray(size * size); var tiles = 0
            val stepY = max(size, (h - size) / 6); val stepX = max(size, (w - size) / 6)
            var y = 0
            while (y + size <= h) {
                var x = 0
                while (x + size <= w) {
                    val m = magnitude(gray, Rect(x, y, size, size), win, size)
                    for (i in m.indices) spectrum[i] += m[i]; tiles++
                    x += stepX
                }
                y += stepY
            }
            val log = FloatArray(size * size) { ln(spectrum[it] / tiles + 1e-3f) }
            fun freq(i: Int) = if (i < size / 2) i else i - size
            fun at(yy: Int, xx: Int) = log[((yy + size) % size) * size + (xx + size) % size]
            val ring = ArrayList<Pair<Int, Int>>()
            for (dy in -5..5) for (dx in -5..5) { val d = hypot(dx.toDouble(), dy.toDouble()); if (d >= 3 && d <= 5) ring += dy to dx }
            // The typical strength of each row and column of the spectrum, away from the axes. Lines of text repeat
            // down the page, which fills whole rows with energy; a printed raster stands out of its row and column.
            val rowLevel = FloatArray(size) { yy -> median(FloatArray(size) { xx -> if (abs(freq(xx)) > 2) log[yy * size + xx] else Float.NaN }) }
            val colLevel = FloatArray(size) { xx -> median(FloatArray(size) { yy -> if (abs(freq(yy)) > 2) log[yy * size + xx] else Float.NaN }) }
            // A peak: the highest point around it, at fine detail, off both axes, four times stronger than anything
            // 3 to 5 steps away, and ten times its row's and column's typical strength.
            val candidates = ArrayList<Pair<Int, Int>>(); val peaks = ArrayList<Pair<Int, Int>>(); val limit = ln(4f); val apart = ln(10f)
            for (yy in 0 until size) for (xx in 0 until size) {
                val fy = freq(yy); val fx = freq(xx)
                if (abs(fx) <= 2 || abs(fy) <= 2 || hypot(fx.toDouble(), fy.toDouble()) < size * .06) continue
                val v = log[yy * size + xx]
                var highest = true
                loop@ for (dy in -2..2) for (dx in -2..2) if ((dx != 0 || dy != 0) && at(yy + dy, xx + dx) > v) { highest = false; break@loop }
                if (!highest || v - ring.maxOf { (dy, dx) -> at(yy + dy, xx + dx) } <= limit) continue
                candidates += fy to fx
                if (v - rowLevel[yy] > apart && v - colLevel[xx] > apart) peaks += fy to fx
            }
            // The pattern's second and third harmonics go with it.
            val found = peaks.toList()
            for ((fy, fx) in candidates) if ((fy to fx) !in peaks && found.any { (py, px) -> (2..3).any { k -> abs(fy - k * py) <= 1 && abs(fx - k * px) <= 1 } }) peaks += fy to fx
            if (peaks.isEmpty()) return false
            val keep = FloatArray(size * size) { 1f }
            for ((py, px) in peaks) for (dy in -6..6) for (dx in -6..6) {
                val i = ((py + dy + size) % size) * size + (px + dx + size) % size
                keep[i] *= (1 - exp(-(dx * dx + dy * dy) / (2 * 1.5 * 1.5))).toFloat()
            }
            notch.create(size, size, CvType.CV_32FC2); notch.put(0, 0, FloatArray(size * size * 2) { keep[it / 2] })
            // A raster covers the blank paper too; a grid of letters (a monospaced font, lines alike) does not. What the
            // notch would take out must match the fine detail of the paper between the print.
            if (onPaper(gray, win, notch, size, stepX, stepY) < .3) return false
            removeTiles(rgba, gray, win, notch, size, s)
            return true
        } finally { gray.release(); win.release(); notch.release() }
    }

    /** The middle of the values that are not NaN. */
    private fun median(values: FloatArray): Float {
        val kept = values.filter { !it.isNaN() }.sorted(); return if (kept.isEmpty()) 0f else kept[kept.size / 2]
    }

    /**
     * Correlation, on paper pixels only (away from print), between the page's fine detail and what [notch] removes,
     * over the same tiles as the spectrum: near 1 for a pattern printed over everything, near 0 for print.
     */
    private fun onPaper(gray: Mat, win: Mat, notch: Mat, size: Int, stepX: Int, stepY: Int): Double {
        var num = 0.0; var gg = 0.0; var rr = 0.0
        val w = floats(win); val square = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)); val near = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        try {
            var y = 0
            while (y + size <= gray.rows()) {
                var x = 0
                while (x + size <= gray.cols()) {
                    val tile = gray.submat(Rect(x, y, size, size)); val f = Mat(); val complex = Mat(); val kept = Mat(); val back = Mat()
                    val paper = Mat(); val ink = Mat(); val blur = Mat()
                    try {
                        tile.convertTo(f, CvType.CV_32F)
                        val t = floats(f)
                        Imgproc.dilate(tile, paper, square)
                        val lifted = Mat(); Core.subtract(paper, Scalar(100.0), lifted)
                        Core.compare(tile, lifted, ink, Core.CMP_LT); lifted.release()
                        Imgproc.dilate(ink, ink, near)
                        val inked = bytes(ink)
                        Imgproc.GaussianBlur(f, blur, Size(0.0, 0.0), 6.0)
                        val smooth = floats(blur)
                        Core.subtract(f, Scalar(Core.mean(f).`val`[0]), f); Core.multiply(f, win, f)
                        Core.dft(f, complex, Core.DFT_COMPLEX_OUTPUT); Core.multiply(complex, notch, kept); Core.subtract(complex, kept, complex)
                        Core.idft(complex, back, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
                        val r = floats(back)
                        // The middle half of the tile, where the window is strong.
                        for (yy in size / 4 until size - size / 4) for (xx in size / 4 until size - size / 4) {
                            val i = yy * size + xx; if (inked[i].toInt() != 0) continue
                            val g = (t[i] - smooth[i]) * w[i]
                            num += g * r[i]; gg += g * g; rr += r[i].toDouble() * r[i]
                        }
                    } finally { listOf(tile, f, complex, kept, back, paper, ink, blur).forEach(Mat::release) }
                    x += stepX
                }
                y += stepY
            }
        } finally { square.release(); near.release() }
        return if (gg <= 0 || rr <= 0) 0.0 else num / kotlin.math.sqrt(gg * rr)
    }

    /** Sine-squared: half-overlapping windows add up to one. */
    private fun window(size: Int) = FloatArray(size) { sin(PI * (it + .5) / size).let { v -> (v * v).toFloat() } }

    private fun magnitude(gray: Mat, area: Rect, win: Mat, size: Int): FloatArray {
        val tile = gray.submat(area); val f = Mat(); val complex = Mat(); val planes = ArrayList<Mat>(); val mag = Mat()
        try {
            tile.convertTo(f, CvType.CV_32F)
            Core.subtract(f, Scalar(Core.mean(f).`val`[0]), f)
            Core.multiply(f, win, f)
            Core.dft(f, complex, Core.DFT_COMPLEX_OUTPUT)
            Core.split(complex, planes); Core.magnitude(planes[0], planes[1], mag)
            return FloatArray(size * size).also { mag.get(0, 0, it) }
        } finally { tile.release(); f.release(); complex.release(); planes.forEach(Mat::release); mag.release() }
    }

    /**
     * What the notch takes out of every half-overlapping windowed tile, summed and divided by the summed windows,
     * is subtracted from all colour channels alike. Done in bands of rows so the page is never held whole as floats.
     */
    private fun removeTiles(rgba: Mat, gray: Mat, win: Mat, notch: Mat, size: Int, s: FloatArray) {
        val w = rgba.cols(); val h = rgba.rows(); val half = size / 2; val band = size * 3
        fun starts(length: Int): IntArray {
            val list = ArrayList<Int>(); var p = 0
            while (p + size <= length) { list += p; p += half }
            if (list.last() + size < length) list += length - size
            return list.toIntArray()
        }
        var c0 = 0
        while (c0 < h) {
            val c1 = min(h, c0 + band)
            val from = max(0, c0 - half); val to = min(h, c1 + half)
            val rowsOf = gray.submat(from, to, 0, w); val src = Mat(); val padded = Mat(); val removed = Mat()
            try {
                rowsOf.convertTo(src, CvType.CV_32F)
                Core.copyMakeBorder(src, padded, half - (c0 - from), half - (to - c1), half, half, Core.BORDER_REFLECT)
                val ph = padded.rows(); val pw = padded.cols()
                removed.create(ph, pw, CvType.CV_32FC1); removed.setTo(Scalar(0.0))
                val ys = starts(ph); val xs = starts(pw)
                val normY = FloatArray(ph); val normX = FloatArray(pw)
                for (y in ys) for (i in 0 until size) normY[y + i] += s[i]
                for (x in xs) for (i in 0 until size) normX[x + i] += s[i]
                for (y in ys) for (x in xs) {
                    val t = padded.submat(Rect(x, y, size, size)); val target = removed.submat(Rect(x, y, size, size))
                    val f = Mat(); val complex = Mat(); val kept = Mat(); val back = Mat()
                    try {
                        Core.multiply(t, win, f)
                        Core.dft(f, complex, Core.DFT_COMPLEX_OUTPUT)
                        Core.multiply(complex, notch, kept)
                        Core.subtract(complex, kept, complex)
                        Core.idft(complex, back, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
                        Core.add(target, back, target)
                    } finally { listOf(t, target, f, complex, kept, back).forEach(Mat::release) }
                }
                val rows = c1 - c0
                val part = removed.submat(Rect(half, half, w, rows)).clone(); val view = rgba.submat(Rect(0, c0, w, rows))
                try {
                    val r = floats(part); val px = bytes(view)
                    for (yy in 0 until rows) {
                        val ny = normY[half + yy]
                        for (xx in 0 until w) {
                            val norm = ny * normX[half + xx]; if (norm < 1e-3f) continue
                            val d = r[yy * w + xx] / norm; val i = (yy * w + xx) * 4
                            for (c in 0..2) px[i + c] = ((px[i + c].toInt() and 255) - d).roundToInt().coerceIn(0, 255).toByte()
                        }
                    }
                    view.put(0, 0, px)
                } finally { part.release(); view.release() }
            } finally { rowsOf.release(); src.release(); padded.release(); removed.release() }
            c0 = c1
        }
    }
}
