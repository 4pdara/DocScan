package com.docscan.rebuild.data

import android.content.Context
import com.docscan.rebuild.export.PdfLayout

/** The page size, orientation and margins of exported PDFs, kept on this phone. Off (each page the shape of its scan) until changed. */
class PdfSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pdf", Context.MODE_PRIVATE)
    var layout: PdfLayout
        get() = PdfLayout(option("paper", PdfLayout.Paper.FIT), option("orientation", PdfLayout.Orientation.AUTO), option("margin", PdfLayout.Margin.NONE))
        set(v) { prefs.edit().putString("paper", v.paper.name).putString("orientation", v.orientation.name).putString("margin", v.margin.name).apply() }
    private inline fun <reified T : Enum<T>> option(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}
