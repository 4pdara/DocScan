package com.docscan.android.export

object ExportNames {
    fun pdf(title: String): String {
        var clean = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim().trimEnd('.').replace(Regex("(?i)\\.pdf$"), "").take(90).trim()
        while (clean.toByteArray(Charsets.UTF_8).size > 180) clean = clean.dropLast(1)
        if (clean.lastOrNull()?.isHighSurrogate() == true) clean = clean.dropLast(1)
        return (clean.ifBlank { "DocScan" }) + ".pdf"
    }
}
