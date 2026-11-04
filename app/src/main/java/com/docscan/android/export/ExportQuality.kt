package com.docscan.android.export

import com.docscan.android.data.PageEntity
import com.docscan.android.data.adjustments
import com.docscan.android.data.render
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.OriginalResolution
import com.docscan.android.processing.PageFormat
import com.docscan.android.processing.ScanFilter
import java.io.File

enum class ExportQuality(val maxEdge: Int, val jpegQuality: Int) {
    ORIGINAL(0, 100), HIGH(3200, 95), COMPACT(1600, 85);
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: ORIGINAL
    }
}

internal fun PageEntity.renderForExport(source: File, quality: ExportQuality) =
    if (quality == ExportQuality.ORIGINAL) OriginalResolution.render(source, rotation, ScanFilter.valueOf(filter),
        CropQuad.decode(crop), filterStrength, adjustments(), PageFormat.fromKey(pageFormat))
    else render(source, quality.maxEdge)
