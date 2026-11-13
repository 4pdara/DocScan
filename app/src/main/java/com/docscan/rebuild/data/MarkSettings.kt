package com.docscan.rebuild.data

import android.content.Context
import com.docscan.rebuild.model.*
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
    var watermarkStyle: MarkStyle
        get() = MarkStyle(option("markFont", MarkFont.BOLD), option("markColor", MarkColor.GREY), option("markStrength", MarkStrength.MEDIUM), option("markSize", MarkSize.MEDIUM))
        set(v) { prefs.edit().putString("markFont", v.font.name).putString("markColor", v.color.name).putString("markStrength", v.strength.name).putString("markSize", v.size.name).apply() }
    var footerOn: Boolean get() = prefs.getBoolean("footerOn", false); set(v) { prefs.edit().putBoolean("footerOn", v).apply() }
    var footerText: String get() = prefs.getString("footerText", "").orEmpty(); set(v) { prefs.edit().putString("footerText", v.take(60)).apply() }
    var footerPlace: StampPlace get() = option("footerPlace", StampPlace.BOTTOM_CENTER); set(v) { prefs.edit().putString("footerPlace", v.name).apply() }
    /** Colour of the footer line and the timestamp. */
    var noteColor: MarkColor get() = option("noteColor", MarkColor.RED); set(v) { prefs.edit().putString("noteColor", v.name).apply() }
    var stampOn: Boolean get() = prefs.getBoolean("stampOn", false); set(v) { prefs.edit().putBoolean("stampOn", v).apply() }
    var stampPlace: StampPlace
        get() = runCatching { StampPlace.valueOf(prefs.getString("stampPlace", null) ?: "BOTTOM_RIGHT") }.getOrDefault(StampPlace.BOTTOM_RIGHT)
        set(v) { prefs.edit().putString("stampPlace", v.name).apply() }

    /** The marks a newly scanned or imported page receives, or the recipe unchanged when both are off. */
    fun applyTo(recipe: EditRecipe, now: Date = Date()): EditRecipe {
        var result = recipe
        val text = watermarkText.trim()
        if (watermarkOn && text.isNotEmpty()) result = result.copy(watermark = text, watermarkPlace = watermarkPlace, watermarkStyle = watermarkStyle)
        val footer = footerText.trim()
        if (footerOn && footer.isNotEmpty()) result = result.copy(footer = footer, footerPlace = footerPlace, noteColor = noteColor)
        if (stampOn) result = result.copy(stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(now), stampPlace = stampPlace, noteColor = noteColor)
        return result
    }
    val active: Boolean get() = (watermarkOn && watermarkText.isNotBlank()) || (footerOn && footerText.isNotBlank()) || stampOn
    private inline fun <reified T : Enum<T>> option(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}
