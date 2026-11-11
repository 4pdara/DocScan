package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import com.docscan.rebuild.BuildConfig
import com.docscan.rebuild.model.Treatment
import com.intsig.nativelib.MagicEnhancer
import com.intsig.nativelib.ImageProcessor
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

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
            Treatment.NATIVE_NO_SHADOW -> removeShadow(bitmap)
            Treatment.NATIVE_INVERT -> {
                check(imageProcessorLoaded)
                checkStatus(ImageProcessor.ReflectBinaryColorShiftBitmap(bitmap, 1))
            }
            Treatment.NATIVE_PRO_LOCAL -> silverBullet(bitmap)
            Treatment.NATIVE_BOARD -> board(bitmap, paper = false)
            Treatment.NATIVE_BOARD_PAPER -> board(bitmap, paper = true)
            Treatment.NATIVE_INVERT_BOARD -> {
                check(imageProcessorLoaded)
                checkStatus(ImageProcessor.ReflectBinaryColorShiftBitmap(bitmap, 1))
                board(bitmap, paper = false)
            }
            else -> error("This treatment does not use the reference engine")
        }
    }

    private fun silverBullet(bitmap: Bitmap) {
        // The JNI copy crashes for unaligned crop dimensions (1970 x 1440).
        // Keep every original pixel: extend the edges to the native working grid,
        // run the same filter, then copy only the original rectangle back.
        val width = (bitmap.width + 31) / 32 * 32
        val height = (bitmap.height + 31) / 32 * 32
        if (width == bitmap.width && height == bitmap.height) {
            checkStatus(MagicEnhancer.ImageSilverBulletBitmap(bitmap, 1, 1, 1))
            return
        }
        val padded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint().apply {
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                isFilterBitmap = false
            }
            Canvas(padded).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            checkStatus(MagicEnhancer.ImageSilverBulletBitmap(padded, 1, 1, 1))
            Canvas(bitmap).drawBitmap(padded, 0f, 0f, null)
        } finally { padded.recycle() }
    }

    private fun board(bitmap: Bitmap, paper: Boolean) {
        check(imageProcessorLoaded)
        if (paper) prepareBoardInk(bitmap)
        // PadLocalModelEnhance uses modes 2/3. These flat mattes are ours;
        // no reference texture, drawable or model is copied into the application.
        val background = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            background.eraseColor(if (paper) Color.WHITE else Color.rgb(40, 74, 70))
            checkStatus(ImageProcessor.ReflectBinaryColorShiftBitmapBG(bitmap, background, if (paper) 3 else 2))
        } finally { background.recycle() }
    }

    private fun prepareBoardInk(bitmap: Bitmap) {
        // Mode 3 is used for writing-pad images in the reference.
        // Our camera supplies colour photos: form a neutral chalk mask so mode 3
        // produces readable black ink on white instead of complementing board hues.
        ImagePipeline.ensureAvailable()
        val gray = Mat(); val mask = Mat()
        try {
            Utils.bitmapToMat(bitmap, gray)
            Imgproc.cvtColor(gray, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Utils.matToBitmap(mask, bitmap)
        } finally { gray.release(); mask.release() }
    }

    private fun removeShadow(bitmap: Bitmap) {
        val status = MagicEnhancer.deshadowBitmap(bitmap, 1)
        if (status == -1 && MagicEnhancer.classifyShadowTypeBitmap(bitmap) == 4) {
            // The verified JNI wrapper returns before writing pixels in this path.
            // Retry the same native shadow processor with its supported explicit
            // illumination path; never substitute PAPER or ignore a failed result.
            checkStatus(MagicEnhancer.deshadowBitmap(bitmap, 2))
        } else checkStatus(status)
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

