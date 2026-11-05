package com.docscan.rebuild.data

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.room.*
import com.docscan.rebuild.model.*
import kotlinx.coroutines.Dispatchers
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
data class Notebook(@PrimaryKey val id: String, val title: String, val created: Long)

@Entity(tableName = "sheets", foreignKeys = [ForeignKey(entity = Notebook::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("notebookId")])
data class Sheet(@PrimaryKey val id: String, val notebookId: String, val position: Int, val source: String, val width: Int, val height: Int, val mime: String, val recipe: String = "{}") {
    fun edit() = RecipeCodec.decode(recipe)
}
data class LibraryEntry(@Embedded val book: Notebook, val pageCount: Int, val cover: String?)

@Dao
interface ScanQueries {
    @Query("SELECT notebooks.*, COUNT(sheets.id) AS pageCount, (SELECT id FROM sheets s WHERE s.notebookId = notebooks.id ORDER BY position LIMIT 1) AS cover FROM notebooks LEFT JOIN sheets ON notebooks.id=sheets.notebookId GROUP BY notebooks.id ORDER BY created DESC")
    fun library(): Flow<List<LibraryEntry>>
    @Query("SELECT * FROM sheets WHERE notebookId=:id ORDER BY position") fun pages(id: String): Flow<List<Sheet>>
    @Query("SELECT * FROM sheets WHERE notebookId=:id ORDER BY position") suspend fun snapshot(id: String): List<Sheet>
    @Query("SELECT * FROM sheets WHERE id=:id") suspend fun page(id: String): Sheet?
    @Query("SELECT * FROM notebooks WHERE id=:id") suspend fun book(id: String): Notebook?
    @Insert suspend fun insert(book: Notebook)
    @Insert suspend fun insert(page: Sheet)
    @Query("UPDATE sheets SET recipe=:recipe WHERE id=:id") suspend fun recipe(id: String, recipe: String)
    @Query("UPDATE notebooks SET title=:title WHERE id=:id") suspend fun rename(id: String, title: String)
    @Query("UPDATE sheets SET position=:position WHERE id=:id") suspend fun position(id: String, position: Int)
}
@Database(entities = [Notebook::class, Sheet::class], version = 1, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() { abstract fun queries(): ScanQueries }

object RecipeCodec {
    fun encode(r: EditRecipe): String = JSONObject().put("turns", r.turns).put("filter", r.treatment.name)
        .put("brightness", r.brightness.toDouble()).put("contrast", r.contrast.toDouble())
        .put("frame", JSONArray(r.frame.points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) })).toString()
    fun decode(text: String): EditRecipe = runCatching {
        val o = JSONObject(text); val q = o.optJSONArray("frame")
        val frame = if (q != null && q.length() == 4) Frame((0..3).map { val p = q.getJSONArray(it); Vertex(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) }) else Frame.FULL
        EditRecipe(frame.takeIf { it.valid() } ?: Frame.FULL, o.optInt("turns").mod(4),
            runCatching { Treatment.valueOf(o.optString("filter", "ORIGINAL")) }.getOrDefault(Treatment.ORIGINAL),
            o.optDouble("brightness", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
            o.optDouble("contrast", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.5f, 1.8f) ?: 1f)
    }.getOrDefault(EditRecipe())
}

class ScanStore(val context: Context, val database: ScanDatabase) {
    private val lock = Mutex()
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
    suspend fun save(page: Sheet, edit: EditRecipe) = withContext(Dispatchers.IO) { require(edit.frame.valid()); dao.recipe(page.id, RecipeCodec.encode(edit)) }
    suspend fun move(bookId: String, id: String, direction: Int) = withContext(Dispatchers.IO) {
        lock.withLock { database.withTransaction {
            val pages = dao.snapshot(bookId); val i = pages.indexOfFirst { it.id == id }; val j = i + direction
            if (i >= 0 && j in pages.indices) { dao.position(pages[i].id, pages[j].position); dao.position(pages[j].id, pages[i].position) }
        } }
    }
}
