package com.docscan.rebuild.imaging

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.MarkColor
import com.docscan.rebuild.model.MarkFont
import com.docscan.rebuild.model.MarkPlace
import com.docscan.rebuild.model.StampPlace
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Watermark, timestamp and footer line drawn on the rendered page. Sizes follow the page, so previews and
 * exports match. Fonts are the bundled Hebrew fonts in assets/fonts (SIL OFL 1.1).
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
            }
        }.getOrElse { fallback(font) }
    }

    private fun fallback(font: MarkFont) = when (font) {
        MarkFont.PLAIN -> Typeface.DEFAULT
        MarkFont.CLASSIC, MarkFont.DAVID -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else -> Typeface.DEFAULT_BOLD
    }

    fun draw(bitmap: Bitmap, recipe: EditRecipe) {
        if (recipe.watermark == null && recipe.stamp == null && recipe.footer == null) return
        val canvas = Canvas(bitmap); val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat(); val short = min(w, h)
        recipe.watermark?.let { text ->
            val style = recipe.watermarkStyle
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(style.strength.alpha, Color.red(style.color.rgb), Color.green(style.color.rgb), Color.blue(style.color.rgb))
                typeface = typeface(style.font); textAlign = Paint.Align.CENTER
                textSize = short * style.size.scale * if (recipe.watermarkPlace == MarkPlace.DIAGONAL || recipe.watermarkPlace == MarkPlace.CENTER) .11f else .06f
            }
            val margin = short * .04f
            // The text runs along a line of this length; longer text is scaled down to fit.
            val (length, angle, x, y) = when (recipe.watermarkPlace) {
                MarkPlace.DIAGONAL -> Placement(hypot(w, h) * .8f, Math.toDegrees(-atan2(h, w).toDouble()).toFloat(), w / 2, h / 2)
                MarkPlace.CENTER -> Placement(w * .9f, 0f, w / 2, h / 2)
                MarkPlace.TOP -> Placement(w * .9f, 0f, w / 2, margin + paint.textSize / 2)
                MarkPlace.BOTTOM -> Placement(w * .9f, 0f, w / 2, h - margin - paint.textSize / 2)
                MarkPlace.SIDE -> Placement(h * .9f, -90f, margin + paint.textSize / 2, h / 2)
            }
            paint.measureText(text).takeIf { it > length }?.let { paint.textSize *= length / it }
            // Text scaled down along the side stays against the edge.
            val cx = if (recipe.watermarkPlace == MarkPlace.SIDE) margin + paint.textSize / 2 else x
            canvas.save(); canvas.rotate(angle, cx, y)
            canvas.drawText(text, cx, y - (paint.ascent() + paint.descent()) / 2, paint)
            canvas.restore()
        }
        // Footer line and timestamp: the footer first, then the time; both at the page edge, stacked when they share a place.
        listOfNotNull(recipe.footer?.let { it to recipe.footerPlace }, recipe.stamp?.let { it to recipe.stampPlace })
            .groupBy({ it.second }, { it.first }).forEach { (place, lines) -> notes(canvas, w, h, short, place, lines, recipe.noteColor) }
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

    private data class Placement(val length: Float, val angle: Float, val x: Float, val y: Float)
}
