package com.docscan.rebuild

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.imaging.PageMarks
import com.docscan.rebuild.imaging.CodeReader
import com.docscan.rebuild.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RebuildFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val cameraPermission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)
    @get:Rule val storagePermission = if (Build.VERSION.SDK_INT <= 28)
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) else androidx.test.rule.GrantPermissionRule.grant()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fixture(name: String = "fixture.jpg"): File {
        val bitmap = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(40, 45, 50))
        val paint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(180f, 220f, 1620f, 2180f, paint)
        paint.color = Color.BLACK; paint.textSize = 28f
        repeat(35) { canvas.drawText("DocScan source ${it + 1} 0123456789", 250f, 350f + it * 45f, paint) }
        return File(context.cacheDir, name).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle() }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun selectFilter(treatment: Treatment) {
        val tag = "filter-${treatment.name}"
        compose.onNodeWithTag("filter-strip").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun screenshot(name: String) {
        val image = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val output = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        try { File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }

    @Test fun localProcessingWorksWhenTheOptionalArm64EngineIsUnavailable() {
        ImagePipeline.ensureAvailable()
        if (BuildConfig.UNIVERSAL_PREVIEW && Build.SUPPORTED_ABIS.first().startsWith("x86")) {
            assertFalse("Universal x86 installation must use its local processor", ImagePipeline.referenceEngineAvailable)
        }
        val input = Bitmap.createBitmap(321, 479, Bitmap.Config.ARGB_8888)
        Canvas(input).apply {
            drawColor(Color.rgb(155, 165, 145))
            drawRect(40f, 100f, 280f, 106f, Paint().apply { color = Color.BLACK })
        }
        val before = IntArray(321 * 479).also { input.getPixels(it, 0, 321, 0, 0, 321, 479) }
        try {
            for (treatment in Treatment.entries) {
                val output = ImagePipeline.transform(input, EditRecipe(treatment = treatment))
                try {
                    assertEquals(321, output.width); assertEquals(479, output.height)
                    assertEquals(255, Color.alpha(output.getPixel(160, 103)))
                } finally { output.recycle() }
            }
            val after = IntArray(before.size).also { input.getPixels(it, 0, 321, 0, 0, 321, 479) }
            assertArrayEquals(before, after)
        } finally { input.recycle() }
    }

    @Test fun cameraCapturesDurablePageAndDoneOpensReviewEditor() {
        val store = (compose.activity.application as ScanApplication).store
        val existing = runBlocking { store.dao.library().first().map { it.book.id }.toSet() }
        compose.onNodeWithTag("new-scan").performClick()
        val shutter = hasContentDescription(compose.activity.getString(R.string.capture)) and isEnabled()
        compose.waitUntil(30000) { compose.onAllNodes(shutter).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil(30000) { compose.onAllNodes(shutter).fetchSemanticsNodes().isNotEmpty() }
        screenshot("camera")
        compose.onNode(shutter).performClick()
        var captured: Sheet? = null
        compose.waitUntil(60000) {
            captured = runBlocking { store.dao.library().first().firstOrNull { it.book.id !in existing }?.let { store.dao.snapshot(it.book.id).firstOrNull() } }
            captured != null
        }
        val page = requireNotNull(captured)
        assertTrue(store.source(page).length() > 0); assertTrue(minOf(page.width, page.height) >= 480 && maxOf(page.width, page.height) >= 640)
        compose.waitUntil(30000) { compose.onAllNodes(hasText(compose.activity.getString(R.string.done)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("filter-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("page-preview").assertIsDisplayed()
        compose.onNodeWithTag("review-counter").assertIsDisplayed()
        screenshot("after-capture-review")
        compose.onNodeWithTag("save-edit").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("document-pages").assertIsDisplayed()
    }

    @Test fun leavingANewScanWithoutCapturingCreatesNoDocument() {
        val store = (compose.activity.application as ScanApplication).store
        val existing = runBlocking { store.dao.allBooks().map { it.id }.toSet() }
        compose.onNodeWithTag("new-scan").performClick()
        val shutter = hasContentDescription(compose.activity.getString(R.string.capture)) and isEnabled()
        compose.waitUntil(30000) { compose.onAllNodes(shutter).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("The scan exists only as a draft while the camera is open", 1, runBlocking { store.dao.allBooks().count { it.id !in existing } })
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("home-shortcuts").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { runBlocking { store.dao.allBooks().none { it.id !in existing } } }
    }

    @Test fun documentToolsWritePdfSizesExtractsLongImageWordAndImportedPdf(): Unit = runBlocking {
        val store = (compose.activity.application as ScanApplication).store
        val output = DocumentOutput(context, store)
        val book = store.create("Tools ${System.nanoTime()}")
        val first = store.captured(book.id, fixture("tools-1.jpg")); val second = store.captured(book.id, fixture("tools-2.jpg"))
        // Settings: both marks are off until turned on, and then reach new pages with the chosen text and places.
        val settings = MarkSettings(context)
        val saved = listOf(settings.watermarkOn, settings.stampOn, settings.footerOn)
        try {
            settings.watermarkOn = false; settings.stampOn = false; settings.footerOn = false
            assertEquals(EditRecipe(), settings.applyTo(EditRecipe()))
            settings.watermarkOn = true; settings.watermarkText = "עותק"; settings.watermarkPlace = MarkPlace.SIDE; settings.stampOn = true; settings.stampPlace = StampPlace.TOP_LEFT
            val style = MarkStyle(MarkFont.HANDWRITING, MarkColor.BLUE, MarkStrength.STRONG, MarkSize.LARGE); settings.watermarkStyle = style
            settings.footerOn = true; settings.footerText = "צולם ע\"י ישראל ישראלי"; settings.footerPlace = StampPlace.TOP_LEFT; settings.noteColor = MarkColor.GREEN
            val applied = settings.applyTo(EditRecipe())
            assertEquals("עותק", applied.watermark); assertEquals(MarkPlace.SIDE, applied.watermarkPlace); assertNotNull(applied.stamp); assertEquals(StampPlace.TOP_LEFT, applied.stampPlace)
            assertEquals(style, applied.watermarkStyle); assertEquals("צולם ע\"י ישראל ישראלי", applied.footer); assertEquals(StampPlace.TOP_LEFT, applied.footerPlace); assertEquals(MarkColor.GREEN, applied.noteColor)
            assertEquals(applied, RecipeCodec.decode(RecipeCodec.encode(applied)))
            // Every bundled font loads and draws Hebrew, and the footer stacks with the timestamp in a shared corner.
            val page = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
            MarkFont.values().forEach { font ->
                page.eraseColor(Color.WHITE); PageMarks.draw(page, EditRecipe(watermark = "שלום", watermarkPlace = MarkPlace.CENTER, watermarkStyle = MarkStyle(font, MarkColor.BLACK, MarkStrength.STRONG)))
                val px = IntArray(400 * 560).also { page.getPixels(it, 0, 400, 0, 0, 400, 560) }
                assertTrue("Font $font draws", px.count { it != Color.WHITE } > 300)
            }
            page.eraseColor(Color.WHITE); PageMarks.draw(page, applied.copy(watermark = null))
            val top = IntArray(400 * 60).also { page.getPixels(it, 0, 400, 0, 0, 400, 60) }
            assertTrue("Footer and time at the top left", top.count { it != Color.WHITE } > 200)
            page.recycle()
        } finally { settings.watermarkOn = saved[0]; settings.stampOn = saved[1]; settings.footerOn = saved[2] }
        // Watermark and timestamp are stored with the page edit and drawn on its output; the source never changes.
        val plain = ImagePipeline.render(store.source(first), first.edit())
        store.save(first, first.edit().copy(watermark = "טיוטה", stamp = "13.11.2026 10:00"))
        val marked = store.dao.page(first.id)!!.edit()
        assertEquals("טיוטה", marked.watermark); assertEquals("13.11.2026 10:00", marked.stamp)
        assertEquals(marked, RecipeCodec.decode(RecipeCodec.encode(marked)))
        val stamped = ImagePipeline.render(store.source(first), marked)
        try {
            assertEquals(plain.width, stamped.width); assertEquals(plain.height, stamped.height)
            val a = IntArray(plain.width * plain.height); val b = IntArray(a.size)
            plain.getPixels(a, 0, plain.width, 0, 0, plain.width, plain.height); stamped.getPixels(b, 0, plain.width, 0, 0, plain.width, plain.height)
            assertTrue("Marks are drawn", a.indices.count { a[it] != b[it] } > 1000)
        } finally { plain.recycle(); stamped.recycle() }
        fun pages(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } }
        val full = output.pdf(book.id); val small = output.pdf(book.id, DocumentOutput.PdfSize.SMALL); val one = output.pdf(book.id, pageIds = setOf(second.id))
        val longImage = output.longImage(book.id); val word = output.word(listOf("שלום עולם\nספר", "second page & <tags>"))
        try {
            assertEquals(2, pages(full)); assertEquals(2, pages(small)); assertEquals(1, pages(one))
            assertTrue("Small PDF ${small.length()} < original ${full.length()}", small.length() < full.length())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(longImage.path, bounds)
            assertTrue(bounds.outWidth in 1..2000); assertTrue("Both pages are stacked", bounds.outHeight > bounds.outWidth * 2)
            java.util.zip.ZipFile(word).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
                assertTrue(xml.contains("שלום עולם") && xml.contains("<w:bidi/>") && xml.contains("&amp; &lt;tags&gt;") && xml.contains("w:type=\"page\""))
                assertNotNull(zip.getEntry("[Content_Types].xml")); assertNotNull(zip.getEntry("_rels/.rels"))
            }
            // A PDF imports as one page per PDF page.
            val imported = store.create("Imported ${System.nanoTime()}")
            val sheets = store.importPdf(imported.id, Uri.fromFile(full))
            assertEquals(2, sheets.size); assertTrue(sheets.all { it.width > 1000 && store.source(it).length() > 0 })
            store.trash(setOf(imported.id)); store.deleteForever(setOf(imported.id))
        } finally { listOf(full, small, one, longImage, word).forEach { it.delete() } }
        store.trash(setOf(book.id)); store.deleteForever(setOf(book.id))
    }

    @Test fun qrIdCardSignatureAndPasswordPdf(): Unit = runBlocking {
        // QR code and barcode: in a photo-like picture (tilted, among clutter) and from a camera frame's luma.
        fun drawn(matrix: com.google.zxing.common.BitMatrix) = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { b ->
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) b.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        val link = "https://example.com/סריקה?id=42"
        val qr = drawn(com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 360, 360, mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8")))
        val ean = drawn(com.google.zxing.oned.EAN13Writer().encode("5901234123457", com.google.zxing.BarcodeFormat.EAN_13, 420, 160))
        val photo = Bitmap.createBitmap(1400, 1000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(photo); canvas.drawColor(Color.rgb(170, 160, 140))
            val clutter = Paint().apply { color = Color.rgb(60, 60, 70) }; repeat(30) { canvas.drawCircle(37f * it % 1400, 53f * it % 1000, 12f, clutter) }
            canvas.save(); canvas.rotate(12f, 380f, 420f); canvas.drawRect(170f, 210f, 590f, 630f, Paint().apply { color = Color.WHITE }); canvas.drawBitmap(qr, 200f, 240f, null); canvas.restore()
            canvas.drawBitmap(ean, 820f, 700f, null)
            val codes = CodeReader.image(photo).associateBy { it.text }
            assertEquals(com.google.zxing.client.result.ParsedResultType.URI, codes[link]?.kind)
            assertEquals(com.google.zxing.BarcodeFormat.EAN_13, codes["5901234123457"]?.format)
            val pixels = IntArray(qr.width * qr.height).also { qr.getPixels(it, 0, qr.width, 0, 0, qr.width, qr.height) }
            val luma = ByteArray(pixels.size) { (Color.red(pixels[it]) * 299 + Color.green(pixels[it]) * 587 + Color.blue(pixels[it]) * 114).div(1000).toByte() }
            assertEquals(link, CodeReader.frame(luma, qr.width, qr.width, qr.height)?.text)
        } finally { photo.recycle(); qr.recycle(); ean.recycle() }

        val store = (compose.activity.application as ScanApplication).store
        // ID card: two sides become one A4 page at 300 dpi; the side photos and their originals are gone.
        val cards = store.create("ID ${System.nanoTime()}")
        val front = store.captured(cards.id, fixture("front.jpg")); val back = store.captured(cards.id, fixture("back.jpg"))
        val card = store.idCard(cards.id, front, back)
        assertEquals(listOf(card.id), store.dao.snapshot(cards.id).map { it.id })
        assertEquals(com.docscan.rebuild.export.IdCardLayout.PAGE_WIDTH, card.width); assertEquals(com.docscan.rebuild.export.IdCardLayout.PAGE_HEIGHT, card.height)
        assertFalse(File(context.filesDir, "masters/${front.source}").exists()); assertFalse(File(context.filesDir, "masters/${back.source}").exists())
        val composed = ImagePipeline.render(store.source(card), card.edit(), 1000)
        try {
            // Card areas are the rendered photos (dark frame around a white page); the rest of the A4 page is white.
            val px = { x: Float, y: Float -> composed.getPixel((x * composed.width).toInt(), (y * composed.height).toInt()) }
            // Each card is 1011 px of 2480 wide, centred in its half; its left edge is the photo's dark border.
            assertTrue(Color.red(px(.05f, .5f)) > 245 && Color.red(px(.5f, .5f)) > 245)
            assertTrue(Color.red(px(.31f, .25f)) < 120 && Color.red(px(.31f, .75f)) < 120)
        } finally { composed.recycle() }

        // Signature: stored as strokes in the edit, drawn on every output, survives encoding.
        val (strokes, aspect) = requireNotNull(SignatureCodec.normalize(listOf(listOf(10f to 80f, 60f to 20f, 110f to 90f, 170f to 30f), listOf(40f to 100f, 160f to 100f))))
        val mark = SignatureMark(strokes, aspect, x = .5f, y = .7f, width = .35f, color = MarkColor.BLUE)
        assertTrue(mark.valid())
        store.save(card, card.edit().copy(signatures = listOf(mark)))
        val signed = requireNotNull(store.dao.page(card.id)).edit()
        assertEquals(listOf(mark), signed.signatures); assertEquals(signed, RecipeCodec.decode(RecipeCodec.encode(signed)))
        val ink = ImagePipeline.render(store.source(card), signed, 1000)
        try {
            var blue = 0
            for (y in (ink.height * .7f).toInt() until minOf(ink.height, (ink.height * .85f).toInt())) for (x in (ink.width * .5f).toInt() until (ink.width * .85f).toInt()) {
                val c = ink.getPixel(x, y); if (Color.blue(c) > 150 && Color.red(c) < 90) blue++
            }
            assertTrue("Signature ink drawn: $blue", blue > 200)
        } finally { ink.recycle() }

        // Password PDF: AES-256, refused without the password.
        val locked = DocumentOutput(context, store).protectedPdf(cards.id, "סיסמה1234")
        try {
            val head = locked.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(head.startsWith("%PDF-1.7") && head.contains("/V 5 /R 6") && head.contains("/AESV3") && head.contains("/Encrypt 3 0 R"))
            val opened = runCatching { ParcelFileDescriptor.open(locked, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } }
            assertTrue("A password PDF must not open without the password: $opened", opened.isFailure)
        } finally { locked.delete() }
        store.trash(setOf(cards.id)); store.deleteForever(setOf(cards.id))
    }

    @Test fun discardedScanRemovesItsPagesAndUnsharedOriginalsOnly(): Unit = runBlocking {
        val store = (compose.activity.application as ScanApplication).store
        val kept = store.create("Kept ${System.nanoTime()}"); val draft = store.create("Draft ${System.nanoTime()}")
        val keptPage = store.captured(kept.id, fixture("kept.jpg")); val draftPage = store.captured(draft.id, fixture("draft.jpg"))
        val second = store.split(draftPage, draftPage.edit(), .5f, true)
        assertTrue(store.source(draftPage).isFile && store.source(second).isFile)
        store.discard(draft.id)
        assertNull(store.dao.book(draft.id)); assertTrue(store.dao.snapshot(draft.id).isEmpty())
        assertFalse("Unsaved original is deleted", store.source(draftPage).exists())
        assertNotNull(store.dao.book(kept.id)); assertTrue("Saved documents keep their originals", store.source(keptPage).isFile)
        // Interrupted scans leave empty documents; only old ones not in use are removed.
        val empty = store.create("Empty ${System.nanoTime()}")
        store.removeEmpty(emptySet()); assertNotNull("A just-created scan is still in progress", store.dao.book(empty.id))
        store.removeEmpty(setOf(empty.id), olderThanMs = -1); assertNotNull(store.dao.book(empty.id))
        store.removeEmpty(emptySet(), olderThanMs = -1); assertNull(store.dao.book(empty.id)); assertNotNull(store.dao.book(kept.id))
        // Recognized Hebrew keeps each printed line, without direction marks, padding or empty lines.
        assertEquals("סדר הקבלה\nתשנ\"ה", com.docscan.rebuild.imaging.OfflineOcr.tidy("\u200f סדר  הקבלה \n\n תשנ''ה\u200e\n"))
        // Permanent deletion works only from the trash.
        store.deleteForever(setOf(kept.id)); assertNotNull("A saved document outside the trash is never deleted", store.dao.book(kept.id))
        store.trash(setOf(kept.id)); store.deleteForever(setOf(kept.id))
        assertNull(store.dao.book(kept.id)); assertFalse(store.source(keptPage).exists())
    }

    @Test fun singleCaptureOpensEditorWithDurableOriginal() {
        val store = (compose.activity.application as ScanApplication).store
        compose.onNodeWithTag("new-scan").performClick()
        val shutter = hasContentDescription(compose.activity.getString(R.string.capture)) and isEnabled()
        compose.waitUntil(30000) { compose.onAllNodes(shutter).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag("capture-single").performClick()
        compose.waitUntil(30000) { compose.onAllNodes(shutter).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(shutter).performClick()
        compose.waitUntil(60000) { compose.onAllNodesWithTag("save-edit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter-strip").assertIsDisplayed()
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        val books = runBlocking { store.dao.library().first() }
        assertTrue(books.any { item -> runBlocking { store.dao.snapshot(item.book.id).any { store.source(it).length() > 0 } } })
        screenshot("single-capture-editor")
    }

    @Test fun sourceRecipeAndOrderSurviveDatabaseReopen(): Unit = runBlocking {
        @Suppress("DEPRECATION") val packageInfo = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(packageInfo.requestedPermissions.orEmpty().contains(android.Manifest.permission.INTERNET))
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val name = "test-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        var store = ScanStore(context, db); val book = store.create("Persistence")
        val input = fixture(); val digest = hash(input)
        val first = store.captured(book.id, input); val second = store.captured(book.id, input)
        val recipe = EditRecipe(turns = 1, treatment = Treatment.INK, brightness = 9f, outputAspect = .64f)
        store.save(first, recipe); store.move(book.id, second.id, -1)
        assertEquals(digest, hash(store.source(first)))
        db.close(); db = Room.databaseBuilder(context, ScanDatabase::class.java, name).build(); store = ScanStore(context, db)
        val restored = store.dao.snapshot(book.id)
        assertEquals(listOf(second.id, first.id), restored.map { it.id })
        assertEquals(recipe, restored.last().edit()); assertEquals(digest, hash(store.source(restored.last())))
        restored.forEach { store.source(it).delete() }; input.delete(); db.close(); context.deleteDatabase(name)
    }

    @Test fun realNativePipelineDetectsCropsAndKeepsFullResolution() {
        val file = fixture("processing.jpg")
        try {
            val detected = requireNotNull(ImagePipeline.detect(file))
            assertTrue(detected.valid())
            assertEquals(.1f, detected.points[0].x, .035f); assertEquals(.092f, detected.points[0].y, .035f)
            val original = ImagePipeline.render(file, EditRecipe())
            assertEquals(1800, original.width); assertEquals(2400, original.height); original.recycle()
            for (treatment in Treatment.entries) {
                android.util.Log.i("DocScanFullResolution", "Rendering ${treatment.name} after crop and rotation")
                val result = ImagePipeline.render(file, EditRecipe(frame = detected, turns = 1, treatment = treatment, brightness = -12f, contrast = .8f))
                assertTrue(result.width > 1800); assertTrue(result.height > 1300)
                assertEquals(255, Color.alpha(result.getPixel(result.width / 2, result.height / 2)))
                result.recycle()
            }
        } finally { file.delete() }
    }

    @Test fun pdfContainsAllPagesAndLeavesMastersUntouched(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val book = store.create("PDF test"); val input = fixture("pdf-source.jpg")
        val first = store.captured(book.id, input); val second = store.captured(book.id, input); val digest = hash(input)
        store.save(second, EditRecipe(turns = 1, treatment = Treatment.GRAY))
        val pdf = DocumentOutput(context, store).pdf(book.id)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
            assertEquals(2, renderer.pageCount)
            renderer.openPage(0).use { assertTrue(it.height > it.width) }
            renderer.openPage(1).use { assertTrue(it.width > it.height) }
        } }
        assertEquals(digest, hash(store.source(first))); assertEquals(digest, hash(store.source(second)))
        pdf.delete(); input.delete(); store.source(first).delete(); store.source(second).delete(); db.close()
    }

    @Test fun referenceRecipesRoundTripAndNeverFallBackToAnotherFilter() {
        ImagePipeline.ensureAvailable()
        val delay = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("researchDelayMs")?.toLongOrNull()?.coerceIn(0, 30000) ?: 0
        if (delay > 0) android.os.SystemClock.sleep(delay)
        // A shaded page with dark text and a red mark: every engine-style filter must render it, and no
        // two of them may silently produce the same pixels (a fallback to another filter).
        val input = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        Canvas(input).apply {
            drawColor(Color.rgb(222, 218, 205))
            drawRect(0f, 0f, 90f, 320f, Paint().apply { color = Color.rgb(130, 126, 118) })
            drawRect(30f, 60f, 210f, 70f, Paint().apply { color = Color.rgb(30, 30, 35) })
            drawCircle(170f, 220f, 26f, Paint().apply { color = Color.rgb(200, 40, 40) })
        }
        val before = IntArray(240 * 320).also { input.getPixels(it, 0, 240, 0, 0, 240, 320) }
        val outputs = mutableMapOf<Treatment, IntArray>()
        try {
            for (treatment in listOf(Treatment.PAPER) + Treatment.entries.filter { it.referenceEngineVariant }) {
                val recipe = EditRecipe(treatment = treatment, brightness = 6f, outputAspect = .75f)
                assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe)))
                val output = ImagePipeline.transform(input, EditRecipe(treatment = treatment))
                try {
                    assertEquals(240, output.width); assertEquals(320, output.height)
                    outputs[treatment] = IntArray(240 * 320).also { output.getPixels(it, 0, 240, 0, 0, 240, 320) }
                } finally { output.recycle() }
            }
            val treatments = outputs.keys.toList()
            for (i in treatments.indices) for (j in i + 1 until treatments.size)
                assertFalse("${treatments[i]} and ${treatments[j]} produced identical pixels", outputs[treatments[i]]!!.contentEquals(outputs[treatments[j]]!!))
            assertArrayEquals(before, IntArray(240 * 320).also { input.getPixels(it, 0, 240, 0, 0, 240, 320) })
        } finally { input.recycle() }
    }

    @Test fun originalFiltersRemoveShadowsKeepInkColourAndSeparateBoardStrokes() {
        ImagePipeline.ensureAvailable()
        Assume.assumeFalse("Measures DocScan's own filters, not the research engine", ImagePipeline.referenceEngineAvailable)
        fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        // Page photo: warm paper, a strong shadow over the left third, black text in and out of it, a red stamp.
        val page = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.rgb(226, 220, 204))
            drawRect(0f, 0f, 200f, 800f, Paint().apply { color = Color.rgb(118, 114, 104) })
            val ink = Paint().apply { color = Color.rgb(28, 28, 32) }
            for (row in 0 until 8) drawRect(40f, 120f + row * 70f, 560f, 132f + row * 70f, ink)
            drawCircle(450f, 700f, 40f, Paint().apply { color = Color.rgb(205, 45, 40) })
        }
        val before = IntArray(600 * 800).also { page.getPixels(it, 0, 600, 0, 0, 600, 800) }
        fun render(input: Bitmap, treatment: Treatment) = ImagePipeline.transform(input, EditRecipe(treatment = treatment))
        try {
            for (treatment in listOf(Treatment.PAPER, Treatment.NATIVE_MAGIC_COLOR, Treatment.NATIVE_PRO_LOCAL, Treatment.NATIVE_NO_SHADOW)) {
                val out = render(page, treatment)
                try {
                    val shaded = lum(out.getPixel(100, 90)); val lit = lum(out.getPixel(400, 90))
                    val textShaded = lum(out.getPixel(100, 126)); val textLit = lum(out.getPixel(400, 126))
                    assertTrue("$treatment left the shadow: $shaded vs $lit", abs(shaded - lit) <= 22)
                    assertTrue("$treatment lost text in the shadow: $textShaded vs $shaded", shaded - textShaded >= 90)
                    assertTrue("$treatment lost text: $textLit vs $lit", lit - textLit >= 120)
                    if (treatment == Treatment.NATIVE_NO_SHADOW) assertTrue("No Shadow should keep the paper tone", lit in 190..245)
                    else assertTrue("$treatment paper is not white: $lit", lit >= 238)
                    val stamp = out.getPixel(450, 700)
                    assertTrue("$treatment removed the red stamp", Color.red(stamp) - Color.green(stamp) >= 80)
                    assertEquals(600, out.width); assertEquals(800, out.height); assertEquals(255, Color.alpha(out.getPixel(10, 10)))
                } finally { out.recycle() }
            }
            render(page, Treatment.NATIVE_INVERT).let { out ->
                try {
                    assertTrue("Inverted paper should be dark", lum(out.getPixel(100, 90)) <= 40 && lum(out.getPixel(400, 90)) <= 40)
                    assertTrue("Inverted text should be light", lum(out.getPixel(400, 126)) >= 190)
                } finally { out.recycle() }
            }
            assertArrayEquals(before, IntArray(600 * 800).also { page.getPixels(it, 0, 600, 0, 0, 600, 800) })
        } finally { page.recycle() }

        // Whiteboard (dark marker on light, unevenly lit board) and blackboard (chalk on dark green).
        val whiteboard = Bitmap.createBitmap(600, 450, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.rgb(214, 216, 212)); drawRect(380f, 0f, 600f, 450f, Paint().apply { color = Color.rgb(170, 172, 168) })
                drawRect(60f, 200f, 540f, 210f, Paint().apply { color = Color.rgb(35, 50, 140) })
            }
        }
        val blackboard = Bitmap.createBitmap(600, 450, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.rgb(48, 66, 58)); drawRect(60f, 200f, 540f, 210f, Paint().apply { color = Color.rgb(222, 222, 214) })
            }
        }
        try {
            for (board in listOf(whiteboard, blackboard)) {
                render(board, Treatment.NATIVE_BOARD).let { out ->
                    try {
                        for (x in listOf(150, 480)) {
                            val surface = out.getPixel(x, 100)
                            assertTrue("Board surface should be green: ${Integer.toHexString(surface)}", abs(Color.red(surface) - 40) <= 12 && abs(Color.green(surface) - 74) <= 12 && abs(Color.blue(surface) - 70) <= 12)
                            assertTrue("Board stroke should be chalk", lum(out.getPixel(x, 205)) >= 190)
                        }
                    } finally { out.recycle() }
                }
                render(board, Treatment.NATIVE_BOARD_PAPER).let { out ->
                    try {
                        for (x in listOf(150, 480)) {
                            assertTrue("Board to paper should be white", lum(out.getPixel(x, 100)) >= 245)
                            assertTrue("Board to paper should keep a dark stroke", lum(out.getPixel(x, 205)) <= 110)
                        }
                    } finally { out.recycle() }
                }
            }
            render(whiteboard, Treatment.NATIVE_BOARD_PAPER).let { out ->
                try { val stroke = out.getPixel(150, 205); assertTrue("Marker colour should survive", Color.blue(stroke) - Color.red(stroke) >= 25) }
                finally { out.recycle() }
            }
            render(whiteboard, Treatment.NATIVE_INVERT_BOARD).let { out ->
                try {
                    for (x in listOf(150, 480)) {
                        assertTrue("Inverted board surface should be chalk-white", lum(out.getPixel(x, 100)) >= 225)
                        val stroke = out.getPixel(x, 205)
                        assertTrue("Inverted board stroke should be board green: ${Integer.toHexString(stroke)}", abs(Color.red(stroke) - 40) <= 12 && abs(Color.green(stroke) - 74) <= 12 && abs(Color.blue(stroke) - 70) <= 12)
                    }
                } finally { out.recycle() }
            }
        } finally { whiteboard.recycle(); blackboard.recycle() }
    }

    @Test fun lightenAndEcoKeepReadableStrokesResolutionAndOriginal(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val store = ScanStore(context, db); val book = store.create("Local filters")
        val input = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(input); canvas.drawColor(Color.rgb(155, 165, 145))
        val ink = Paint().apply { color = Color.rgb(25, 30, 20) }
        canvas.drawRect(32f, 96f, 288f, 110f, ink)
        val file = File(context.cacheDir, "local-filter-source.png")
        file.outputStream().use { input.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val page = store.captured(book.id, file); val digest = hash(store.source(page))
        val before = IntArray(320 * 480).also { input.getPixels(it, 0, 320, 0, 0, 320, 480) }
        try {
            for (treatment in listOf(Treatment.LIGHTEN, Treatment.ECO)) {
                val recipe = EditRecipe(treatment = treatment)
                store.save(page, recipe)
                assertEquals(recipe, store.dao.page(page.id)!!.edit())
                val output = ImagePipeline.render(store.source(page), recipe)
                try {
                    assertEquals(320, output.width); assertEquals(480, output.height)
                    val paper = output.getPixel(160, 60); val text = output.getPixel(160, 102)
                    assertEquals(255, Color.alpha(paper)); assertEquals(255, Color.alpha(text))
                    assertTrue("Text contrast lost", Color.red(paper) - Color.red(text) > 100)
                    if (treatment == Treatment.LIGHTEN) {
                        assertTrue(Color.red(paper) > 155)
                        assertTrue("Colour removed", Color.green(paper) > Color.red(paper))
                    } else {
                        assertEquals(Color.WHITE, paper)
                        assertEquals(Color.red(text), Color.green(text)); assertEquals(Color.red(text), Color.blue(text))
                        assertTrue("Eco ink should retain the stroke in grey", Color.red(text) in 1..100)
                    }
                } finally { output.recycle() }
                assertEquals(digest, hash(store.source(page)))
            }
            val after = IntArray(320 * 480).also { input.getPixels(it, 0, 320, 0, 0, 320, 480) }
            assertArrayEquals(before, after)
        } finally { input.recycle(); store.source(page).delete(); file.delete(); db.close() }
    }

    @Test fun nativeProPreservesPageDimensionsAndOriginalPixels() {
        Assume.assumeTrue("Requires the ARM64 Native profile", ImagePipeline.referenceEngineAvailable)
        val failures = mutableListOf<String>()
        for ((width, height) in listOf(1970 to 1440, 1440 to 1970, 321 to 479)) {
            val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(input); canvas.drawColor(Color.rgb(155, 165, 145))
            canvas.drawRect(width * .1f, height * .4f, width * .9f, height * .45f, Paint().apply { color = Color.BLACK })
            val before = IntArray(width * height).also { input.getPixels(it, 0, width, 0, 0, width, height) }
            try {
                val output = ImagePipeline.transform(input, EditRecipe(treatment = Treatment.NATIVE_PRO_LOCAL))
                try {
                    assertEquals(width, output.width); assertEquals(height, output.height)
                    assertEquals(255, Color.alpha(output.getPixel(width - 1, height - 1)))
                    val paper = output.getPixel(width / 2, height / 4)
                    val ink = output.getPixel(width / 2, (height * .42f).toInt())
                    val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                    File(directory, "pro-source-$width-$height.png").outputStream().use { input.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(directory, "pro-result-$width-$height.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val darkRows = (0 until height).filter { Color.red(output.getPixel(width / 2, it)) < 80 }
                    val detail = "$width x $height paper=${Color.red(paper)} ink=${Color.red(ink)} darkRows=${darkRows.take(8)}..${darkRows.takeLast(8)}"
                    android.util.Log.i("DocScanProRegression", detail)
                    if (Color.red(paper) - Color.red(ink) <= 80) failures.add(detail)
                } finally { output.recycle() }
                val after = IntArray(width * height).also { input.getPixels(it, 0, width, 0, 0, width, height) }
                assertArrayEquals(before, after)
            } finally { input.recycle() }
        }
        assertTrue("Pro lost the original stroke: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun referenceNativeSmokePreservesDimensionsAlphaAndSource() {
        Assume.assumeTrue("Requires the private ARM64 reference profile", ImagePipeline.referenceEngineAvailable)
        ImagePipeline.ensureAvailable()
        val delay = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("researchDelayMs")?.toLongOrNull()?.coerceIn(0, 30000) ?: 0
        if (delay > 0) android.os.SystemClock.sleep(delay)
        val input = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(input); canvas.drawColor(Color.rgb(155, 165, 145))
        val paint = Paint().apply { color = Color.rgb(25, 30, 20) }
        repeat(8) { canvas.drawRect(10f, 12f + it * 12, 85f, 15f + it * 12, paint) }
        val before = IntArray(96 * 128).also { input.getPixels(it, 0, 96, 0, 0, 96, 128) }
        try {
            for (treatment in listOf(Treatment.PAPER) + Treatment.entries.filter { it.referenceEngineVariant }) {
                val output = ImagePipeline.transform(input, EditRecipe(treatment = treatment))
                try {
                    assertEquals(96, output.width); assertEquals(128, output.height)
                    var changed = false
                    for (y in 0 until 128) for (x in 0 until 96) {
                        val color = output.getPixel(x, y)
                        assertEquals(255, Color.alpha(color))
                        if (color != before[y * 96 + x]) changed = true
                    }
                    assertTrue("No effect from $treatment", changed)
                } finally { output.recycle() }
            }
            val after = IntArray(96 * 128).also { input.getPixels(it, 0, 96, 0, 0, 96, 128) }
            assertArrayEquals(before, after)
        } finally { input.recycle() }
    }

    /** Research-only entry point: each invocation can run one filter in a fresh Android process. */
    @Test fun referenceNativeIsolatedMatrix() {
        Assume.assumeTrue("Requires the private ARM64 reference profile", ImagePipeline.referenceEngineAvailable)
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val caseId = args.getString("researchCaseId", "manual")
        require(caseId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val allowed = listOf(Treatment.PAPER) + Treatment.entries.filter { it.referenceEngineVariant }
        val selected = args.getString("researchTreatments")?.split(",")?.map { Treatment.valueOf(it) } ?: allowed
        require(selected.isNotEmpty() && selected.all { it in allowed })
        val width = (args.getString("researchWidth")?.toIntOrNull() ?: 96).also { require(it in setOf(96, 320, 1280)) }
        val height = if (width == 96) 128 else width * 3 / 2
        val boundary = args.getString("researchBoundary", "pipeline")
        require(boundary in setOf("pipeline", "direct"))
        if (boundary == "pipeline") ImagePipeline.ensureAvailable()
        val delay = args.getString("researchDelayMs")?.toLongOrNull()?.coerceIn(0, 30000) ?: 0
        if (delay > 0) android.os.SystemClock.sleep(delay)
        listOf("opencv", "ncnn", "MNN", "CVNN", "magicenhancer").forEach(System::loadLibrary)
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val fixtureKind = args.getString("researchFixture", "gradient")
        require(fixtureKind in setOf("flat", "gradient", "legacy", "board", "blank"))
        if (fixtureKind == "board") canvas.drawColor(Color.rgb(40, 65, 50))
        else if (fixtureKind == "blank") canvas.drawColor(Color.WHITE)
        else if (fixtureKind in setOf("flat", "legacy")) canvas.drawColor(Color.rgb(155, 165, 145)) else {
            val background = Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.rgb(130, 138, 118), Color.rgb(240, 248, 228), Shader.TileMode.CLAMP) }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        }
        val paint = Paint().apply { color = if (fixtureKind == "board") Color.rgb(240, 242, 230) else Color.rgb(25, 30, 20) }
        if (fixtureKind == "legacy") {
            require(width == 96)
            repeat(8) { canvas.drawRect(10f, 12f + it * 12, 85f, 15f + it * 12, paint) }
        } else if (fixtureKind != "blank") repeat(8) { canvas.drawRect(width * .1f, height * (.1f + it * .1f), width * .9f, height * (.12f + it * .1f), paint) }
        val before = IntArray(width * height).also { source.getPixels(it, 0, width, 0, 0, width, height) }
        val failures = mutableListOf<String>()
        try {
            for (treatment in selected) {
                val start = android.os.SystemClock.elapsedRealtime()
                android.util.Log.i("DocScanFilterProbe", org.json.JSONObject().put("case_id", caseId).put("boundary", boundary).put("event", "case_start").put("treatment", treatment.name).put("width", width).put("height", height).toString())
                val row = org.json.JSONObject().put("case_id", caseId).put("fixture", fixtureKind).put("boundary", boundary).put("event", "case_result").put("treatment", treatment.name).put("width", width).put("height", height)
                try {
                    if (args.getString("researchClassify", "true") == "true") row.put("shadow_class", com.intsig.nativelib.MagicEnhancer.classifyShadowTypeBitmap(source))
                    val forcedShadowType = args.getString("researchShadowType")?.toIntOrNull()?.also { require(it in 1..3) }
                    val expectedNativeStatus = args.getString("researchExpectedNativeStatus")?.toIntOrNull()
                    require(expectedNativeStatus == null || forcedShadowType != null)
                    val output = if (forcedShadowType != null && treatment == Treatment.NATIVE_NO_SHADOW) {
                        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
                        try {
                            val status = com.intsig.nativelib.MagicEnhancer.deshadowBitmap(copy, forcedShadowType)
                            row.put("native_status", status).put("forced_shadow_type", forcedShadowType)
                            if (expectedNativeStatus != null) assertEquals(expectedNativeStatus, status)
                            else check(status >= 0) { "Forced shadow processing failed: $status" }
                            copy
                        } catch (failure: Throwable) { copy.recycle(); throw failure }
                    } else if (boundary == "pipeline") ImagePipeline.transform(source, EditRecipe(treatment = treatment)) else {
                        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
                        try { com.docscan.rebuild.imaging.NativeCamScannerEnhancer.applyInPlace(copy, treatment); copy }
                        catch (failure: Throwable) { copy.recycle(); throw failure }
                    }
                    try {
                        assertEquals(width, output.width); assertEquals(height, output.height)
                        val actual = IntArray(width * height).also { output.getPixels(it, 0, width, 0, 0, width, height) }
                        if (expectedNativeStatus != null && expectedNativeStatus < 0) {
                            assertArrayEquals("Rejected native call changed its working bitmap", before, actual)
                            row.put("expected_rejection", true)
                        } else assertTrue("Filter had no effect", actual.indices.any { actual[it] != before[it] })
                        assertTrue("Output alpha changed", actual.all { Color.alpha(it) == 255 })
                        if (fixtureKind == "board" && treatment == Treatment.NATIVE_BOARD_PAPER) {
                            assertEquals("Board background should become white paper", Color.WHITE, output.getPixel(10, 10))
                            assertEquals("Chalk should become black ink", Color.BLACK, output.getPixel(width / 2, (height * .11f).toInt()))
                        }
                        val outputDir = File(context.getExternalFilesDir(null), "filter-probe").apply { mkdirs() }
                        val shadowSuffix = forcedShadowType?.toString() ?: "auto"
                        File(outputDir, "$caseId-${treatment.name}-$shadowSuffix.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        row.put("pixel_sha256", MessageDigest.getInstance("SHA-256").digest(java.nio.ByteBuffer.allocate(actual.size * 4).apply { actual.forEach { putInt(it) } }.array()).joinToString("") { "%02x".format(it.toInt() and 255) })
                        row.put("passed", true)
                    } finally { output.recycle() }
                } catch (failure: Throwable) {
                    row.put("passed", false).put("error_type", failure.javaClass.simpleName).put("error", failure.message)
                    failures.add(treatment.name + ": " + failure.message)
                } finally {
                    val after = IntArray(width * height).also { source.getPixels(it, 0, width, 0, 0, width, height) }
                    val preserved = before.contentEquals(after)
                    row.put("source_preserved", preserved).put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
                    if (!preserved) failures.add(treatment.name + ": source changed")
                    android.util.Log.i("DocScanFilterProbe", row.toString())
                }
            }
            assertTrue(failures.joinToString("; "), failures.isEmpty())
        } finally { source.recycle() }
    }

    @Test fun invalidCropCannotOverwriteSourceOrSavedRecipe(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val book = store.create("Invalid edit"); val input = fixture("invalid.jpg"); val page = store.captured(book.id, input)
        val invalid = EditRecipe(frame = Frame(listOf(Vertex(0f, 0f))))
        try { store.save(page, invalid); fail("Expected invalid crop rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(EditRecipe(), store.dao.page(page.id)!!.edit()); assertEquals(hash(input), hash(store.source(page)))
        store.source(page).delete(); input.delete(); db.close()
    }

    @Test fun detectsDarkAndEqualLuminanceColouredBookCovers() {
        fun checkCover(name: String, background: Int, cover: Int) {
            val bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(background)
            val path = Path().apply { moveTo(180f, 180f); lineTo(810f, 260f); lineTo(870f, 1180f); lineTo(130f, 1120f); close() }
            canvas.drawPath(path, Paint().apply { color = cover })
            val file = File(context.cacheDir, name)
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val frame = requireNotNull(ImagePipeline.detect(file)) { "Cover not detected: $name" }
                assertEquals(.18f, frame.points[0].x, .035f)
                assertEquals(.129f, frame.points[0].y, .035f)
                assertTrue(frame.area() > .35f)
            } finally { bitmap.recycle(); file.delete() }
        }
        checkCover("dark-cover.png", Color.rgb(235,235,230), Color.rgb(28,30,36))
        // Red and green have almost identical grayscale luminance here.
        checkCover("colour-cover.png", Color.rgb(0,102,0), Color.rgb(200,0,0))
    }

    @Test fun galleryAndPdfUseTheSameEditedGeometryAtFullResolution(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val book = store.create("Gallery regression ${System.nanoTime()}"); val input = fixture("gallery-source.jpg")
        val first = store.captured(book.id, input); val second = store.captured(book.id, input)
        val frame = Frame(listOf(Vertex(.1f,.1f),Vertex(.9f,.1f),Vertex(.9f,.9f),Vertex(.1f,.9f)))
        val recipe = EditRecipe(frame = frame, outputAspect = .64f)
        store.save(first, recipe); store.save(second, recipe.copy(turns = 1, treatment = Treatment.GRAY))
        val digest = hash(input); var uris = emptyList<Uri>(); var pdf: File? = null
        try {
            assertNull(RecipeCodec.decode("{}").outputAspect)
            val output = DocumentOutput(context, store); uris = output.gallery(book.id); pdf = output.pdf(book.id)
            assertEquals(2, uris.size)
            uris.forEachIndexed { index, uri ->
                val bitmap = requireNotNull(context.contentResolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it) }!!
                try {
                    val expected = CropGeometry.dimensions(frame, first.width, first.height, .64f).rotated(index)
                    assertEquals(expected.width, bitmap.width); assertEquals(expected.height, bitmap.height)
                    assertTrue(maxOf(bitmap.width, bitmap.height) > 2200)
                    context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use {
                        assertTrue(it.moveToFirst()); assertTrue(it.getString(0).endsWith(".jpg"))
                    }
                    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(index).use { page -> assertEquals(bitmap.width.toDouble()/bitmap.height, page.width.toDouble()/page.height, .002) }
                    } }
                } finally { bitmap.recycle() }
            }
            assertEquals(digest, hash(store.source(first))); assertEquals(digest, hash(store.source(second)))
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }; pdf?.delete()
            input.delete(); store.source(first).delete(); store.source(second).delete(); db.close()
        }
    }

    @Test fun documentOffersGalleryExportAndPreviewUsesEditedAspect() {
        val store = (compose.activity.application as ScanApplication).store
        val title = "Gallery UI ${System.nanoTime()}"
        val page = runBlocking {
            val book = store.create(title); val sheet = store.captured(book.id, fixture("gallery-ui.jpg"))
            store.save(sheet, EditRecipe(outputAspect = .64f)); sheet
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithTag("page-image-${page.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.page_number, 1), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val image = compose.onNodeWithTag("page-image-${page.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(.64, (image.right - image.left).value.toDouble()/(image.bottom - image.top).value, .01)
        compose.onNodeWithTag("export-menu").performClick()
        compose.onNodeWithTag("save-gallery").assertIsDisplayed()
        screenshot("gallery-export-menu")
    }

    @Test fun multipleDocumentsCanBeTrashedAndRestoredWithoutLosingSources(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val input = fixture("trash-source.jpg"); val digest = hash(input)
        val a = store.create("Trash A"); val b = store.create("Trash B")
        val p = store.captured(a.id, input); val q = store.captured(b.id, input)
        val recipe = EditRecipe(turns = 1, outputAspect = .64f); store.save(p, recipe)
        store.trash(setOf(a.id, b.id))
        assertTrue(store.dao.library().first().isEmpty())
        assertEquals(setOf(a.id, b.id), store.dao.library(true).first().map { it.book.id }.toSet())
        assertEquals(digest, hash(store.source(p))); assertEquals(digest, hash(store.source(q)))
        store.trash(setOf(a.id), restore = true)
        assertEquals(listOf(a.id), store.dao.library().first().map { it.book.id })
        assertEquals(recipe, store.dao.page(p.id)!!.edit())
        store.trash(setOf(b.id), restore = true)
        assertEquals(2, store.dao.library().first().size)
        input.delete(); store.source(p).delete(); store.source(q).delete(); db.close()
    }

    @Test fun existingVersionOneDatabaseMigratesWithoutChangingItsSourceOrRecipe(): Unit = runBlocking {
        val name = "migration-${System.nanoTime()}.db"; val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val recipe = RecipeCodec.encode(EditRecipe(treatment = Treatment.GRAY))
        val master = File(File(context.filesDir, "masters").apply { mkdirs() }, "migration-${System.nanoTime()}.source")
        val input = fixture("migration-input.jpg"); input.copyTo(master)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE notebooks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE sheets (id TEXT NOT NULL PRIMARY KEY, notebookId TEXT NOT NULL, position INTEGER NOT NULL, source TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, mime TEXT NOT NULL, recipe TEXT NOT NULL, FOREIGN KEY(notebookId) REFERENCES notebooks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_sheets_notebookId ON sheets(notebookId)")
            old.execSQL("INSERT INTO notebooks VALUES ('old-book','Old document',1)")
            old.execSQL("INSERT INTO sheets VALUES ('old-sheet','old-book',0,?,1800,2400,'image/jpeg',?)", arrayOf(master.name, recipe))
            old.version = 1
        }
        val db = Room.databaseBuilder(context, ScanDatabase::class.java, name).addMigrations(ScanDatabase.MIGRATION_1_2, ScanDatabase.MIGRATION_2_3).build()
        try {
            val store = ScanStore(context, db)
            assertNull(store.dao.book("old-book")!!.trashedAt)
            val sheet = store.dao.page("old-sheet")!!
            assertEquals(recipe, sheet.recipe); assertEquals(hash(input), hash(store.source(sheet)))
        } finally { db.close(); context.deleteDatabase(name); input.delete(); master.delete() }
    }

    @Test fun editorImageExportUsesTheUnsavedRecipeInsteadOfTheMaster(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build(); val store = ScanStore(context, db)
        val book = store.create("JPEG regression"); val input = fixture("jpeg-source.jpg"); val page = store.captured(book.id, input)
        val edit = EditRecipe(treatment = Treatment.GRAY, outputAspect = .64f, turns = 1)
        val output = DocumentOutput(context, store).image(page, edit)
        try {
            val bitmap = BitmapFactory.decodeFile(output.path)!!
            try {
                assertEquals(1.0/.64, bitmap.width.toDouble()/bitmap.height, .002)
                val pixel = bitmap.getPixel(bitmap.width/2, bitmap.height/2)
                assertTrue(kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 2)
                assertTrue(kotlin.math.abs(Color.green(pixel) - Color.blue(pixel)) <= 2)
            } finally { bitmap.recycle() }
            assertEquals(EditRecipe(), store.dao.page(page.id)!!.edit())
            assertEquals(hash(input), hash(store.source(page)))
        } finally { output.delete(); input.delete(); store.source(page).delete(); db.close() }
    }

    @Test fun librarySelectionMovesTwoDocumentsToTrashAndRestoresThem() {
        val store = (compose.activity.application as ScanApplication).store
        val a = runBlocking { store.create("Selection A ${System.nanoTime()}") }
        val b = runBlocking { store.create("Selection B ${System.nanoTime()}") }
        compose.waitUntil(10000) { compose.onAllNodesWithText(a.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.select_documents)).performClick()
        compose.onNodeWithTag("library-list").performScrollToIndex(0)
        compose.onNodeWithTag("select-${a.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("select-${b.id}").assertIsDisplayed().performClick()
        screenshot("selection")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.move_to_trash)).performClick()
        compose.waitUntil(10000) { runBlocking { store.dao.book(a.id)!!.trashedAt != null && store.dao.book(b.id)!!.trashedAt != null } }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.trash)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.trash)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.select_documents)).performClick()
        compose.onNodeWithTag("library-list").performScrollToIndex(0)
        compose.onNodeWithTag("select-${a.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("select-${b.id}").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.restore)).performClick()
        compose.waitUntil(10000) { runBlocking { store.dao.book(a.id)!!.trashedAt == null && store.dao.book(b.id)!!.trashedAt == null } }
    }

    @Test fun homeSearchAndNavigationKeepDocumentsAndControlsReachable() {
        val store = (compose.activity.application as ScanApplication).store
        val title = "Home search ${System.nanoTime()}"
        runBlocking { val book = store.create(title); store.captured(book.id, fixture("home.jpg")) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-shortcuts").assertIsDisplayed()
        compose.onNodeWithTag("home-navigation").assertIsDisplayed()
        val fab = compose.onNodeWithTag("new-scan").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("home-tab-home").fetchSemanticsNode().boundsInRoot
        assertTrue("Camera button stays above navigation", fab.bottom <= tabs.top)
        screenshot("home-recent")
        compose.onNodeWithTag("home-search").performTextInput("missing-${System.nanoTime()}")
        compose.onNodeWithText(compose.activity.getString(R.string.search_empty)).assertIsDisplayed()
        compose.onNodeWithTag("home-search").performTextClearance()
        compose.onNodeWithTag("home-tab-files").performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithTag("home-tab-tools").performClick()
        compose.onNodeWithTag("tools-sections").assertIsDisplayed()
        compose.onNodeWithTag("home-tab-me").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.local_profile)).assertIsDisplayed()
        compose.onNodeWithTag("home-tab-home").performClick()
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("document-pages").assertIsDisplayed()
    }

    @Test fun reviewNavigationCommitsEachRecipeWithoutChangingOriginals() {
        val store = (compose.activity.application as ScanApplication).store
        val title = "Review pages ${System.nanoTime()}"
        val input = fixture("review.jpg")
        val pages = runBlocking { val book = store.create(title); listOf(store.captured(book.id, input), store.captured(book.id, input)) }
        val hashes = pages.map { hash(store.source(it)) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.add_pages)).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-batch").fetchSemanticsNodes().isNotEmpty() }
        // Existing pages are sufficient: do not capture another emulated photo.
        compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("review-counter").fetchSemanticsNodes().isNotEmpty() }
        selectFilter(Treatment.GRAY)
        compose.onNodeWithTag("review-next").performClick()
        compose.waitUntil(10000) { runBlocking { store.dao.page(pages[0].id)?.edit()?.treatment == Treatment.GRAY } }
        compose.onNodeWithTag("review-counter").assertTextEquals(compose.activity.getString(R.string.page_counter, 2, 2))
        selectFilter(Treatment.INK)
        compose.onNodeWithTag("review-previous").performClick()
        compose.waitUntil(10000) { runBlocking { store.dao.page(pages[1].id)?.edit()?.treatment == Treatment.INK } }
        compose.onNodeWithTag("filter-strip").performScrollToNode(hasTestTag("filter-GRAY"))
        compose.onNodeWithTag("filter-GRAY").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("review-counter").fetchSemanticsNodes().isNotEmpty() }
        screenshot("review-pages")
        compose.onNodeWithTag("save-edit").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
        pages.forEachIndexed { index, page -> assertEquals(hashes[index], hash(store.source(page))) }
        input.delete()
    }

    @Test fun nativeReviewKeepsAllFourteenFiltersAndExportsAfterReopen() {
        Assume.assumeTrue("Requires the ARM64 Native profile", ImagePipeline.referenceEngineAvailable)
        val treatments = Treatment.entries.toList()
        assertEquals(14, treatments.size)
        val store = (compose.activity.application as ScanApplication).store
        val title = "Native 0.1.5 to 0.1.6 ${System.nanoTime()}"
        val input = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        Canvas(input).apply {
            drawColor(Color.rgb(155, 165, 145))
            val paint = Paint().apply { color = Color.rgb(25, 30, 20) }
            repeat(8) { drawRect(32f, 48f + it * 48f, 288f, 58f + it * 48f, paint) }
        }
        val file = File(context.cacheDir, "native-review.png")
        file.outputStream().use { input.compress(Bitmap.CompressFormat.PNG, 100, it) }
        input.recycle()
        val pages = runBlocking {
            val book = store.create(title)
            treatments.map { store.captured(book.id, file) }
        }
        val originals = pages.map { hash(store.source(it)) }
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.add_pages)).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-batch").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(compose.activity.getString(R.string.done)).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("filter-strip").fetchSemanticsNodes().isNotEmpty() }
            treatments.forEachIndexed { index, treatment ->
                selectFilter(treatment)
                compose.onNodeWithTag("filter-${treatment.name}").assertIsSelected()
                compose.onNodeWithTag("review-counter").assertTextEquals(compose.activity.getString(R.string.page_counter, index + 1, treatments.size))
                if (index < treatments.lastIndex) compose.onNodeWithTag("review-next").performClick()
                else compose.onNodeWithTag("save-edit").performClick()
                compose.waitUntil(10000) { runBlocking { store.dao.page(pages[index].id)?.edit()?.treatment == treatment } }
                if (index < treatments.lastIndex) compose.waitUntil(10000) {
                    compose.onAllNodes(hasTestTag("review-counter") and hasText(compose.activity.getString(R.string.page_counter, index + 2, treatments.size))).fetchSemanticsNodes().isNotEmpty()
                }
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
            runBlocking {
                val restored = store.dao.snapshot(pages.first().notebookId)
                assertEquals(treatments, restored.map { it.edit().treatment })
                val output = DocumentOutput(context, store)
                restored.forEachIndexed { index, page ->
                    val image = output.image(page, page.edit())
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(image.path, bounds)
                        assertEquals(320, bounds.outWidth); assertEquals(480, bounds.outHeight)
                        assertEquals(originals[index], hash(store.source(page)))
                    } finally { image.delete() }
                }
                val pdf = output.pdf(pages.first().notebookId)
                try {
                    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { assertEquals(14, it.pageCount) }
                    }
                } finally { pdf.delete() }
            }
            screenshot("native-fourteen-filter-document")
        } finally { file.delete() }
    }

    @Test fun editorHasInlineFiltersAndActionsAboveSystemNavigation() {
        val language = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("locale", "en")
        assertEquals(if (language == "he") android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR, compose.activity.resources.configuration.layoutDirection)
        val store = (compose.activity.application as ScanApplication).store
        val title = "UI fixture ${System.nanoTime()}"
        val page = runBlocking { val book = store.create(title); store.captured(book.id, fixture("ui.jpg")) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        screenshot("library")
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        compose.onNodeWithTag("filter-strip").assertIsDisplayed()
        compose.waitUntil(60000) {
            compose.onAllNodes(hasTestTag("rendered-page-image") and hasAnyAncestor(hasTestTag("page-preview"))).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("editor")
        compose.onNodeWithTag("editor-export").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.save_gallery)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.save_jpeg)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.source_export)).assertIsDisplayed()
        compose.waitForIdle()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("editor-export-sheet").fetchSemanticsNodes().isEmpty() }
        selectFilter(Treatment.GRAY)
        val root = compose.onNodeWithTag("page-editor").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("save-edit").fetchSemanticsNode().boundsInRoot
        var navBottom = 0
        compose.runOnUiThread { navBottom = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0 }
        val toolbar = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("Save remains visible", save.top >= 0 && save.bottom < root.bottom - navBottom)
        assertTrue("App toolbar reserves navigation inset", toolbar.bottom <= root.bottom - navBottom + 1)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("filter-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save-edit").performClick()
        compose.waitUntil(10000) { runBlocking { store.dao.page(page.id)?.edit()?.treatment == Treatment.GRAY } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty() }
        val notices = compose.onAllNodesWithText(compose.activity.getString(R.string.saved)).fetchSemanticsNodes()
        if (notices.isNotEmpty()) {
            val actions = compose.onNodeWithTag("document-actions").fetchSemanticsNode().boundsInRoot
            assertTrue("Feedback stays above document actions", notices.single().boundsInRoot.bottom <= actions.top)
        }
        screenshot("document")
    }
}


