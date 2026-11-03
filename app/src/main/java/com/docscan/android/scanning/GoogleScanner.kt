package com.docscan.android.scanning

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/** Public SDK boundary. No Drive-private intents, models, or decompiled code. */
object GoogleScanner {
    const val PAGE_LIMIT = 100

    fun options(): GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(PAGE_LIMIT)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        // DocScan builds its own PDF after import; asking Google for another PDF wastes work.
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .build()

    fun isAvailable(context: Context): Boolean = try {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: RuntimeException) { false }

    fun pages(intent: Intent?): List<Uri> {
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(intent)?.pages.orEmpty().map { it.imageUri }
        require(pages.size <= PAGE_LIMIT)
        require(pages.all { it.scheme == "content" || it.scheme == "file" })
        return pages
    }
}
