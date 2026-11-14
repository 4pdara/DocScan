package com.docscan.rebuild.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Collage: several pages on one portrait A4 sheet (200 dpi), in a grid of [columns] x [rows] cells with white
 * margins. Each page keeps its proportions, as large as its cell allows and centred in it.
 */
object CollageLayout {
    const val WIDTH = 1654; const val HEIGHT = 2339

    /** The cell rectangles, row by row in reading order ([rightToLeft]: the first cell is at the right). */
    fun cells(columns: Int, rows: Int, rightToLeft: Boolean): List<RectF> {
        require(columns in 1..4 && rows in 1..4)
        val margin = WIDTH * .04f; val gap = WIDTH * .025f
        val cellWidth = (WIDTH - 2 * margin - (columns - 1) * gap) / columns
        val cellHeight = (HEIGHT - 2 * margin - (rows - 1) * gap) / rows
        return (0 until rows).flatMap { r -> (0 until columns).map { c ->
            val column = if (rightToLeft) columns - 1 - c else c
            val left = margin + column * (cellWidth + gap); val top = margin + r * (cellHeight + gap)
            RectF(left, top, left + cellWidth, top + cellHeight)
        } }
    }

    /** Draws up to columns x rows pages, asked for one at a time so only one full page is in memory. */
    fun compose(count: Int, columns: Int, rows: Int, rightToLeft: Boolean, page: (Int) -> Bitmap): Bitmap {
        val cells = cells(columns, rows, rightToLeft)
        require(count in 1..cells.size)
        val sheet = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val frame = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(225, 225, 225); strokeWidth = 2f }
        for (i in 0 until count) {
            val bitmap = page(i)
            try {
                val cell = cells[i]
                val scale = min(cell.width() / bitmap.width, cell.height() / bitmap.height)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                val target = RectF(cell.centerX() - w / 2, cell.centerY() - h / 2, cell.centerX() + w / 2, cell.centerY() + h / 2)
                canvas.drawBitmap(bitmap, null, target, paint)
                canvas.drawRect(target, frame)
            } finally { bitmap.recycle() }
        }
        return sheet
    }

    /** Pixels to render a page at for a cell of this grid: a little above the cell's size, for sharp downscaling. */
    fun edge(columns: Int, rows: Int) = (maxOf(WIDTH / columns, HEIGHT / rows) * 1.3).roundToInt()
}
