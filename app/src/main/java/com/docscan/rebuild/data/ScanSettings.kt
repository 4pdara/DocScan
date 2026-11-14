package com.docscan.rebuild.data

import android.content.Context
import com.docscan.rebuild.model.Treatment

/**
 * The filter a newly photographed page starts with. Like a scanner app, new pages are enhanced (Magic Color:
 * white paper, crisp ink, colour kept) unless the user chooses otherwise; the original photo is always kept and
 * every page can go back to "Original" in the editor.
 */
class ScanSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scan", Context.MODE_PRIVATE)
    var treatment: Treatment
        get() = prefs.getString("treatment", null)?.let { name -> Treatment.values().firstOrNull { it.name == name } } ?: Treatment.NATIVE_MAGIC_COLOR
        set(v) { prefs.edit().putString("treatment", v.name).apply() }
}
