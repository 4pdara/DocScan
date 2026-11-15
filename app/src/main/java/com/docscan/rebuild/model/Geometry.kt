package com.docscan.rebuild.model

import kotlin.math.*

data class Vertex(val x: Float, val y: Float) {
    fun distance(other: Vertex) = hypot(x - other.x, y - other.y)
}

/** Clockwise in image coordinates: top-left, top-right, bottom-right, bottom-left. */
data class Frame(val points: List<Vertex>) {
    fun valid(): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val crosses = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return crosses.all { it > 0.0001f } && area() > 0.025f && points.indices.all { points[it].distance(points[(it + 1) % 4]) > .03f }
    }
    fun area(): Float = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]; (a.x * b.y - a.y * b.x).toDouble()
    }.toFloat()) / 2
    fun handles(): List<Vertex> = points + points.indices.map { i ->
        val a = points[i]; val b = points[(i + 1) % 4]; Vertex((a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    /** Edge handles translate both endpoints along the edge normal, in image pixel space. */
    fun dragHandle(handle: Int, dx: Float, dy: Float, aspect: Float = 1f): Frame {
        require(handle in 0..7 && aspect.isFinite() && aspect > 0f)
        if (!dx.isFinite() || !dy.isFinite()) return this
        val affected = if (handle < 4) listOf(handle) else listOf(handle - 4, (handle - 3) % 4)
        var x = dx; var y = dy
        if (handle >= 4) {
            val a = points[affected[0]]; val b = points[affected[1]]
            val nx = -(b.y - a.y); val ny = (b.x - a.x) * aspect
            val denominator = nx * nx + ny * ny
            if (denominator < .000001f) return this
            val distance = (dx * aspect * nx + dy * ny) / denominator
            x = nx * distance / aspect; y = ny * distance
        }
        var fraction = 1f
        affected.forEach { i ->
            val p = points[i]
            if (x > 0) fraction = min(fraction, (1 - p.x) / x) else if (x < 0) fraction = min(fraction, -p.x / x)
            if (y > 0) fraction = min(fraction, (1 - p.y) / y) else if (y < 0) fraction = min(fraction, -p.y / y)
        }
        repeat(12) {
            val candidate = Frame(points.mapIndexed { i, p -> if (i in affected) Vertex((p.x + x * fraction).coerceIn(0f, 1f), (p.y + y * fraction).coerceIn(0f, 1f)) else p })
            if (candidate.valid()) return candidate
            fraction *= .5f
        }
        return this
    }
    fun split(position: Float): Pair<Frame, Frame> {
        require(position.isFinite() && position in .15f.. .85f)
        fun between(a: Vertex, b: Vertex) = Vertex(a.x + (b.x-a.x)*position, a.y + (b.y-a.y)*position)
        val top = between(points[0], points[1]); val bottom = between(points[3], points[2])
        val left = Frame(listOf(points[0], top, bottom, points[3])); val right = Frame(listOf(top, points[1], points[2], bottom))
        require(left.valid() && right.valid())
        return left to right
    }
    fun motion(other: Frame) = points.zip(other.points).maxOf { (a, b) -> a.distance(b) }
    fun turned(degrees: Int): Frame {
        var vertices = points
        repeat(((degrees % 360 + 360) % 360) / 90) {
            val rotated = vertices.map { Vertex(1f - it.y, it.x) }
            vertices = listOf(rotated[3], rotated[0], rotated[1], rotated[2])
        }
        return Frame(vertices)
    }
    companion object {
        val FULL = Frame(listOf(Vertex(0f, 0f), Vertex(1f, 0f), Vertex(1f, 1f), Vertex(0f, 1f)))
        /**
         * The crop for a captured photo: the outline shown in the camera at the shot, refined by detection on the
         * full photo only when both agree (every corner within 4% of the frame); otherwise what the user saw.
         */
        fun choose(detected: Frame?, shown: Frame?): Frame? {
            val seen = shown?.takeIf { it.valid() } ?: return detected
            return if (detected != null && detected.motion(seen) <= .04f) detected else seen
        }
    }
}

/**
 * A saved automatic straightening: nine numbers of the first (polynomial) model, or the B-spline surface saved as
 * [across, down, across x down coefficients]. Coefficients are shares of the page height.
 */
object Flattening {
    fun valid(f: List<Float>): Boolean {
        if (f.size == 9) return f.all { it.isFinite() && it in -1f..1f }
        if (f.size < 2 || !f[0].isFinite() || !f[1].isFinite()) return false
        val across = f[0].toInt(); val down = f[1].toInt()
        if (across.toFloat() != f[0] || down.toFloat() != f[1] || across !in 4..32 || down !in 4..16) return false
        return f.size == 2 + across * down && f.drop(2).all { it.isFinite() && it in -1f..1f }
    }
}

/** [referenceEngineVariant]: the private research profile can route this filter to its engine; every build has an original implementation. */
enum class Treatment(val referenceEngineVariant: Boolean = false) {
    ORIGINAL, COLOR, GRAY, INK, PAPER,
    NATIVE_MAGIC_COLOR(true), NATIVE_NO_SHADOW(true), NATIVE_INVERT(true), NATIVE_PRO_LOCAL(true),
    LIGHTEN, ECO, NATIVE_BOARD(true), NATIVE_BOARD_PAPER(true), NATIVE_INVERT_BOARD(true)
}
/**
 * [watermark]: text drawn across the page in [watermarkStyle]; [stamp]: a date and time; [footer]: a line of the
 * user's own text ("scanned by ..."); [numberText]: the page number in [numbers] format, kept current by the store.
 * [stamp], [footer] and the number sit at the page edge in [noteColor]; when they share a place they are stacked.
 * [erase]: areas painted out with the paper around them (smart erase), applied before the filter. [ink] (brush),
 * [texts], [signatures] and the watermark are drawn after the filter so their colours stay. All positions are
 * fractions of the turned page. All are drawn on every output of the page; the source photo never changes.
 */
data class EditRecipe(val frame: Frame = Frame.FULL, val turns: Int = 0, val treatment: Treatment = Treatment.ORIGINAL, val brightness: Float = 0f, val contrast: Float = 1f, val outputAspect: Float? = null, val curvature: BookCurve = BookCurve(),
    val watermark: String? = null, val stamp: String? = null, val watermarkPlace: MarkPlace = MarkPlace.DIAGONAL, val stampPlace: StampPlace = StampPlace.BOTTOM_RIGHT,
    val watermarkStyle: MarkStyle = MarkStyle(), val footer: String? = null, val footerPlace: StampPlace = StampPlace.BOTTOM_CENTER, val noteColor: MarkColor = MarkColor.RED,
    val signatures: List<SignatureMark> = emptyList(), val flatten: List<Float> = emptyList(), val eraseColor: Boolean = false,
    val erase: List<EraseMark> = emptyList(), val ink: List<InkStroke> = emptyList(), val texts: List<TextMark> = emptyList(),
    val watermarkSpot: MarkSpot = MarkSpot(), val numbers: NumberFormat? = null, val numberPlace: StampPlace = StampPlace.BOTTOM_CENTER, val numberText: String? = null) {
    /** Everything drawn over the page removed: what text recognition should read. Erased areas stay erased. */
    fun withoutMarks() = copy(watermark = null, stamp = null, footer = null, signatures = emptyList(), ink = emptyList(), texts = emptyList(), numberText = null)
    val marked: Boolean get() = watermark != null || stamp != null || footer != null || numberText != null || signatures.isNotEmpty() || ink.isNotEmpty() || texts.isNotEmpty()
    fun valid() = frame.valid() && curvature.valid() && signatures.all { it.valid() } && erase.all { it.valid() } && ink.all { it.valid() } && texts.all { it.valid() } &&
        watermarkSpot.valid() && watermarkStyle.valid() && (flatten.isEmpty() || Flattening.valid(flatten)) &&
        (outputAspect == null || outputAspect.isFinite() && outputAspect in .1f..10f)

    /**
     * The page turned [quarters] times clockwise, with everything placed on it moving along. [aspect] is the
     * width / height of the page before turning; signatures keep their size on the page.
     */
    fun turnedBy(quarters: Int, aspect: Float): EditRecipe {
        var result = this; var a = aspect
        repeat(quarters.mod(4)) {
            fun t(v: Vertex) = Vertex(1f - v.y, v.x)
            result = result.copy(turns = (result.turns + 1).mod(4),
                erase = result.erase.map { m -> if (m.kind == EraseKind.RECT) m.copy(points = m.points.map(::t).let { p -> listOf(Vertex(min(p[0].x, p[1].x), min(p[0].y, p[1].y)), Vertex(max(p[0].x, p[1].x), max(p[0].y, p[1].y))) }) else m.copy(points = m.points.map(::t)) },
                ink = result.ink.map { it.copy(points = it.points.map(::t)) },
                texts = result.texts.map { val c = t(Vertex(it.x, it.y)); it.copy(x = c.x, y = c.y) },
                watermarkSpot = result.watermarkSpot.let { val c = t(Vertex(it.x, it.y)); it.copy(x = c.x, y = c.y) },
                signatures = result.signatures.map { s ->
                    // Same size on paper: the width share changes with the page width.
                    val boxH = s.width * a / s.aspect
                    val c = t(Vertex(s.x + s.width / 2, s.y + boxH / 2)); val width = (s.width * a).coerceIn(.03f, 1f)
                    val newBoxH = width / a / s.aspect
                    s.copy(x = c.x - width / 2, y = c.y - newBoxH / 2, width = width)
                })
            a = 1f / a
        }
        return result
    }
}

/** What smart erase paints out: a brush stroke [width] wide (a share of the page's short side), a rectangle (two corners), or the word or blot under a tap. */
enum class EraseKind { STROKE, RECT, SHAPE }
data class EraseMark(val kind: EraseKind, val points: List<Vertex>, val width: Float = .03f) {
    fun valid() = points.isNotEmpty() && points.size <= 5000 && points.all { it.x.isFinite() && it.y.isFinite() && it.x in -.2f..1.2f && it.y in -.2f..1.2f } &&
        width.isFinite() && width in .001f.. .5f && (kind != EraseKind.RECT || points.size == 2) && (kind != EraseKind.SHAPE || points.size == 1)
}
/** A brush stroke drawn over the page: [width] is a share of the page's short side, [rgb] its colour and [alpha] 255 for a pen, less for a highlighter. */
data class InkStroke(val points: List<Vertex>, val width: Float, val rgb: Int, val alpha: Int = 255) {
    fun valid() = points.isNotEmpty() && points.size <= 5000 && points.all { it.x.isFinite() && it.y.isFinite() && it.x in -.2f..1.2f && it.y in -.2f..1.2f } &&
        width.isFinite() && width in .001f.. .2f && alpha in 1..255
}
/** Text written on the page, centred at ([x], [y]); [size] is the letter height as a share of the page's short side. Lines split at newlines. */
data class TextMark(val text: String, val x: Float, val y: Float, val size: Float, val rgb: Int = 0x1A1A1A, val font: MarkFont = MarkFont.PLAIN) {
    fun valid() = text.isNotBlank() && text.length <= 500 && x.isFinite() && y.isFinite() && x in -.2f..1.2f && y in -.2f..1.2f && size.isFinite() && size in .005f.. .5f
}
/** Where a free watermark sits: its centre in page fractions and its angle in degrees (negative turns it up to the right). */
data class MarkSpot(val x: Float = .5f, val y: Float = .5f, val angle: Float = -45f) {
    fun valid() = x.isFinite() && y.isFinite() && angle.isFinite() && x in -.2f..1.2f && y in -.2f..1.2f && angle in -180f..180f
}
/** Page numbers: "3", "3 / 12" or "Page 3 of 12". */
enum class NumberFormat { PLAIN, SLASH, WORDS }

/** Colours offered for marks: watermark ink and brush. */
object MarkPalette {
    val watermark = listOf(0x787878, 0xBDBAB4, 0x1A1A1A, 0x5D3A1A, 0xC8281E, 0xFF2D2D, 0xF07840, 0xFFA040, 0xE0B000, 0x1E8C3C, 0x008F86, 0x1E50C8, 0x6A3FC8, 0xD62AF0, 0xFF3399, 0xFFA0D0)
    val ink = listOf(0xFFE600, 0xFFF59D, 0x1E88E5, 0xFF6B6B, 0xFF9800, 0x1DB9A0, 0xFFFFFF, 0x000000, 0xD32F2F, 0x2E7D32)
}

/**
 * A drawn signature: strokes as points within its own box (0..1 on both axes; the box is [aspect] times as wide as
 * tall), placed with its top-left corner at ([x], [y]) of the page and [width] of the page width wide.
 */
data class SignatureMark(val strokes: List<List<Vertex>>, val aspect: Float, val x: Float = .55f, val y: Float = .8f, val width: Float = .35f, val color: MarkColor = MarkColor.BLACK) {
    fun valid() = strokes.isNotEmpty() && strokes.all { it.isNotEmpty() } && aspect.isFinite() && aspect in .2f..20f &&
        x.isFinite() && y.isFinite() && width.isFinite() && width in .03f..1f && x in -.5f..1f && y in -.5f..1f
}

/**
 * Where a watermark is drawn: corner to corner, across the middle, along the top or bottom, up the side; [FREE]
 * where the user placed it ([EditRecipe.watermarkSpot]), or [TILE] repeated over the whole page at the spot's angle.
 */
enum class MarkPlace { DIAGONAL, CENTER, TOP, BOTTOM, SIDE, FREE, TILE }
/** Where the timestamp, page number or footer line is drawn: a corner, or centred at the bottom or top edge. */
enum class StampPlace { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT, BOTTOM_CENTER, TOP_CENTER }
/** Bundled Hebrew fonts (SIL OFL 1.1, see assets/licenses). */
enum class MarkFont { BOLD, PLAIN, CLASSIC, DAVID, HEADLINE, FESTIVE, HANDWRITING, SERIF }
enum class MarkColor(val rgb: Int) { GREY(0x787878), BLACK(0x1A1A1A), RED(0xC8281E), BLUE(0x1E50C8), GREEN(0x1E8C3C), ORANGE(0xE07814) }
/** Opacity of the watermark ink, 0-255. */
enum class MarkStrength(val alpha: Int) { LIGHT(40), MEDIUM(70), STRONG(130) }
enum class MarkSize(val scale: Float) { SMALL(.7f), MEDIUM(1f), LARGE(1.35f) }
/** Watermark look: font, colour ([rgb]), opacity ([alpha], 0-255) and size ([scale], 1 = normal). The enums are the preset choices. */
data class MarkStyle(val font: MarkFont = MarkFont.BOLD, val rgb: Int = MarkColor.GREY.rgb, val alpha: Int = MarkStrength.MEDIUM.alpha, val scale: Float = MarkSize.MEDIUM.scale) {
    constructor(font: MarkFont, color: MarkColor, strength: MarkStrength = MarkStrength.MEDIUM, size: MarkSize = MarkSize.MEDIUM) : this(font, color.rgb, strength.alpha, size.scale)
    fun valid() = alpha in 10..255 && scale.isFinite() && scale in .2f..4f
}

/** Vertical page warp after perspective correction, before rotation. Values are image-height fractions. */
data class BookCurve(val top: Float = 0f, val bottom: Float = 0f) {
    fun valid() = top.isFinite() && bottom.isFinite() && top in -.2f.. .2f && bottom in -.2f.. .2f
    val active: Boolean get() = top != 0f || bottom != 0f
    fun sourceY(x: Float, y: Float): Float = y + 4f*x*(1f-x)*(top*(1f-y) + bottom*y)
}

/** A capture is latched until the content changes or the paper leaves the frame. */
class CaptureStability {
    private var anchor: Frame? = null
    private var stableSince = 0L
    private var stableSamples = 0
    private var stableSignature: List<Float>? = null
    private var absentSince: Long? = null
    private var captured: List<Float>? = null
    data class Decision(val progress: Float, val capture: Boolean)
    @Synchronized fun sample(frame: Frame?, signature: List<Float>, now: Long): Decision {
        if (frame == null || !frame.valid()) {
            if (absentSince == null) absentSince = now
            if (now - (absentSince ?: now) >= 450) captured = null
            anchor = null; stableSamples = 0; stableSignature = null
            return Decision(0f, false)
        }
        absentSince = null
        val previous = captured
        if (previous != null && !contentChanged(previous, signature)) return Decision(1f, false)
        val origin = anchor
        // The outline found in the camera wanders a little from frame to frame even when the phone is still (an open
        // book's page edges and spine); a steadier hold than 2.5% of the frame would hardly ever be reached.
        if (origin == null || origin.motion(frame) > .025f || stableSignature?.let { contentChanged(it, signature) } == true) {
            anchor = frame; stableSince = now; stableSamples = 1; stableSignature = signature.toList()
            return Decision(0f, false)
        }
        stableSamples++
        val progress = ((now - stableSince) / 750f).coerceIn(0f, 1f)
        if (progress < 1f || stableSamples < 3) return Decision(progress, false)
        captured = signature.toList(); anchor = null; stableSamples = 0; stableSignature = null
        return Decision(1f, true)
    }
    @Synchronized fun failed() { captured = null; anchor = null; stableSamples = 0; stableSignature = null }
    @Synchronized fun markCaptured(signature: List<Float>) { captured = signature.toList(); anchor = null; stableSamples = 0; stableSignature = null }
    @Synchronized fun unstable() { anchor = null; stableSamples = 0; stableSignature = null }
    companion object {
        fun contentChanged(a: List<Float>, b: List<Float>): Boolean {
            if (a.size != b.size || a.isEmpty()) return true
            val meanA = a.average(); val meanB = b.average()
            return a.indices.count { abs((a[it] - meanA) - (b[it] - meanB)) > 12 } >= max(4, a.size / 5)
        }
    }
}



/** Temporal consensus keeps a single erroneous boundary proposal out of auto capture. */
class BoundaryTracker {
    private var tracked: Frame? = null
    private var pending: Frame? = null
    fun sample(candidate: Frame?): Frame? {
        if (candidate == null) { tracked = null; pending = null; return null }
        val previous = tracked
        // Nothing shown yet: the first outline appears at once (a phone may need most of a second for each look);
        // the next look smooths it, or replaces it only when two looks agree on the new place.
        if (previous == null) { tracked = candidate; pending = null; return candidate }
        if (previous.motion(candidate) > .065f) {
            val proposal = pending
            pending = candidate
            if (proposal == null || proposal.motion(candidate) > .025f) return null
            tracked = candidate; pending = null
            return candidate
        }
        pending = null
        val smoothed = Frame(previous.points.zip(candidate.points).map { (a,b) -> Vertex(a.x*.55f+b.x*.45f,a.y*.55f+b.y*.45f) })
        tracked = if (smoothed.valid()) smoothed else candidate
        return tracked
    }
}
