package com.docscan.rebuild.data

import android.content.Context
import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.MarkPlace
import com.docscan.rebuild.model.StampPlace
import java.text.DateFormat
import java.util.Date

/**
 * Optional marks for new scans, chosen in Settings. Both are off until the user turns them on; existing pages
 * are never changed by these settings.
 */
class MarkSettings(context: Context) {
    private val prefs = context.getSharedPreferences("marks", Context.MODE_PRIVATE)
    var watermarkOn: Boolean get() = prefs.getBoolean("watermarkOn", false); set(v) { prefs.edit().putBoolean("watermarkOn", v).apply() }
    var watermarkText: String get() = prefs.getString("watermarkText", "").orEmpty(); set(v) { prefs.edit().putString("watermarkText", v.take(40)).apply() }
    var watermarkPlace: MarkPlace
        get() = runCatching { MarkPlace.valueOf(prefs.getString("watermarkPlace", null) ?: "DIAGONAL") }.getOrDefault(MarkPlace.DIAGONAL)
        set(v) { prefs.edit().putString("watermarkPlace", v.name).apply() }
    var stampOn: Boolean get() = prefs.getBoolean("stampOn", false); set(v) { prefs.edit().putBoolean("stampOn", v).apply() }
    var stampPlace: StampPlace
        get() = runCatching { StampPlace.valueOf(prefs.getString("stampPlace", null) ?: "BOTTOM_RIGHT") }.getOrDefault(StampPlace.BOTTOM_RIGHT)
        set(v) { prefs.edit().putString("stampPlace", v.name).apply() }

    /** The marks a newly scanned or imported page receives, or the recipe unchanged when both are off. */
    fun applyTo(recipe: EditRecipe, now: Date = Date()): EditRecipe {
        var result = recipe
        val text = watermarkText.trim()
        if (watermarkOn && text.isNotEmpty()) result = result.copy(watermark = text, watermarkPlace = watermarkPlace)
        if (stampOn) result = result.copy(stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(now), stampPlace = stampPlace)
        return result
    }
    val active: Boolean get() = (watermarkOn && watermarkText.isNotBlank()) || stampOn
}
