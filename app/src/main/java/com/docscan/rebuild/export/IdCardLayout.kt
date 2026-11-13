package com.docscan.rebuild.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/**
 * Both sides of an ID card on one A4 page at the card's real size (ID-1: 85.6 x 54 mm), so the page prints
 * the card at 1:1. The page is 300 dpi; a PDF page 595 points wide is then exactly A4.
 */
object IdCardLayout {
    const val PAGE_WIDTH = 2480; const val PAGE_HEIGHT = 3508 // A4 at 300 dpi
    const val CARD_WIDTH = 1011; const val CARD_HEIGHT = 638   // 85.6 x 54 mm at 300 dpi

    /** The card upright and landscape: a card photographed in portrait is turned a quarter. */
    private fun landscape(card: Bitmap): Bitmap =
        if (card.height <= card.width) card else Bitmap.createBitmap(card, 0, 0, card.width, card.height, Matrix().apply { postRotate(-90f) }, true)

    fun compose(front: Bitmap, back: Bitmap?): Bitmap {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val left = (PAGE_WIDTH - CARD_WIDTH) / 2f
        listOfNotNull(front, back).forEachIndexed { index, side ->
            val card = landscape(side)
            try {
                // Upper and lower half of the page, each card centred in its half.
                val top = (PAGE_HEIGHT / 2f - CARD_HEIGHT) / 2f + index * PAGE_HEIGHT / 2f
                canvas.drawBitmap(card, null, RectF(left, top, left + CARD_WIDTH, top + CARD_HEIGHT), paint)
            } finally { if (card !== side) card.recycle() }
        }
        return page
    }
}
