package com.docscan.android.export

object ExportNames {
    fun pdf(title: String): String {
        val clean = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim().trimEnd('.').replace(Regex("(?i)\\.pdf$"), "").take(90).trim()
        return (clean.ifBlank { "DocScan" }) + ".pdf"
    }
}
