package com.docscan.rebuild.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Folders for documents, one inside another as deep as wanted, kept on this phone and in backups. A document is in
 * at most one folder; without one (or when its folder is gone) it is at the top level. Locking a folder is done
 * by [DocumentLock], with the same code as documents.
 */
class DocumentFolders(context: Context) {
    data class Folder(val id: String, val name: String, val parent: String?)
    private val prefs = context.applicationContext.getSharedPreferences("folders", Context.MODE_PRIVATE)

    fun folders(): List<Folder> = runCatching {
        val a = JSONArray(prefs.getString("folders", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Folder(o.getString("id"), o.getString("name"), o.optString("parent").ifEmpty { null }) } }
    }.getOrDefault(emptyList()).let { list -> val ids = list.map { it.id }.toSet(); list.map { if (it.parent != null && it.parent !in ids) it.copy(parent = null) else it } }

    /** Each document's folder (only folders that exist). */
    fun placement(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("placement", "{}") ?: "{}"); val ids = folders().map { it.id }.toSet()
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it in ids }
    }.getOrDefault(emptyMap())

    fun folderOf(document: String): String? = placement()[document]

    /** The folder and the folders above it, from the inside out. */
    fun chain(folder: String?): List<String> {
        val byId = folders().associateBy { it.id }; val out = ArrayList<String>(); var at = folder
        while (at != null && at !in out && byId.containsKey(at)) { out += at; at = byId.getValue(at).parent }
        return out
    }
    fun chainOf(document: String) = chain(folderOf(document))

    /** From the top level down to [folder]. */
    fun path(folder: String?): List<Folder> { val byId = folders().associateBy { it.id }; return chain(folder).reversed().mapNotNull { byId[it] } }

    fun create(name: String, parent: String?): Folder {
        val folder = Folder(UUID.randomUUID().toString(), clean(name).ifEmpty { "?" }, parent?.takeIf { p -> folders().any { it.id == p } })
        save(folders() + folder, placement()); return folder
    }

    fun rename(id: String, name: String) { val n = clean(name); if (n.isNotEmpty()) save(folders().map { if (it.id == id) it.copy(name = n) else it }, placement()) }

    /** Removes the folder; what was in it (documents and folders) moves up to where the folder was. */
    fun delete(id: String) {
        val all = folders(); val gone = all.firstOrNull { it.id == id } ?: return
        save(all.filter { it.id != id }.map { if (it.parent == id) it.copy(parent = gone.parent) else it },
            placement().mapValues { (_, f) -> if (f == id) gone.parent.orEmpty() else f }.filterValues { it.isNotEmpty() })
    }

    /** Puts [documents] in [folder] (null: the top level). */
    fun move(documents: Collection<String>, folder: String?) {
        val target = folder?.takeIf { f -> folders().any { it.id == f } }
        save(folders(), placement().toMutableMap().apply { documents.forEach { if (target == null) remove(it) else put(it, target) } })
    }

    /** Moves a folder into [parent] (null: the top level), never into itself or a folder inside it. */
    fun moveFolder(id: String, parent: String?) {
        if (parent != null && id in chain(parent)) return
        save(folders().map { if (it.id == id) it.copy(parent = parent) else it }, placement())
    }

    /** Drops the placement of documents that no longer exist. */
    fun keepOnly(ids: Set<String>) { val p = placement(); if (p.keys.any { it !in ids }) save(folders(), p.filterKeys { it in ids }) }

    private fun save(folders: List<Folder>, placement: Map<String, String>) {
        prefs.edit().putString("folders", JSONArray(folders.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("parent", f.parent ?: "") }).toString())
            .putString("placement", JSONObject().apply { placement.forEach { (k, v) -> put(k, v) } }.toString()).apply()
        changes.value++
    }

    companion object {
        /** Bumped on every change, so screens showing folders refresh. */
        val changes = MutableStateFlow(0)
        fun clean(name: String) = name.trim().replace(Regex("\\s+"), " ").take(60)
    }
}
