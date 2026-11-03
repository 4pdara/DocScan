package com.docscan.android.data

import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ImageAdjustments
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.PageFormat
import com.docscan.android.processing.ScanFilter
import java.io.File

fun PageEntity.adjustments() = ImageAdjustments(brightness, contrast, saturation, sharpness)

/** A single rendering contract for previews, filter thumbnails, JPEG and PDF. */
fun PageEntity.render(source: File, maxEdge: Int) = ImagePipeline.render(source, rotation,
    ScanFilter.valueOf(filter), maxEdge, CropQuad.decode(crop), filterStrength,
    adjustments(), PageFormat.fromKey(pageFormat))

fun PageEntity.withoutEdits() = copy(rotation = 0, filter = ScanFilter.ORIGINAL.name, crop = null,
    brightness = 0, contrast = 0, saturation = 0, sharpness = 0, pageFormat = PageFormat.NATURAL.name)
