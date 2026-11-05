package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import com.docscan.rebuild.BuildConfig
import com.intsig.nativelib.MagicEnhancer

/** Experimental Bitmap boundary; no Mat or allocation crosses between OpenCV providers. */
internal object NativeCamScannerEnhancer {
    private val loaded by lazy {
        listOf("opencv", "ncnn", "MNN", "CVNN", "magicenhancer").forEach(System::loadLibrary)
        check(MagicEnhancer.GetVersion() == "v0.0.9-4ea7ef5e") { "Unexpected CamScanner research engine version" }
        true
    }

    @Synchronized
    fun applyInPlace(bitmap: Bitmap) {
        check(BuildConfig.CAMSCANNER_NATIVE) { "CamScanner research engine is not bundled" }
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "A mutable software ARGB bitmap is required" }
        check(loaded)
        // Session zero is valid. Thread count one and mode one are the measured path.
        val session = MagicEnhancer.initDeshadowModelNew(1)
        check(session >= 0) { "CamScanner model initialization failed: $session" }
        try {
            val status = MagicEnhancer.deshadowBitmapNew(bitmap, 1, session)
            check(status == 0) { "CamScanner image processing failed: $status" }
        } finally {
            MagicEnhancer.releaseDeshadowModelNew(session)
        }
    }
}
