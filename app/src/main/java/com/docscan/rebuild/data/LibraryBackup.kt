package com.docscan.rebuild.data

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

/** Versioned portable archive. Restore validates everything before adding documents, never replaces a library. */
class LibraryBackup(private val store: ScanStore) {
    private fun digest(file: File): String = file.inputStream().use { input ->
        val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)}
        md.digest().joinToString(""){ "%02x".format(it.toInt() and 255) }
    }
    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        val (books,pages)=store.database.withTransaction { store.dao.allBooks() to store.dao.allPages() }
        require(books.size<=10000 && pages.size<=20000) { "Library exceeds backup entry limit" }
        require(books.all { it.title.length<=1000 } && pages.all { it.recipe.length<=65536 && (it.ocrText?.length?:0)<=2_000_000 }) { "Library metadata exceeds backup limits" }
        val sources=pages.distinctBy { it.source }.map { store.source(it) }
        require(sources.all { it.length()<=100L*1024*1024 } && sources.sumOf { it.length() }<=8L*1024*1024*1024) { "Library exceeds backup size limit" }
        val manifest=JSONObject().put("format","docscan-backup").put("version",1)
            .put("books",JSONArray(books.map { b -> JSONObject().put("id",b.id).put("title",b.title).put("created",b.created).put("trashedAt",b.trashedAt?:JSONObject.NULL) }))
            .put("pages",JSONArray(pages.map { p ->
                val file=store.source(p);check(file.isFile)
                JSONObject().put("id",p.id).put("book",p.notebookId).put("position",p.position).put("source",p.source)
                    .put("width",p.width).put("height",p.height).put("mime",p.mime).put("recipe",p.recipe)
                    .put("ocrText",p.ocrText?:JSONObject.NULL).put("ocrRecipe",p.ocrRecipe?:JSONObject.NULL)
                    .put("size",file.length()).put("sha256",digest(file))
            }))
        val metadata=manifest.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size<=16*1024*1024) { "Backup manifest is too large" }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(metadata);zip.closeEntry()
            pages.distinctBy { it.source }.forEach { page ->
                coroutineContext.ensureActive();zip.putNextEntry(ZipEntry("sources/"+page.source))
                store.source(page).inputStream().use { it.copyTo(zip) };zip.closeEntry()
            }
        }
    }
    suspend fun restore(input: InputStream): Int = withContext(Dispatchers.IO) {
        val stage=File(store.context.cacheDir,"restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val copied=mutableListOf<File>();var committed=false
        try {
            val entries=mutableSetOf<String>();var total=0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while(true) {
                    coroutineContext.ensureActive();val entry=zip.nextEntry?:break;val name=entry.name
                    require(!entry.isDirectory && (name=="manifest.json" || Regex("sources/[A-Za-z0-9_-]+\\.source").matches(name))) { "Unexpected backup entry" }
                    require(entries.add(name) && entries.size<=20001) { "Duplicate or excessive backup entries" }
                    val target=File(stage,if(name=="manifest.json")name else name.removePrefix("sources/"))
                    val limit=if(name=="manifest.json")16L*1024*1024 else 100L*1024*1024
                    var size=0L;FileOutputStream(target).use { out ->
                        val buffer=ByteArray(65536)
                        while(true){val n=zip.read(buffer);if(n<0)break;size+=n;if(name!="manifest.json")total+=n;require(size<=limit && total<=8L*1024*1024*1024){"Backup too large"};out.write(buffer,0,n)}
                        out.fd.sync()
                    };zip.closeEntry()
                }
            }
            val manifest=JSONObject(File(stage,"manifest.json").readText())
            require(manifest.getString("format")=="docscan-backup" && manifest.getInt("version")==1)
            val books=manifest.getJSONArray("books");val pages=manifest.getJSONArray("pages")
            require(books.length()<=10000 && pages.length()<=20000)
            val bookIds=mutableSetOf<String>();val pageIds=mutableSetOf<String>();val sources=mutableMapOf<String,Pair<Long,String>>()
            for(i in 0 until books.length()) { val b=books.getJSONObject(i);require(bookIds.add(b.getString("id")));require(b.getString("title").length<=1000) }
            for(i in 0 until pages.length()) {
                coroutineContext.ensureActive();val p=pages.getJSONObject(i);require(pageIds.add(p.getString("id")) && p.getString("book") in bookIds)
                val name=p.getString("source");require(Regex("[A-Za-z0-9_-]+\\.source").matches(name))
                val file=File(stage,name);require(file.isFile && file.length()==p.getLong("size") && digest(file)==p.getString("sha256")) { "Original checksum mismatch" }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.path,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0 && p.getInt("width")>0 && p.getInt("height")>0)
                val dimensions=setOf(bounds.outWidth to bounds.outHeight,bounds.outHeight to bounds.outWidth)
                require(p.getInt("width") to p.getInt("height") in dimensions)
                require(p.getString("recipe").length<=65536);JSONObject(p.getString("recipe"))
                val identity=file.length() to p.getString("sha256");require(sources.put(name,identity)?.let { it==identity }!=false)
                require(p.optString("ocrText").length<=2_000_000)
            }
            require(entries==sources.keys.map { "sources/$it" }.toSet()+"manifest.json")
            coroutineContext.ensureActive()
            withContext(NonCancellable) { store.lock.withLock {
                val destination=File(store.context.filesDir,"masters").apply { mkdirs() }
                val names=sources.keys.associateWith { "${UUID.randomUUID()}.source" }
                names.forEach { (old,new) -> val target=File(destination,new);check(!target.exists());check(File(stage,old).renameTo(target));copied.add(target) }
                val mapping=bookIds.associateWith { UUID.randomUUID().toString() }
                store.database.withTransaction {
                    for(i in 0 until books.length()) {
                        val b=books.getJSONObject(i)
                        store.dao.insert(Notebook(mapping.getValue(b.getString("id")),b.getString("title"),b.getLong("created"),if(b.isNull("trashedAt"))null else b.getLong("trashedAt")))
                    }
                    for(i in 0 until pages.length()) {
                        val p=pages.getJSONObject(i)
                        store.dao.insert(Sheet(UUID.randomUUID().toString(),mapping.getValue(p.getString("book")),p.getInt("position"),names.getValue(p.getString("source")),p.getInt("width"),p.getInt("height"),p.getString("mime"),p.getString("recipe"),if(p.isNull("ocrText"))null else p.getString("ocrText"),if(p.isNull("ocrRecipe"))null else p.getString("ocrRecipe")))
                    }
                }
                committed=true
            } }
            books.length()
        } finally { if(!committed)copied.forEach { it.delete() };stage.deleteRecursively() }
    }
}
