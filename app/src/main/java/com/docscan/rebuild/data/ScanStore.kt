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

object RecipeCodec {
    fun encode(r: EditRecipe): String = JSONObject().put("turns", r.turns).put("filter", r.treatment.name)
        .put("brightness", r.brightness.toDouble()).put("contrast", r.contrast.toDouble())
        .put("outputAspect", r.outputAspect?.toDouble() ?: JSONObject.NULL)
        .put("curveTop", r.curvature.top.toDouble()).put("curveBottom", r.curvature.bottom.toDouble())
        .put("frame", JSONArray(r.frame.points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
        .apply {
            r.watermark?.let {
                put("watermark", it); put("watermarkPlace", r.watermarkPlace.name)
                put("markFont", r.watermarkStyle.font.name); put("markColor", r.watermarkStyle.color.name)
                put("markStrength", r.watermarkStyle.strength.name); put("markSize", r.watermarkStyle.size.name)
            }
            r.stamp?.let { put("stamp", it); put("stampPlace", r.stampPlace.name) }
            r.footer?.let { put("footer", it); put("footerPlace", r.footerPlace.name) }
            if (r.stamp != null || r.footer != null) put("noteColor", r.noteColor.name)
            if (r.signatures.isNotEmpty()) put("signatures", JSONArray(r.signatures.map { SignatureCodec.encode(it) }))
        }.toString()
    fun decode(text: String): EditRecipe = runCatching {
        val o = JSONObject(text); val q = o.optJSONArray("frame")
        val frame = if (q != null && q.length() == 4) Frame((0..3).map { val p = q.getJSONArray(it); Vertex(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) }) else Frame.FULL
        EditRecipe(frame.takeIf { it.valid() } ?: Frame.FULL, o.optInt("turns").mod(4),
            runCatching { Treatment.valueOf(o.optString("filter", "ORIGINAL")) }.getOrDefault(Treatment.ORIGINAL),
            o.optDouble("brightness", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
            o.optDouble("contrast", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.5f, 1.8f) ?: 1f,
            o.optDouble("outputAspect", Double.NaN).toFloat().takeIf { it.isFinite() && it in .1f..10f },
            BookCurve(o.optDouble("curveTop", 0.0).toFloat(), o.optDouble("curveBottom", 0.0).toFloat()).takeIf { it.valid() } ?: BookCurve(),
            o.optString("watermark").trim().take(80).ifBlank { null }, o.optString("stamp").trim().take(80).ifBlank { null },
            runCatching { MarkPlace.valueOf(o.optString("watermarkPlace", "DIAGONAL")) }.getOrDefault(MarkPlace.DIAGONAL),
            runCatching { StampPlace.valueOf(o.optString("stampPlace", "BOTTOM_RIGHT")) }.getOrDefault(StampPlace.BOTTOM_RIGHT),
            MarkStyle(option(o, "markFont", MarkFont.BOLD), option(o, "markColor", MarkColor.GREY), option(o, "markStrength", MarkStrength.MEDIUM), option(o, "markSize", MarkSize.MEDIUM)),
            o.optString("footer").trim().take(80).ifBlank { null }, option(o, "footerPlace", StampPlace.BOTTOM_CENTER), option(o, "noteColor", MarkColor.RED),
            o.optJSONArray("signatures")?.let { list -> (0 until minOf(list.length(), 20)).mapNotNull { SignatureCodec.decode(list.optJSONObject(it)) } }.orEmpty())
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
    suspend fun captured(id: String, file: File): Sheet = withContext(Dispatchers.IO) { file.inputStream().use { store(id, it) } }
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
            page
        } finally { temporary.delete() }
    }
    suspend fun save(page: Sheet, edit: EditRecipe) = withContext(Dispatchers.IO) {
        require(edit.frame.valid() && edit.curvature.valid() && edit.signatures.all { it.valid() })
        require(edit.outputAspect == null || edit.outputAspect.isFinite() && edit.outputAspect in .1f..10f)
        dao.recipe(page.id, RecipeCodec.encode(edit))
    }
    suspend fun move(bookId: String, id: String, direction: Int) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            val pages = dao.snapshot(bookId); val i = pages.indexOfFirst { it.id == id }; val j = i + direction
            if (i >= 0 && j in pages.indices) { dao.position(pages[i].id, pages[j].position); dao.position(pages[j].id, pages[i].position) }
        } }
    }
    /** Two recipes reference the same immutable full source; the split never recompresses it. */
    suspend fun split(page: Sheet, recipe: EditRecipe, position: Float, rightFirst: Boolean): Sheet = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            val current = requireNotNull(dao.page(page.id))
            val (left,right) = recipe.frame.split(position)
            val crops = if(rightFirst) listOf(right,left) else listOf(left,right)
            val base = recipe.copy(outputAspect=null,curvature=BookCurve())
            val pages = dao.snapshot(current.notebookId)
            pages.filter { it.position>current.position }.sortedByDescending { it.position }.forEach { dao.position(it.id,it.position+1) }
            dao.recipe(current.id,RecipeCodec.encode(base.copy(frame=crops[0])))
            val second = current.copy(id=UUID.randomUUID().toString(),position=current.position+1,recipe=RecipeCodec.encode(base.copy(frame=crops[1])),ocrText=null,ocrRecipe=null)
            dao.insert(second)
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
                }
                sides.map { it.source }.distinct().filter { dao.sourceUses(it) == 0 }.forEach { File(masters, it).delete() }
            }
            requireNotNull(dao.page(card.id))
        } finally { file.delete() }
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
    suspend fun trash(ids: Set<String>, restore: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction { dao.trash(ids.toList(), if (restore) null else System.currentTimeMillis()) } }
    }
}

