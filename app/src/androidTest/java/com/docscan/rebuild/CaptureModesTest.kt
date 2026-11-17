package com.docscan.rebuild

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.imaging.OfflineOcr
import com.docscan.rebuild.model.BusinessCard
import com.docscan.rebuild.model.Receipt
import com.docscan.rebuild.ui.vcard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** 1.3.4: long scan, business cards and receipts. */
@RunWith(AndroidJUnit4::class)
class CaptureModesTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store get() = (context as ScanApplication).store
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun jpeg(name: String, bitmap: Bitmap): File = File(context.cacheDir, "$name-${System.nanoTime()}.jpg").also { f -> f.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }

    @Test fun aLongReceiptPhotographedInThreePartsBecomesOnePage(): Unit = runBlocking {
        // A long receipt: lines of items, numbered so each line differs.
        val w = 700; val h = 2400
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(full).apply {
            drawColor(Color.rgb(250, 250, 246))
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20); textSize = 30f }
            var y = 60; var i = 1
            while (y < h - 40) { drawText("Item $i ${"abcdefghij".take(3 + i % 7)}", 40f, y.toFloat(), ink); drawText("${(i * 37) % 900}.${i % 100}", 520f, y.toFloat(), ink); y += 48; i++ }
        }
        // Three photos with about a quarter overlapping.
        val starts = listOf(0, 750, 1500); val tall = 900
        val files = starts.map { s -> val part = Bitmap.createBitmap(full, 0, s, w, minOf(tall, h - s)); try { jpeg("long-$s", part) } finally { part.recycle() } }
        val book = store.create("Long ${System.nanoTime()}")
        try {
            val parts = files.map { store.captured(book.id, it) }
            val hashes = parts.map { hash(store.source(it)) }
            val joined = store.longScan(book.id, parts, "Long parts")
            // One page is left in the document; the three photos are kept, unchanged, in a document in the trash.
            val left = store.dao.snapshot(book.id)
            assertEquals(listOf(joined.id), left.map { it.id })
            val holder = store.dao.allBooks().single { it.title == "Long parts" && it.trashedAt != null && store.dao.snapshot(it.id).map { p -> p.id }.toSet() == parts.map { p -> p.id }.toSet() }
            assertEquals(hashes, store.dao.snapshot(holder.id).sortedBy { it.position }.map { hash(store.source(it)) })
            // As long as the receipt, and the same receipt: every row band matches the original.
            ImagePipeline.ensureAvailable()
            val page = BitmapFactory.decodeFile(store.source(joined).path)
            try {
                assertEquals(w, page.width)
                assertTrue("height ${page.height}", abs(page.height - h) <= 12)
                fun row(b: Bitmap, y: Int) = IntArray(w) { x -> val c = b.getPixel(x, y); (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 }
                for (y in listOf(300, 850, 1200, 1650, 2200)) {
                    val best = (-4..4).minOf { dy -> val a = row(full, y); val b = row(page, y + dy); a.indices.sumOf { abs(a[it] - b[it]) }.toDouble() / w }
                    assertTrue("row $y differs by $best", best < 20)
                }
            } finally { page.recycle() }
            store.trash(setOf(holder.id), false); store.deleteForever(setOf(holder.id))
        } finally { full.recycle(); files.forEach { it.delete() }; store.trash(setOf(book.id), false); store.deleteForever(setOf(book.id)) }
    }

    @Test fun aBusinessCardAndAReceiptAreReadFromTheirScans(): Unit = runBlocking {
        fun page(width: Int, height: Int, lines: List<Pair<String, Float>>): File {
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(b).apply {
                    drawColor(Color.WHITE)
                    var y = 140f
                    for ((text, size) in lines) { drawText(text, 90f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }); y += size * 1.7f }
                }
                return jpeg("text", b)
            } finally { b.recycle() }
        }
        val book = store.create("Cards ${System.nanoTime()}")
        val card = page(1700, 1000, listOf("John Smith" to 90f, "Senior Engineer" to 56f, "Acme Group Ltd" to 56f, "+44 20 7946 0958" to 56f, "john.smith@acme.com" to 56f))
        val bill = page(1000, 1600, listOf("FRESH MARKET" to 70f, "16/11/2026" to 50f, "Milk 6.90" to 50f, "Bread 13.80" to 50f, "TOTAL 20.70" to 60f))
        try {
            val contact = BusinessCard.parse(OfflineOcr(store).recognize(store.captured(book.id, card), "eng"))
            assertEquals("John Smith", contact.name)
            assertEquals("john.smith@acme.com", contact.emails.single())
            assertEquals("+44 20 7946 0958", contact.phones.single().replace(Regex("\\s+"), " "))
            val receipt = Receipt.parse(OfflineOcr(store).recognize(store.captured(book.id, bill), "eng"))
            assertEquals("20.70", receipt.total); assertEquals("16/11/2026", receipt.date)
            val text = vcard("John Smith", "Acme; Group", "", listOf("+44 20 7946 0958", ""), "john.smith@acme.com", "", "")
            assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:John Smith\r\n") && text.contains("ORG:Acme\\; Group\r\n") && text.endsWith("END:VCARD\r\n"))
            assertEquals(1, Regex("TEL:").findAll(text).count())
        } finally { card.delete(); bill.delete(); store.trash(setOf(book.id), false); store.deleteForever(setOf(book.id)) }
    }
}
