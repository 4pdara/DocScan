package com.docscan.rebuild.data

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.docscan.rebuild.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

@Entity(tableName = "notebooks")
data class Notebook(@PrimaryKey val id: String, val title: String, val created: Long, @ColumnInfo(defaultValue = "NULL") val trashedAt: Long? = null)

@Entity(tableName = "sheets", foreignKeys = [ForeignKey(entity = Notebook::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("notebookId")])
data class Sheet(@PrimaryKey val id: String, val notebookId: String, val position: Int, val source: String, val width: Int, val height: Int, val mime: String, val recipe: String = "{}", @ColumnInfo(defaultValue = "NULL") val ocrText: String? = null, @ColumnInfo(defaultValue = "NULL") val ocrRecipe: String? = null) {
    fun edit() = RecipeCodec.decode(recipe)
}
data class LibraryEntry(@Embedded val book: Notebook, val pageCount: Int, val cover: String?)

@Dao
interface ScanQueries {
    @Query("SELECT notebooks.*, COUNT(sheets.id) AS pageCount, (SELECT id FROM sheets s WHERE s.notebookId = notebooks.id ORDER BY position LIMIT 1) AS cover FROM notebooks LEFT JOIN sheets ON notebooks.id=sheets.notebookId WHERE (:trashed = 0 AND notebooks.trashedAt IS NULL) OR (:trashed = 1 AND notebooks.trashedAt IS NOT NULL) GROUP BY notebooks.id ORDER BY created DESC")
    fun library(trashed: Boolean = false): Flow<List<LibraryEntry>>
    @Query("SELECT * FROM sheets WHERE notebookId=:id ORDER BY position") fun pages(id: String): Flow<List<Sheet>>
    @Query("SELECT * FROM sheets WHERE notebookId=:id ORDER BY position") suspend fun snapshot(id: String): List<Sheet>
    @Query("SELECT * FROM sheets WHERE id=:id") suspend fun page(id: String): Sheet?
    @Query("SELECT * FROM notebooks WHERE id=:id") suspend fun book(id: String): Notebook?
    @Query("SELECT * FROM notebooks ORDER BY created, id") suspend fun allBooks(): List<Notebook>
    @Query("SELECT * FROM sheets ORDER BY notebookId, position, id") suspend fun allPages(): List<Sheet>
    @Query("UPDATE sheets SET ocrText=:text, ocrRecipe=:recipe WHERE id=:id AND sheets.recipe=:recipe") suspend fun recognized(id: String, text: String, recipe: String): Int
    @Insert suspend fun insert(book: Notebook)
    @Insert suspend fun insert(page: Sheet)
    @Query("UPDATE sheets SET ocrText=CASE WHEN recipe=:recipe THEN ocrText ELSE NULL END, ocrRecipe=CASE WHEN recipe=:recipe THEN ocrRecipe ELSE NULL END, recipe=:recipe WHERE id=:id") suspend fun recipe(id: String, recipe: String)
    @Query("UPDATE notebooks SET title=:title WHERE id=:id") suspend fun rename(id: String, title: String)
    @Query("UPDATE sheets SET position=:position WHERE id=:id") suspend fun position(id: String, position: Int)
    @Query("UPDATE notebooks SET trashedAt=:at WHERE id IN (:ids)") suspend fun trash(ids: List<String>, at: Long?)
    @Query("DELETE FROM sheets WHERE notebookId=:id") suspend fun deletePages(id: String)
    @Query("DELETE FROM sheets WHERE id=:id") suspend fun deletePage(id: String)
    @Query("DELETE FROM notebooks WHERE id=:id") suspend fun deleteBook(id: String)
    @Query("SELECT COUNT(*) FROM sheets WHERE source=:source") suspend fun sourceUses(source: String): Int
    @Query("UPDATE sheets SET notebookId=:book, position=:position WHERE id=:id") suspend fun relocate(id: String, book: String, position: Int)
    @Query("SELECT id FROM notebooks WHERE trashedAt IS NULL AND created < :before AND id NOT IN (SELECT notebookId FROM sheets)") suspend fun emptyBooks(before: Long): List<String>
}
@Database(entities = [Notebook::class, Sheet::class], version = 3, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun queries(): ScanQueries
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sheets ADD COLUMN ocrText TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sheets ADD COLUMN ocrRecipe TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE notebooks ADD COLUMN trashedAt INTEGER DEFAULT NULL") }
        }
    }
}

/** Signature strokes as compact JSON: each stroke a flat [x, y, x, y, ...] array rounded to 1/1000 of the box. */
object SignatureCodec {
    private fun r(v: Float) = Math.round(v.coerceIn(0f, 1f) * 1000) / 1000.0
    fun encode(s: SignatureMark): JSONObject = JSONObject().put("aspect", s.aspect.toDouble()).put("x", s.x.toDouble()).put("y", s.y.toDouble())
        .put("width", s.width.toDouble()).put("color", s.color.name)
        .put("strokes", JSONArray(s.strokes.map { stroke -> JSONArray(stroke.flatMap { listOf(r(it.x), r(it.y)) }) }))
    /** Strokes from the pad, in pixels, as points of their own bounding box (with a small margin), rounded as stored. */
    fun normalize(strokes: List<List<Pair<Float, Float>>>): Pair<List<List<Vertex>>, Float>? {
        val all = strokes.flatten(); if (all.isEmpty()) return null
        val minX = all.minOf { it.first }; val maxX = all.maxOf { it.first }; val minY = all.minOf { it.second }; val maxY = all.maxOf { it.second }
        val pad = maxOf(maxX - minX, maxY - minY) * .04f + 2f
        val w = maxX - minX + 2 * pad; val h = maxY - minY + 2 * pad
        val aspect = (w / h).coerceIn(.2f, 20f)
        return strokes.filter { it.isNotEmpty() }.map { stroke -> stroke.map { (x, y) -> Vertex(r((x - minX + pad) / w).toFloat(), r((y - minY + pad) / h).toFloat()) } } to aspect
    }
    fun decode(o: JSONObject?): SignatureMark? = runCatching {
        o ?: return null
        val list = o.getJSONArray("strokes")
        val strokes = (0 until minOf(list.length(), 400)).map { i ->
            val a = list.getJSONArray(i); (0 until minOf(a.length() / 2, 2000)).map { Vertex(a.getDouble(it * 2).toFloat(), a.getDouble(it * 2 + 1).toFloat()) }
        }
        val color = o.optString("color").let { name -> MarkColor.values().firstOrNull { it.name == name } } ?: MarkColor.BLACK
        SignatureMark(strokes, o.getDouble("aspect").toFloat(), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("width").toFloat(), color).takeIf { it.valid() }
    }.getOrNull()
}

/** A failure the user can act on: shown as its own message instead of the generic error. */
class UserMessage(val text: Int) : Exception()

object RecipeCodec {
    private fun r4(v: Float) = Math.round(v * 10000) / 10000.0
    private fun points(list: List<Vertex>) = JSONArray(list.flatMap { listOf(r4(it.x), r4(it.y)) })
    private fun points(a: JSONArray?): List<Vertex> = if (a == null) emptyList() else (0 until minOf(a.length() / 2, 5000)).map { Vertex(a.optDouble(it * 2).toFloat(), a.optDouble(it * 2 + 1).toFloat()) }
    fun encode(r: EditRecipe): String = JSONObject().put("turns", r.turns).put("filter", r.treatment.name)
        .put("brightness", r.brightness.toDouble()).put("contrast", r.contrast.toDouble())
        .apply { if (r.detail != 0f) put("detail", r4(r.detail)); if (r.edges.isNotEmpty()) put("edges", JSONArray(r.edges.map { Math.round(it * 1e6) / 1e6 })) }
        .put("outputAspect", r.outputAspect?.toDouble() ?: JSONObject.NULL)
        .put("curveTop", r.curvature.top.toDouble()).put("curveBottom", r.curvature.bottom.toDouble())
        .put("frame", JSONArray(r.frame.points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
        .apply {
            r.watermark?.let {
                put("watermark", it); put("watermarkPlace", r.watermarkPlace.name)
                put("markFont", r.watermarkStyle.font.name); put("markRgb", r.watermarkStyle.rgb)
                put("markAlpha", r.watermarkStyle.alpha); put("markScale", r.watermarkStyle.scale.toDouble())
                if (r.watermarkPlace == MarkPlace.FREE || r.watermarkPlace == MarkPlace.TILE) {
                    put("spotX", r4(r.watermarkSpot.x)); put("spotY", r4(r.watermarkSpot.y)); put("spotAngle", r.watermarkSpot.angle.toDouble())
                }
            }
            r.stamp?.let { put("stamp", it); put("stampPlace", r.stampPlace.name) }
            r.footer?.let { put("footer", it); put("footerPlace", r.footerPlace.name) }
            r.numbers?.let { put("numbers", it.name); put("numberPlace", r.numberPlace.name); r.numberText?.let { t -> put("numberText", t) } }
            if (r.stamp != null || r.footer != null || r.numbers != null) put("noteColor", r.noteColor.name)
            if (r.signatures.isNotEmpty()) put("signatures", JSONArray(r.signatures.map { SignatureCodec.encode(it) }))
            if (r.flatten.isNotEmpty()) put("flatten", JSONArray(r.flatten.map { it.toDouble() }))
            if (r.eraseColor) put("eraseColor", true)
            if (r.glare) put("glare", true)
            if (r.fingers) put("fingers", true)
            if (r.moire) put("moire", true)
            if (r.erase.isNotEmpty()) put("erase", JSONArray(r.erase.map { JSONObject().put("k", it.kind.name).put("w", r4(it.width)).put("p", points(it.points)) }))
            if (r.hides.isNotEmpty()) put("hides", JSONArray(r.hides.map { JSONObject().put("k", it.kind.name).put("p", points(listOf(it.a, it.b))) }))
            if (r.shapes.isNotEmpty()) put("shapes", JSONArray(r.shapes.map { JSONObject().put("k", it.kind.name).put("p", points(listOf(it.a, it.b))).put("c", it.rgb).put("w", r4(it.width)) }))
            if (r.ink.isNotEmpty()) put("ink", JSONArray(r.ink.map { JSONObject().put("w", r4(it.width)).put("c", it.rgb).put("a", it.alpha).put("p", points(it.points)) }))
            if (r.texts.isNotEmpty()) put("texts", JSONArray(r.texts.map { JSONObject().put("t", it.text).put("x", r4(it.x)).put("y", r4(it.y)).put("s", r4(it.size)).put("c", it.rgb).put("f", it.font.name) }))
        }.toString()
    fun decode(text: String): EditRecipe = runCatching {
        val o = JSONObject(text); val q = o.optJSONArray("frame")
        val frame = if (q != null && q.length() == 4) Frame((0..3).map { val p = q.getJSONArray(it); Vertex(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) }) else Frame.FULL
        // Watermark colour, opacity and size: stored as values; recipes from before 1.1.0 name a preset.
        val style = MarkStyle(option(o, "markFont", MarkFont.BOLD),
            if (o.has("markRgb")) o.optInt("markRgb") and 0xFFFFFF else option(o, "markColor", MarkColor.GREY).rgb,
            if (o.has("markAlpha")) o.optInt("markAlpha").coerceIn(10, 255) else option(o, "markStrength", MarkStrength.MEDIUM).alpha,
            if (o.has("markScale")) o.optDouble("markScale", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.2f, 4f) ?: 1f else option(o, "markSize", MarkSize.MEDIUM).scale)
        fun list(key: String, limit: Int) = o.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), limit)).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        EditRecipe(frame = frame.takeIf { it.valid() } ?: Frame.FULL, turns = o.optInt("turns").mod(4),
            treatment = runCatching { Treatment.valueOf(o.optString("filter", "ORIGINAL")) }.getOrDefault(Treatment.ORIGINAL),
            brightness = o.optDouble("brightness", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
            contrast = o.optDouble("contrast", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.5f, 1.8f) ?: 1f,
            detail = o.optDouble("detail", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            edges = o.optJSONArray("edges")?.let { a -> (0 until minOf(a.length(), 6)).map { a.optDouble(it, Double.NaN).toFloat() } }?.takeIf { PageEdgeCurves(it).valid() }.orEmpty(),
            outputAspect = o.optDouble("outputAspect", Double.NaN).toFloat().takeIf { it.isFinite() && it in .1f..10f },
            curvature = BookCurve(o.optDouble("curveTop", 0.0).toFloat(), o.optDouble("curveBottom", 0.0).toFloat()).takeIf { it.valid() } ?: BookCurve(),
            watermark = o.optString("watermark").trim().take(80).ifBlank { null }, stamp = o.optString("stamp").trim().take(80).ifBlank { null },
            watermarkPlace = option(o, "watermarkPlace", MarkPlace.DIAGONAL), stampPlace = option(o, "stampPlace", StampPlace.BOTTOM_RIGHT),
            watermarkStyle = style,
            footer = o.optString("footer").trim().take(80).ifBlank { null }, footerPlace = option(o, "footerPlace", StampPlace.BOTTOM_CENTER), noteColor = option(o, "noteColor", MarkColor.RED),
            signatures = list("signatures", 20).mapNotNull { SignatureCodec.decode(it) },
            flatten = o.optJSONArray("flatten")?.let { a -> (0 until a.length()).map { a.optDouble(it, Double.NaN).toFloat() } }?.takeIf { Flattening.valid(it) }.orEmpty(),
            eraseColor = o.optBoolean("eraseColor", false),
            glare = o.optBoolean("glare", false), fingers = o.optBoolean("fingers", false), moire = o.optBoolean("moire", false),
            erase = list("erase", 500).mapNotNull { m ->
                val kind = m.optString("k").let { k -> EraseKind.values().firstOrNull { it.name == k } } ?: return@mapNotNull null
                EraseMark(kind, points(m.optJSONArray("p")), m.optDouble("w", .03).toFloat()).takeIf { it.valid() }
            },
            hides = list("hides", 500).mapNotNull { m ->
                val kind = option(m, "k", HideKind.BLACK); val p = points(m.optJSONArray("p"))
                if (p.size == 2) HideMark(kind, p[0], p[1]).takeIf { it.valid() } else null
            },
            shapes = list("shapes", 500).mapNotNull { m ->
                val kind = m.optString("k").let { k -> ShapeKind.values().firstOrNull { it.name == k } } ?: return@mapNotNull null
                val p = points(m.optJSONArray("p"))
                if (p.size == 2) ShapeMark(kind, p[0], p[1], m.optInt("c", 0xD32F2F) and 0xFFFFFF, m.optDouble("w", .006).toFloat()).takeIf { it.valid() } else null
            },
            ink = list("ink", 500).mapNotNull { m -> InkStroke(points(m.optJSONArray("p")), m.optDouble("w", .01).toFloat(), m.optInt("c") and 0xFFFFFF, m.optInt("a", 255)).takeIf { it.valid() } },
            texts = list("texts", 100).mapNotNull { m ->
                TextMark(m.optString("t").take(500), m.optDouble("x", .5).toFloat(), m.optDouble("y", .5).toFloat(), m.optDouble("s", .04).toFloat(), m.optInt("c", 0x1A1A1A) and 0xFFFFFF, option(m, "f", MarkFont.PLAIN)).takeIf { it.valid() }
            },
            watermarkSpot = MarkSpot(o.optDouble("spotX", .5).toFloat(), o.optDouble("spotY", .5).toFloat(), o.optDouble("spotAngle", -45.0).toFloat()).takeIf { it.valid() } ?: MarkSpot(),
            numbers = o.optString("numbers").takeIf { it.isNotEmpty() }?.let { n -> NumberFormat.values().firstOrNull { it.name == n } },
            numberPlace = option(o, "numberPlace", StampPlace.BOTTOM_CENTER),
            numberText = o.optString("numberText").trim().take(40).ifBlank { null })
    }.getOrDefault(EditRecipe())
    private inline fun <reified T : Enum<T>> option(o: JSONObject, key: String, default: T): T =
        o.optString(key).takeIf { it.isNotEmpty() }?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}

class ScanStore(val context: Context, val database: ScanDatabase) {
    internal val lock = Mutex()
    val dao = database.queries()
    private val masters = File(context.filesDir, "masters").apply { mkdirs() }
    private val incoming = File(context.cacheDir, "incoming").apply { mkdirs() }
    fun source(page: Sheet): File = File(masters, page.source).also { require(it.canonicalFile.parentFile == masters.canonicalFile) }
    suspend fun create(title: String): Notebook = withContext(Dispatchers.IO) {
        Notebook(UUID.randomUUID().toString(), title, System.currentTimeMillis()).also { dao.insert(it) }
    }
    suspend fun import(id: String, uri: Uri): Sheet = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { stream -> requireNotNull(stream); store(id, stream) }
    }
    /**
     * Each page of a PDF becomes a page of the document: rendered at 200 dpi on white (long edge at most 3000
     * pixels) and kept as a JPEG original. The PDF itself is not modified or stored.
     */
    suspend fun importPdf(id: String, uri: Uri): List<Sheet> = withContext(Dispatchers.IO) {
        // A password PDF is refused before Android's PdfRenderer sees it: after one failed password check, that
        // renderer fails every later PDF in the same process (seen on Android 8 to 15 in the device tests).
        if (encryptedPdf(uri)) throw UserMessage(com.docscan.rebuild.R.string.pdf_import_protected)
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "Cannot open PDF" }
        descriptor.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount in 1..500) { "Unsupported PDF" }
                (0 until renderer.pageCount).map { index ->
                    coroutineContext.ensureActive()
                    val bitmap = renderer.openPage(index).use { page ->
                        val scale = minOf(200.0 / 72, 3000.0 / maxOf(page.width, page.height))
                        val bitmap = android.graphics.Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT); bitmap
                    }
                    val bytes = try { java.io.ByteArrayOutputStream().also { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray() } finally { bitmap.recycle() }
                    store(id, bytes.inputStream())
                }
            }
        }
    }
    /** Whether the PDF's trailer (in its last megabyte, uncompressed in every PDF) names an encryption dictionary. */
    fun encryptedPdf(uri: Uri): Boolean = runCatching {
        requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { input ->
                val length = fd.statSize; if (length > 1_048_576) input.channel.position(length - 1_048_576)
                input.readBytes().toString(Charsets.ISO_8859_1).contains("/Encrypt")
            }
        }
    }.getOrDefault(false)
    suspend fun captured(id: String, file: File): Sheet = withContext(Dispatchers.IO) { file.inputStream().use { store(id, it) } }
    /** A picture made in the app (a web page slice) as a new page. */
    suspend fun picture(id: String, bytes: ByteArray): Sheet = withContext(Dispatchers.IO) { store(id, bytes.inputStream()) }
    /** A text file's pages (TXT to PDF): each page a PNG original, with the text printed on it kept as its recognised text. */
    suspend fun textPages(id: String, text: String): List<Sheet> = withContext(Dispatchers.IO) {
        val rendered = ArrayList<Pair<ByteArray, String>>()
        com.docscan.rebuild.export.TextPages.render(text) { bitmap, t -> rendered += java.io.ByteArrayOutputStream().also { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() to t }
        rendered.map { (bytes, t) -> coroutineContext.ensureActive(); store(id, bytes.inputStream()).also { check(dao.recognized(it.id, t, it.recipe) == 1) } }
    }
    /** Commit bytes atomically before inserting metadata. No recompression, resizing or EXIF rewriting. */
    private suspend fun store(bookId: String, input: InputStream): Sheet = lock.withLock {
        requireNotNull(dao.book(bookId))
        val id = UUID.randomUUID().toString(); val temporary = File(incoming, "$id.part"); val final = File(masters, "$id.source")
        try {
            FileOutputStream(temporary).use { out ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 100L * 1024 * 1024) { "Image exceeds 100 MiB" }; out.write(buffer, 0, n) }
                out.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(com.docscan.rebuild.R.string.unsupported_image) }
            val orientation = runCatching { ExifInterface(temporary).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val swap = orientation in 5..8
            check(temporary.renameTo(final)) { "Unable to save original" }
            val position = (dao.snapshot(bookId).maxOfOrNull { it.position } ?: -1) + 1
            val page = Sheet(id, bookId, position, final.name, if (swap) bounds.outHeight else bounds.outWidth, if (swap) bounds.outWidth else bounds.outHeight, bounds.outMimeType ?: "image/jpeg")
            try { dao.insert(page) } catch (e: Exception) { final.delete(); throw e }
            renumber(bookId)
            requireNotNull(dao.page(id))
        } finally { temporary.delete() }
    }
    suspend fun save(page: Sheet, edit: EditRecipe) = withContext(Dispatchers.IO) {
        require(edit.valid())
        dao.recipe(page.id, RecipeCodec.encode(edit))
    }
    suspend fun move(bookId: String, id: String, direction: Int) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            val pages = dao.snapshot(bookId); val i = pages.indexOfFirst { it.id == id }; val j = i + direction
            if (i >= 0 && j in pages.indices) { dao.position(pages[i].id, pages[j].position); dao.position(pages[j].id, pages[i].position) }
            renumber(bookId)
        } }
    }
    /** Two recipes reference the same immutable full source; the split never recompresses it. */
    suspend fun split(page: Sheet, recipe: EditRecipe, position: Float, rightFirst: Boolean): Sheet = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            val current = requireNotNull(dao.page(page.id))
            val (left,right) = recipe.frame.split(position)
            val crops = if(rightFirst) listOf(right,left) else listOf(left,right)
            // Each half is its own page: curved edges and straightening found on the spread do not carry over.
            val base = recipe.copy(outputAspect=null,curvature=BookCurve(),edges=emptyList(),flatten=emptyList())
            val pages = dao.snapshot(current.notebookId)
            pages.filter { it.position>current.position }.sortedByDescending { it.position }.forEach { dao.position(it.id,it.position+1) }
            // Hidden areas stay hidden on the half they are on (mapped into its own coordinates).
            fun half(crop: Frame) = base.copy(frame=crop,hides=recipe.hidesForHalf(position,crop==left))
            dao.recipe(current.id,RecipeCodec.encode(half(crops[0])))
            val second = current.copy(id=UUID.randomUUID().toString(),position=current.position+1,recipe=RecipeCodec.encode(half(crops[1])),ocrText=null,ocrRecipe=null)
            dao.insert(second)
            renumber(current.notebookId)
            requireNotNull(dao.page(current.id))
        } }
    }

    /**
     * The ID card tool: both photographed sides become one A4 page at the card's real size. The new page takes
     * the place of the two side photos, which are removed with their originals (they were taken for this page
     * only, in an unsaved scan).
     */
    suspend fun idCard(bookId: String, front: Sheet, back: Sheet?): Sheet = withContext(Dispatchers.IO) {
        val sides = listOfNotNull(front, back)
        // Marks (watermark, footer, time) belong on the finished page, not on each card.
        val rendered = sides.map { com.docscan.rebuild.imaging.ImagePipeline.render(source(it), it.edit().copy(watermark = null, stamp = null, footer = null)) }
        val file = File(incoming, "idcard-${UUID.randomUUID()}.jpg")
        try {
            val page = com.docscan.rebuild.export.IdCardLayout.compose(rendered[0], rendered.getOrNull(1))
            try { FileOutputStream(file).use { out -> check(page.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)); out.fd.sync() } } finally { page.recycle() }
        } finally { rendered.forEach { it.recycle() } }
        try {
            val card = captured(bookId, file)
            lock.withLock {
                database.withTransaction {
                    sides.forEach { dao.deletePage(it.id) }
                    dao.position(card.id, sides.minOf { it.position })
                    renumber(bookId)
                }
                sides.map { it.source }.distinct().filter { dao.sourceUses(it) == 0 }.forEach { File(masters, it).delete() }
            }
            requireNotNull(dao.page(card.id))
        } finally { file.delete() }
    }

    /**
     * Page numbers follow the pages: when any page of the document carries numbers, every page gets the same
     * format and place with its current number. Runs inside the store lock after pages are added, moved or removed.
     */
    private suspend fun renumber(bookId: String) {
        val pages = dao.snapshot(bookId)
        val numbered = pages.map { it.edit() }.firstOrNull { it.numbers != null } ?: return
        val format = numbered.numbers ?: return
        pages.forEachIndexed { i, page ->
            val recipe = page.edit()
            val text = when (format) {
                NumberFormat.PLAIN -> "${i + 1}"
                NumberFormat.SLASH -> "${i + 1} / ${pages.size}"
                NumberFormat.WORDS -> context.getString(com.docscan.rebuild.R.string.page_number_words, i + 1, pages.size)
            }
            val next = recipe.copy(numbers = format, numberPlace = numbered.numberPlace, numberText = text,
                noteColor = if (recipe.footer != null || recipe.stamp != null) recipe.noteColor else numbered.noteColor)
            if (next != recipe) dao.recipe(page.id, RecipeCodec.encode(next))
        }
    }

    /** Numbers every page of the document in [format] at [place] (null removes the numbers). */
    suspend fun numberPages(bookId: String, format: NumberFormat?, place: StampPlace, colour: MarkColor) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            dao.snapshot(bookId).forEach { page ->
                val recipe = page.edit()
                val next = if (format == null) recipe.copy(numbers = null, numberText = null)
                    else recipe.copy(numbers = format, numberPlace = place, noteColor = colour)
                if (next != recipe) dao.recipe(page.id, RecipeCodec.encode(next))
            }
            renumber(bookId)
        } }
    }

    /** Removes one page; its original goes too unless another page still uses it. The pages after it move up. */
    suspend fun deletePage(page: Sheet) = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                dao.deletePage(page.id)
                dao.snapshot(page.notebookId).forEachIndexed { i, p -> if (p.position != i) dao.position(p.id, i) }
                renumber(page.notebookId)
            }
            if (dao.sourceUses(page.source) == 0) File(masters, page.source).delete()
        }
    }

    /**
     * Retake: the newly captured page [fresh] takes the place of [old], keeping the document-wide marks of the old
     * page (watermark, footer line, time, numbers). The old page and its original (unless shared) are removed.
     */
    suspend fun replace(old: Sheet, fresh: Sheet): Sheet = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                val before = old.edit(); val recipe = requireNotNull(dao.page(fresh.id)).edit()
                val kept = recipe.copy(watermark = before.watermark ?: recipe.watermark, watermarkPlace = if (before.watermark != null) before.watermarkPlace else recipe.watermarkPlace,
                    watermarkStyle = if (before.watermark != null) before.watermarkStyle else recipe.watermarkStyle, watermarkSpot = if (before.watermark != null) before.watermarkSpot else recipe.watermarkSpot,
                    footer = before.footer ?: recipe.footer, footerPlace = if (before.footer != null) before.footerPlace else recipe.footerPlace,
                    stamp = before.stamp ?: recipe.stamp, stampPlace = if (before.stamp != null) before.stampPlace else recipe.stampPlace,
                    noteColor = if (before.footer != null || before.stamp != null) before.noteColor else recipe.noteColor)
                dao.recipe(fresh.id, RecipeCodec.encode(kept))
                dao.deletePage(old.id)
                dao.position(fresh.id, old.position)
                dao.snapshot(old.notebookId).forEachIndexed { i, p -> if (p.position != i) dao.position(p.id, i) }
                renumber(old.notebookId)
            }
            if (dao.sourceUses(old.source) == 0) File(masters, old.source).delete()
            requireNotNull(dao.page(fresh.id))
        }
    }

    /**
     * A new document with the pages of [ids] in that order. The pages share the originals (nothing is copied or
     * recompressed); the merged documents stay as they are.
     */
    suspend fun merge(ids: List<String>, title: String): Notebook = withContext(Dispatchers.IO) {
        lock.withLock {
            database.withTransaction {
                val book = Notebook(UUID.randomUUID().toString(), title, System.currentTimeMillis()).also { dao.insert(it) }
                var position = 0
                for (id in ids) for (page in dao.snapshot(id)) dao.insert(page.copy(id = UUID.randomUUID().toString(), notebookId = book.id, position = position++))
                renumber(book.id)
                book
            }
        }
    }

    /**
     * Collage: the [pages] drawn [columns] x [rows] to a sheet, as many sheets as needed, added at the end of the
     * document as new pages. The pages themselves stay as they are.
     */
    suspend fun collage(bookId: String, pages: List<Sheet>, columns: Int, rows: Int, rightToLeft: Boolean): List<Sheet> = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty())
        val edge = com.docscan.rebuild.export.CollageLayout.edge(columns, rows)
        pages.chunked(columns * rows).map { group ->
            coroutineContext.ensureActive()
            val file = File(incoming, "collage-${UUID.randomUUID()}.jpg")
            try {
                val sheet = com.docscan.rebuild.export.CollageLayout.compose(group.size, columns, rows, rightToLeft) { i ->
                    com.docscan.rebuild.imaging.ImagePipeline.render(source(group[i]), group[i].edit(), edge)
                }
                try { FileOutputStream(file).use { out -> check(sheet.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)); out.fd.sync() } } finally { sheet.recycle() }
                captured(bookId, file)
            } finally { file.delete() }
        }
    }

    /**
     * ID photos from [photo] (cropped to [crop], in fractions of the photo): an A4 print sheet and a 10 x 15 cm
     * print at real size (300 dpi), then the single photo, added to the document as pages.
     */
    suspend fun idPhotos(bookId: String, photo: File, crop: android.graphics.RectF, size: com.docscan.rebuild.imaging.IdPhoto.Size,
        backdrop: com.docscan.rebuild.imaging.IdPhoto.Backdrop): List<Sheet> = withContext(Dispatchers.IO) {
        val source = com.docscan.rebuild.imaging.ImagePipeline.decode(photo, 3000)
        val single = try { com.docscan.rebuild.imaging.IdPhoto.photo(source, crop, size, backdrop) } finally { source.recycle() }
        try {
            suspend fun save(bitmap: android.graphics.Bitmap): Sheet {
                val file = File(incoming, "idphoto-${UUID.randomUUID()}.jpg")
                try {
                    FileOutputStream(file).use { out -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)); out.fd.sync() }
                    return captured(bookId, file)
                } finally { file.delete() }
            }
            val sheets = listOf(com.docscan.rebuild.imaging.IdPhoto.Sheet.A4, com.docscan.rebuild.imaging.IdPhoto.Sheet.PHOTO_10X15).map { kind ->
                val sheet = com.docscan.rebuild.imaging.IdPhoto.sheet(single, size, kind)
                try { save(sheet) } finally { sheet.recycle() }
            }
            sheets + save(single)
        } finally { single.recycle() }
    }

    /**
     * Permanently removes a scan the user left without saving: its pages, and each original no other page shares.
     * Only used for unsaved scan sessions and empty documents; saved documents go to the recoverable trash.
     */
    suspend fun discard(bookId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val pages = dao.snapshot(bookId)
            database.withTransaction { dao.deletePages(bookId); dao.deleteBook(bookId) }
            pages.map { it.source }.distinct().filter { dao.sourceUses(it) == 0 }.forEach { File(masters, it).delete() }
        }
    }

    /** Permanent deletion, only from the trash: the documents, their pages and every original no other page shares. */
    suspend fun deleteForever(ids: Set<String>) = withContext(Dispatchers.IO) {
        for (id in ids) if (dao.book(id)?.trashedAt != null) discard(id)
    }

    /** Documents left empty by an interrupted scan, older than [olderThanMs] and not currently open. */
    suspend fun removeEmpty(keep: Set<String>, olderThanMs: Long = 10 * 60 * 1000L) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            dao.emptyBooks(System.currentTimeMillis() - olderThanMs).filter { it !in keep }.forEach { dao.deleteBook(it) }
        } }
    }

    /** Recoverable removal: never deletes original files, recipes or page order. */
    /**
     * Long scan (1.3.4): [parts], each cropped and straightened as edited, joined top to bottom into one new page of
     * [bookId] with the first part's filter. The separate shots are not deleted: they move, unchanged, to a document
     * named [partsTitle] in the trash, from where they can be restored.
     */
    suspend fun longScan(bookId: String, parts: List<Sheet>, partsTitle: String): Sheet {
        require(parts.size >= 2)
        val bytes = withContext(Dispatchers.Default) {
            com.docscan.rebuild.imaging.ImagePipeline.ensureAvailable()
            val mats = ArrayList<org.opencv.core.Mat>()
            try {
                for (p in parts) {
                    coroutineContext.ensureActive()
                    val plain = p.edit().copy(treatment = Treatment.ORIGINAL, brightness = 0f, contrast = 1f, detail = 0f).withoutMarks().copy(hides = emptyList())
                    val bitmap = com.docscan.rebuild.imaging.ImagePipeline.render(source(p), plain, 2400)
                    try { mats += org.opencv.core.Mat().also { org.opencv.android.Utils.bitmapToMat(bitmap, it) } } finally { bitmap.recycle() }
                }
                val joined = com.docscan.rebuild.imaging.PageStitch.stitch(mats)
                try {
                    val bitmap = android.graphics.Bitmap.createBitmap(joined.cols(), joined.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                    try { org.opencv.android.Utils.matToBitmap(joined, bitmap); java.io.ByteArrayOutputStream().also { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)) }.toByteArray() }
                    finally { bitmap.recycle() }
                } finally { joined.release() }
            } finally { mats.forEach { it.release() } }
        }
        val page = withContext(Dispatchers.IO) { store(bookId, bytes.inputStream()) }
        save(page, EditRecipe(treatment = parts.first().edit().treatment))
        withContext(Dispatchers.IO) {
            lock.withLock { database.withTransaction {
                val now = System.currentTimeMillis(); val holder = Notebook(UUID.randomUUID().toString(), partsTitle, now, now)
                dao.insert(holder); parts.forEachIndexed { i, p -> dao.relocate(p.id, holder.id, i) }
            } }
        }
        return requireNotNull(dao.page(page.id))
    }
    suspend fun trash(ids: Set<String>, restore: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction { dao.trash(ids.toList(), if (restore) null else System.currentTimeMillis()) } }
    }
}

