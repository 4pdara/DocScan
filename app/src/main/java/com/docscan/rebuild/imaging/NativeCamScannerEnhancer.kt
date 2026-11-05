package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import com.docscan.rebuild.BuildConfig
import com.docscan.rebuild.model.Treatment
import com.intsig.nativelib.MagicEnhancer
import com.intsig.nativelib.ImageProcessor

/** Experimental Bitmap boundary; no Mat or allocation crosses between OpenCV providers. */
internal object NativeCamScannerEnhancer {
    private val loaded by lazy {
        listOf("opencv", "ncnn", "MNN", "CVNN", "magicenhancer").forEach(System::loadLibrary)
        check(MagicEnhancer.GetVersion() == "v0.0.9-4ea7ef5e") { "Unexpected CamScanner research engine version" }
        true
    }
    private val imageProcessorLoaded by lazy {
        System.loadLibrary("imageprocessor")
        true
    }

    @Synchronized
    fun applyInPlace(bitmap: Bitmap, treatment: Treatment = Treatment.PAPER) {
        check(BuildConfig.CAMSCANNER_NATIVE) { "CamScanner research engine is not bundled" }
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "A mutable software ARGB bitmap is required" }
        check(loaded)
        when (treatment) {
            Treatment.PAPER -> deshadowWithSession(bitmap, 1, strictZero = true)
            Treatment.NATIVE_MAGIC_COLOR -> deshadowWithSession(bitmap, 3, strictZero = false)
            Treatment.NATIVE_NO_SHADOW -> checkStatus(MagicEnhancer.deshadowBitmap(bitmap, 1))
            Treatment.NATIVE_INVERT -> {
                check(imageProcessorLoaded)
                checkStatus(ImageProcessor.ReflectBinaryColorShiftBitmap(bitmap, 1))
            }
            Treatment.NATIVE_PRO_LOCAL -> checkStatus(MagicEnhancer.ImageSilverBulletBitmap(bitmap, 1, 1, 1))
            else -> error("This treatment does not use the reference engine")
        }
    }

    private fun checkStatus(status: Int) {
        // ScannerUtils accepts nonnegative results; positive values also describe successful output.
        check(status >= 0) { "CamScanner image processing failed: $status" }
    }

    private fun deshadowWithSession(bitmap: Bitmap, mode: Int, strictZero: Boolean) {
        // Session zero is valid. Thread count one and mode one are the measured path.
        val session = MagicEnhancer.initDeshadowModelNew(1)
        check(session >= 0) { "CamScanner model initialization failed: $session" }
        try {
            val status = MagicEnhancer.deshadowBitmapNew(bitmap, mode, session)
            if (strictZero) check(status == 0) { "CamScanner image processing failed: $status" }
            else checkStatus(status)
        } finally {
            MagicEnhancer.releaseDeshadowModelNew(session)
        }
    }
}
