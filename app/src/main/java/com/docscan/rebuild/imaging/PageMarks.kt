package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.MarkPlace
import com.docscan.rebuild.model.StampPlace
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Watermark and timestamp drawn on the rendered page; sizes follow the page so previews and exports match. */
object PageMarks {
    fun draw(bitmap: Bitmap, recipe: EditRecipe) {
        if (recipe.watermark == null && recipe.stamp == null) return
        val canvas = Canvas(bitmap); val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat(); val short = min(w, h)
        recipe.watermark?.let { text ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 120, 120, 120); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                textSize = short * if (recipe.watermarkPlace == MarkPlace.DIAGONAL || recipe.watermarkPlace == MarkPlace.CENTER) .11f else .06f
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
            canvas.save(); canvas.rotate(angle, x, y)
            canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint)
            canvas.restore()
        }
        recipe.stamp?.let { text ->
            val size = short * .028f; val margin = short * .03f
            val right = recipe.stampPlace == StampPlace.BOTTOM_RIGHT || recipe.stampPlace == StampPlace.TOP_RIGHT
            val bottom = recipe.stampPlace == StampPlace.BOTTOM_RIGHT || recipe.stampPlace == StampPlace.BOTTOM_LEFT
            val align = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; style = Paint.Style.STROKE; strokeWidth = size * .25f; textAlign = align }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 40, 30); textSize = size; textAlign = align }
            val x = if (right) w - margin else margin; val y = if (bottom) h - margin else margin + size
            canvas.drawText(text, x, y, halo); canvas.drawText(text, x, y, ink)
        }
    }
    private data class Placement(val length: Float, val angle: Float, val x: Float, val y: Float)
}
