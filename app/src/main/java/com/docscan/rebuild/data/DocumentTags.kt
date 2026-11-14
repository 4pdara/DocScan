package com.docscan.rebuild.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Tags for organising documents ("קבלות", "בית ספר"), kept on this phone and in backups. */
class DocumentTags(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tags", Context.MODE_PRIVATE)

    fun all(): Map<String, List<String>> = runCatching {
        val o = JSONObject(prefs.getString("tags", "{}") ?: "{}")
        o.keys().asSequence().associateWith { id -> o.getJSONArray(id).let { a -> (0 until a.length()).map { a.getString(it) } } }
    }.getOrDefault(emptyMap())

    fun of(id: String): List<String> = all()[id].orEmpty()

    /** Every tag in use, alphabetically. */
    fun names(): List<String> = all().values.flatten().distinct().sorted()

    fun set(id: String, tags: List<String>) {
        val clean = tags.map { clean(it) }.filter { it.isNotEmpty() }.distinct().take(20)
        val map = all().toMutableMap().apply { if (clean.isEmpty()) remove(id) else put(id, clean) }
        prefs.edit().putString("tags", JSONObject().apply { map.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString()).apply()
        changes.value++
    }

    /** Drops tags of documents that no longer exist. */
    fun keepOnly(ids: Set<String>) { val map = all(); if (map.keys.any { it !in ids }) prefs.edit().putString("tags", JSONObject().apply { map.filterKeys { it in ids }.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString()).apply() }

    companion object {
        /** Bumped on every change, so screens showing tags refresh. */
        val changes = MutableStateFlow(0)
        fun clean(tag: String) = tag.trim().replace(Regex("\\s+"), " ").take(30)
    }
}
