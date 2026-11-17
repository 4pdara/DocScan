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

    @Test fun countingFindsEveryBoxAfterOneIsMarked() {
        // 1.3.5: basic counting on 35 box ends in a grid.
        ImagePipeline.ensureAvailable()
        val w = 1200; val h = 900
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val centres = ArrayList<Pair<Int, Int>>()
        Canvas(picture).apply {
            drawColor(Color.rgb(70, 70, 70))
            val face = Paint().apply { color = Color.rgb(210, 190, 170) }; val edge = Paint().apply { color = Color.rgb(160, 140, 120); style = Paint.Style.STROKE; strokeWidth = 4f }
            for (r in 0 until 5) for (c in 0 until 7) { val x = 120 + c * 150; val y = 110 + r * 160; drawRect(x - 60f, y - 65f, x + 60f, y + 65f, face); drawRect(x - 60f, y - 65f, x + 60f, y + 65f, edge); centres += x to y }
        }
        val m = org.opencv.core.Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(picture, m)
            val result = com.docscan.rebuild.imaging.ObjectCount.count(m, 120f / w, 110f / h)
            val hit = centres.count { (x, y) -> result.centres.any { (fx, fy) -> kotlin.math.hypot(fx * w - x.toDouble(), fy * h - y.toDouble()) < 30 } }
            assertEquals("found ${result.centres.size}", 35, hit); assertEquals(35, result.centres.size)
            // Less sensitive keeps the clear matches; a size given by the user is used as it is.
            assertEquals(.2f, com.docscan.rebuild.imaging.ObjectCount.count(m, 120f / w, 110f / h, size = .2f, sensitivity = .3f).size, .01f)
            // The count saved as marks: a ring and a number for each, inside the page.
            val (rings, numbers) = com.docscan.rebuild.ui.countMarks(result.centres, result.size, w, h)
            assertEquals(35, rings.size); assertEquals(35, numbers.size); assertEquals("35", numbers.last().text)
        } finally { m.release(); picture.recycle() }
    }

    @Test fun docRadarFindsADocumentPhotoInTheGalleryAndNotAPlainPhoto() {
        // 1.3.5: Doc Radar looks at the gallery's newest photos.
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pkg = context.packageName
        // pm grant works on every version (UiAutomation.grantRuntimePermission needs Android 9).
        fun grant(permission: String) = instrumentation.uiAutomation.executeShellCommand("pm grant $pkg $permission").close()
        if (android.os.Build.VERSION.SDK_INT >= 33) grant(android.Manifest.permission.READ_MEDIA_IMAGES) else grant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (android.os.Build.VERSION.SDK_INT < 29) grant(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        fun insert(name: String, draw: (Canvas) -> Unit): android.net.Uri {
            val b = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888); Canvas(b).also(draw)
            val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.nanoTime()}.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }
            val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try { context.contentResolver.openOutputStream(uri)!!.use { check(b.compress(Bitmap.CompressFormat.JPEG, 92, it)) } } finally { b.recycle() }
            return uri
        }
        val document = insert("radar-page") { c ->
            c.drawColor(Color.rgb(96, 70, 50))
            c.drawRect(220f, 260f, 980f, 1340f, Paint().apply { color = Color.rgb(245, 243, 236) })
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); textSize = 34f }
            for (i in 0 until 18) c.drawText("Invoice line $i for the radar test", 280f, 340f + i * 52, ink)
        }
        val plain = insert("radar-plain") { c ->
            for (y in 0 until 1600 step 8) c.drawRect(0f, y.toFloat(), 1200f, y + 8f, Paint().apply { color = Color.rgb(90 + y / 20, 140, 200 - y / 16) })
        }
        try {
            // The test saves its photos from the app itself, which the radar leaves out (pages DocScan saved to the
            // gallery are not new documents) on Android 10 and later, where the gallery records who saved a photo.
            val newest = com.docscan.rebuild.ui.latestPhotos(context, 10, own = true)
            assertTrue(newest.containsAll(listOf(document, plain)))
            if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(com.docscan.rebuild.ui.latestPhotos(context, 10).contains(document))
            val found = com.docscan.rebuild.ui.look(context, document)
            assertNotNull("the page photo is a document", found); found?.recycle()
            assertNull("a plain photo is not", com.docscan.rebuild.ui.look(context, plain))
        } finally { context.contentResolver.delete(document, null, null); context.contentResolver.delete(plain, null, null) }
    }

    @Test fun ownPageNetworkIsInstalledAndFindsAPageWithTheDetector() {
        // 1.3.6: DocScan's page network ships in the app (checked by its digest at build time) and runs on the phone.
        ImagePipeline.ensureAvailable()
        assertTrue("page network installed", com.docscan.rebuild.imaging.PageNet.available)
        val w = 900; val h = 1200
        val corners = listOf(.18f to .14f, .84f to .19f, .8f to .88f, .13f to .84f)
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            drawColor(Color.rgb(122, 86, 58))
            for (y in 0 until h step 14) drawRect(0f, y.toFloat(), w.toFloat(), y + 5f, Paint().apply { color = Color.rgb(110, 76, 50) })
            drawPath(Path().apply { corners.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }; close() }, Paint().apply { color = Color.rgb(240, 238, 230) })
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 40); textSize = 26f }
            save(); rotate(4f, w / 2f, h / 2f)
            for (i in 0 until 20) drawText("Line $i of the page network test, printed text", 210f, 250f + i * 38, ink)
            restore()
        }
        val rgba = org.opencv.core.Mat(); val gray = org.opencv.core.Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(picture, rgba); org.opencv.imgproc.Imgproc.cvtColor(rgba, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            val truth = com.docscan.rebuild.model.Frame(corners.map { com.docscan.rebuild.model.Vertex(it.first, it.second) })
            val started = System.nanoTime()
            val look = requireNotNull(com.docscan.rebuild.imaging.PageNet.look(rgba))
            val ms = (System.nanoTime() - started) / 1e6
            android.util.Log.i("DocScanRebuild", "Page network: %.0f ms".format(ms))
            val seen = requireNotNull(com.docscan.rebuild.imaging.PageNet.outline(look, w, h)) { "the network found no page" }
            assertTrue("network outline $seen", seen.motion(truth) < .05f)
            // The page's sides lie on the outline the network sees.
            assertTrue(com.docscan.rebuild.imaging.PageNet.onOutline(truth, look) > .3)
            // Detection with the network: the same page, the detector's outline kept where both agree.
            val found = requireNotNull(ImagePipeline.detectGray(gray, rgba))
            assertTrue("detected $found", found.motion(truth) < .04f)
            assertTrue("network time $ms ms", ms < 2000)
        } finally { rgba.release(); gray.release(); picture.recycle() }
    }

    @Test fun cameraOutlineFollowsTheNetworkAtOnceAndTakesTheDetectorsWhenStill() {
        // 1.3.7: in the camera the page network looks at every frame and the detector runs in the background.
        ImagePipeline.ensureAvailable()
        assertTrue(com.docscan.rebuild.imaging.PageNet.available)
        val w = 900; val h = 1200
        fun scene(dx: Float): Pair<Bitmap, com.docscan.rebuild.model.Frame> {
            val corners = listOf(.14f + dx to .14f, .62f + dx to .17f, .6f + dx to .78f, .1f + dx to .75f)
            val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(picture).apply {
                drawColor(Color.rgb(70, 74, 82))
                drawPath(Path().apply { corners.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }; close() }, Paint().apply { color = Color.rgb(238, 236, 228) })
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 40); textSize = 22f }
                for (i in 0 until 16) drawText("Moving page line $i", (corners[0].first + .05f) * w, 240f + i * 34, ink)
            }
            return picture to com.docscan.rebuild.model.Frame(corners.map { com.docscan.rebuild.model.Vertex(it.first, it.second) })
        }
        // The detector's runs wait in a queue the test empties itself, as a slow phone would leave them.
        val queue = ArrayDeque<Runnable>()
        var time = 0L
        val live = com.docscan.rebuild.imaging.LiveDetection({ queue.addLast(it) }, clock = { time })
        fun look(picture: Bitmap): com.docscan.rebuild.model.Frame? {
            val rgba = org.opencv.core.Mat(); val gray = org.opencv.core.Mat()
            try {
                org.opencv.android.Utils.bitmapToMat(picture, rgba); org.opencv.imgproc.Imgproc.cvtColor(rgba, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                return live.outline(gray, rgba)
            } finally { rgba.release(); gray.release() }
        }
        val (first, truth) = scene(0f); val (moved, movedTruth) = scene(.25f)
        try {
            // The first frame: the network's outline at once, before the detector has run.
            val quick = requireNotNull(look(first)); assertEquals(1, queue.size)
            assertTrue("network outline $quick", quick.motion(truth) < .05f)
            // The detector finishes; while the page stays, the outline is the detector's (they agree).
            queue.removeFirst().run(); time += 150
            val still = requireNotNull(look(first)); assertTrue("still $still", still.motion(truth) < .04f)
            assertNotNull(live.timing.networkMs); assertNotNull(live.timing.detectorMs)
            // The page moves a quarter of the view; the detector has not looked again, yet the outline follows it.
            time += 150
            val following = requireNotNull(look(moved)); assertTrue("followed $following", following.motion(movedTruth) < .05f)
            // 1.3.8: the detector's look at the frame before the move finishes now; the phone has moved since, so
            // the outline stays the network's on the moved page.
            queue.removeFirst().run(); time += 150
            val after = requireNotNull(look(moved)); assertTrue("after a late detector look $after", after.motion(movedTruth) < .05f)
        } finally { first.recycle(); moved.recycle(); while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
}
