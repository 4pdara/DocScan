package com.docscan.rebuild.imaging

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.InkStroke
import com.docscan.rebuild.model.MarkColor
import com.docscan.rebuild.model.MarkFont
import com.docscan.rebuild.model.MarkPlace
import com.docscan.rebuild.model.MarkSpot
import com.docscan.rebuild.model.MarkStyle
import com.docscan.rebuild.model.SignatureMark
import com.docscan.rebuild.model.StampPlace
import com.docscan.rebuild.model.TextMark
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Marks drawn on the rendered page: brush, text, signatures, watermark, footer line, page number and timestamp.
 * Sizes follow the page, so previews and exports match. Fonts are the bundled Hebrew fonts in assets/fonts (SIL OFL 1.1).
 */
object PageMarks {
    @Volatile private var assets: AssetManager? = null
    private val faces = ConcurrentHashMap<MarkFont, Typeface>()

    fun install(context: Context) { assets = context.applicationContext.assets }

    fun typeface(font: MarkFont): Typeface = faces[font] ?: load(font).also { if (assets != null) faces[font] = it }

    private fun load(font: MarkFont): Typeface {
        val a = assets ?: return fallback(font)
        return runCatching {
            fun variable(file: String, weight: Int) = Typeface.Builder(a, "fonts/$file").setFontVariationSettings("'wght' $weight").build()
            when (font) {
                MarkFont.BOLD -> variable("Heebo.ttf", 800)
                MarkFont.PLAIN -> variable("Heebo.ttf", 400)
                MarkFont.CLASSIC -> variable("FrankRuhlLibre.ttf", 700)
                MarkFont.DAVID -> Typeface.createFromAsset(a, "fonts/DavidLibre-Bold.ttf")
                MarkFont.HEADLINE -> Typeface.createFromAsset(a, "fonts/SecularOne-Regular.ttf")
                MarkFont.FESTIVE -> Typeface.createFromAsset(a, "fonts/SuezOne-Regular.ttf")
                MarkFont.HANDWRITING -> Typeface.createFromAsset(a, "fonts/AmaticSC-Bold.ttf")
                MarkFont.SERIF -> variable("FrankRuhlLibre.ttf", 400)
            }
        }.getOrElse { fallback(font) }
    }

    private fun fallback(font: MarkFont) = when (font) {
        MarkFont.PLAIN -> Typeface.DEFAULT
        MarkFont.CLASSIC, MarkFont.DAVID -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        MarkFont.SERIF -> Typeface.SERIF
        else -> Typeface.DEFAULT_BOLD
    }

    fun draw(bitmap: Bitmap, recipe: EditRecipe) {
        if (!recipe.marked) return
        val canvas = Canvas(bitmap); val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat(); val short = min(w, h)
        // Brush first (a highlighter goes under the rest), then text, signatures, the watermark and the edge notes.
        recipe.ink.forEach { ink(canvas, w, h, it) }
        recipe.texts.forEach { text(canvas, w, h, it) }
        recipe.signatures.forEach { signature(canvas, w, h, it) }
        recipe.watermark?.let { watermark(canvas, w, h, it, recipe.watermarkPlace, recipe.watermarkStyle, recipe.watermarkSpot) }
        // Footer line, page number and timestamp at the page edge, stacked in that order when they share a place.
        listOfNotNull(recipe.footer?.let { it to recipe.footerPlace }, recipe.numberText?.let { it to recipe.numberPlace }, recipe.stamp?.let { it to recipe.stampPlace })
            .groupBy({ it.second }, { it.first }).forEach { (place, lines) -> notes(canvas, w, h, short, place, lines, recipe.noteColor) }
    }

    private fun watermarkPaint(style: MarkStyle, short: Float, place: MarkPlace) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(style.alpha, Color.red(style.rgb), Color.green(style.rgb), Color.blue(style.rgb))
        typeface = typeface(style.font); textAlign = Paint.Align.CENTER
        textSize = short * style.scale * when (place) { MarkPlace.DIAGONAL, MarkPlace.CENTER, MarkPlace.FREE -> .11f; MarkPlace.TILE -> .06f; else -> .06f }
    }

    /** Width and height of a free watermark's text on a [w] x [h] page, before it is turned. */
    fun watermarkBox(w: Float, h: Float, text: String, style: MarkStyle): Pair<Float, Float> {
        val paint = watermarkPaint(style, min(w, h), MarkPlace.FREE)
        val length = hypot(w, h) * .95f
        paint.measureText(text).takeIf { it > length }?.let { paint.textSize *= length / it }
        return paint.measureText(text) to paint.textSize
    }

    fun watermark(canvas: Canvas, w: Float, h: Float, text: String, place: MarkPlace, style: MarkStyle, spot: MarkSpot) {
        val short = min(w, h)
        val paint = watermarkPaint(style, short, place)
        val centre = -(paint.ascent() + paint.descent()) / 2
        when (place) {
            MarkPlace.FREE -> {
                val length = hypot(w, h) * .95f
                paint.measureText(text).takeIf { it > length }?.let { paint.textSize *= length / it }
                val x = spot.x * w; val y = spot.y * h
                canvas.save(); canvas.rotate(spot.angle, x, y)
                canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint)
                canvas.restore()
            }
            MarkPlace.TILE -> {
                // Rows of the text over the whole page, every other row shifted by half a step.
                val stepX = paint.measureText(text) + paint.textSize * 2f; val stepY = paint.textSize * 3.2f
                val reach = hypot(w, h) / 2 + stepX
                canvas.save(); canvas.rotate(spot.angle, w / 2, h / 2)
                var row = 0; var y = h / 2 - reach
                while (y <= h / 2 + reach) {
                    var x = w / 2 - reach + if (row % 2 == 0) 0f else stepX / 2
                    while (x <= w / 2 + reach) { canvas.drawText(text, x, y + centre, paint); x += stepX }
                    y += stepY; row++
                }
                canvas.restore()
            }
            else -> {
                val margin = short * .04f
                // The text runs along a line of this length; longer text is scaled down to fit.
                val (length, angle, x, y) = when (place) {
                    MarkPlace.DIAGONAL -> Placement(hypot(w, h) * .8f, Math.toDegrees(-atan2(h, w).toDouble()).toFloat(), w / 2, h / 2)
                    MarkPlace.TOP -> Placement(w * .9f, 0f, w / 2, margin + paint.textSize / 2)
                    MarkPlace.BOTTOM -> Placement(w * .9f, 0f, w / 2, h - margin - paint.textSize / 2)
                    MarkPlace.SIDE -> Placement(h * .9f, -90f, margin + paint.textSize / 2, h / 2)
                    else -> Placement(w * .9f, 0f, w / 2, h / 2)
                }
                paint.measureText(text).takeIf { it > length }?.let { paint.textSize *= length / it }
                // Text scaled down along the side stays against the edge.
                val cx = if (place == MarkPlace.SIDE) margin + paint.textSize / 2 else x
                canvas.save(); canvas.rotate(angle, cx, y)
                canvas.drawText(text, cx, y - (paint.ascent() + paint.descent()) / 2, paint)
                canvas.restore()
            }
        }
    }

    private fun textPaint(mark: TextMark, short: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(Color.red(mark.rgb), Color.green(mark.rgb), Color.blue(mark.rgb))
        typeface = typeface(mark.font); textAlign = Paint.Align.CENTER; textSize = mark.size * short
    }

    /** Where a text mark is drawn on a [w] x [h] page. */
    fun textBox(w: Float, h: Float, mark: TextMark): RectF {
        val paint = textPaint(mark, min(w, h)); val lines = mark.text.split('\n')
        val width = lines.maxOf { paint.measureText(it) }; val height = (lines.size - 1) * paint.textSize * 1.25f + (paint.descent() - paint.ascent())
        val x = mark.x * w; val y = mark.y * h
        return RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
    }

    fun text(canvas: Canvas, w: Float, h: Float, mark: TextMark) {
        val paint = textPaint(mark, min(w, h)); val lines = mark.text.split('\n')
        val step = paint.textSize * 1.25f; val x = mark.x * w; val y = mark.y * h
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, x, y + (i - (lines.size - 1) / 2f) * step - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    /** A brush stroke: a pen in its colour, or a highlighter (alpha below 255) that tints the page like a marker. */
    fun ink(canvas: Canvas, w: Float, h: Float, stroke: InkStroke) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(Color.red(stroke.rgb), Color.green(stroke.rgb), Color.blue(stroke.rgb))
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = maxOf(1f, stroke.width * min(w, h))
            if (stroke.alpha < 255) xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        val points = stroke.points.map { it.x * w to it.y * h }
        if (points.size == 1) { canvas.drawPoint(points[0].first, points[0].second, paint); return }
        canvas.drawPath(smooth(points), paint)
    }

    /** Midpoint quadratic smoothing through the sampled touch points. */
    private fun smooth(points: List<Pair<Float, Float>>) = Path().apply {
        moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size - 1) {
            val (cx, cy) = points[i]; val (nx, ny) = points[i + 1]
            quadTo(cx, cy, (cx + nx) / 2, (cy + ny) / 2)
        }
        lineTo(points.last().first, points.last().second)
    }

    private fun notes(canvas: Canvas, w: Float, h: Float, short: Float, place: StampPlace, lines: List<String>, colour: MarkColor) {
        val size = short * .028f; val margin = short * .03f
        val right = place == StampPlace.BOTTOM_RIGHT || place == StampPlace.TOP_RIGHT
        val left = place == StampPlace.BOTTOM_LEFT || place == StampPlace.TOP_LEFT
        val bottom = place == StampPlace.BOTTOM_RIGHT || place == StampPlace.BOTTOM_LEFT || place == StampPlace.BOTTOM_CENTER
        val align = if (right) Paint.Align.RIGHT else if (left) Paint.Align.LEFT else Paint.Align.CENTER
        val x = if (right) w - margin else if (left) margin else w / 2
        val room = if (right || left) w * .7f else w * .9f
        val gap = size * 1.35f
        lines.forEachIndexed { index, text ->
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(Color.red(colour.rgb), Color.green(colour.rgb), Color.blue(colour.rgb)); textSize = size; textAlign = align; typeface = typeface(MarkFont.PLAIN) }
            ink.measureText(text).takeIf { it > room }?.let { ink.textSize *= room / it }
            val halo = Paint(ink).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ink.textSize * .25f }
            val y = if (bottom) h - margin - (lines.size - 1 - index) * gap else margin + size + index * gap
            canvas.drawText(text, x, y, halo); canvas.drawText(text, x, y, ink)
        }
    }

    /** A signature as ink: smooth strokes whose width follows the signature's size, like a pen. */
    fun signature(canvas: Canvas, w: Float, h: Float, mark: SignatureMark) {
        val boxW = mark.width * w; val boxH = boxW / mark.aspect
        val left = mark.x * w; val top = mark.y * h
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(Color.red(mark.color.rgb), Color.green(mark.color.rgb), Color.blue(mark.color.rgb))
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = maxOf(1.5f, min(boxW, boxH * 3f) * .018f)
        }
        mark.strokes.forEach { stroke ->
            val points = stroke.map { left + it.x * boxW to top + it.y * boxH }
            if (points.size == 1) { canvas.drawPoint(points[0].first, points[0].second, ink); return@forEach }
            canvas.drawPath(smooth(points), ink)
        }
    }

    private data class Placement(val length: Float, val angle: Float, val x: Float, val y: Float)
}
