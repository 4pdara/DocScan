package com.docscan.rebuild.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A text file as pages (1.3.3, TXT to PDF): A4 at 150 dpi with 2 cm margins and 11 pt type. Each paragraph runs in
 * its own direction (Hebrew from the right, English from the left), as a text editor shows it. Every page keeps the
 * text printed on it, so search, Word and text export use the exact text instead of reading the picture.
 */
object TextPages {
    const val WIDTH = 1240; const val HEIGHT = 1754
    private const val MARGIN = 118; private const val SIZE = 23f
    const val MAX_CHARS = 400_000; const val MAX_PAGES = 300

    /** The file's text: UTF-8 (with or without a byte-order mark), else the older Hebrew Windows encoding. */
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        fun strict(name: String) = try { Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString() } catch (e: CharacterCodingException) { null }
        val text = strict("UTF-8") ?: runCatching { strict("windows-1255") }.getOrNull() ?: String(body, Charsets.ISO_8859_1)
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
    }

    /** Lays the text out and hands each page (its picture and its text) to [page], in order. Returns the page count. */
    fun render(text: String, typeface: Typeface = Typeface.DEFAULT, page: (Bitmap, String) -> Unit): Int {
        val body = text.take(MAX_CHARS).ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20); textSize = SIZE; this.typeface = typeface }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, WIDTH - 2 * MARGIN)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, 1.2f).setIncludePad(false).build()
        val room = HEIGHT - 2 * MARGIN
        var first = 0; var count = 0
        while (first < layout.lineCount && count < MAX_PAGES) {
            val top = layout.getLineTop(first)
            var last = first
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= room) last++
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                canvas.save(); canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
                canvas.clipRect(0, top, WIDTH - 2 * MARGIN, layout.getLineBottom(last)); layout.draw(canvas); canvas.restore()
                page(bitmap, body.substring(layout.getLineStart(first), layout.getLineEnd(last)).trim('\n'))
            } finally { bitmap.recycle() }
            count++; first = last + 1
        }
        return count
    }
}
