package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import com.docscan.rebuild.BuildConfig
import com.docscan.rebuild.model.Treatment
import com.intsig.nativelib.MagicEnhancer
import com.intsig.nativelib.ImageProcessor
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Experimental Bitmap boundary; no Mat or allocation crosses between OpenCV providers. */
internal object NativeCamScannerEnhancer {
    private lateinit var workingDirectory: File
    fun configureCache(cache: File) {
        workingDirectory = File(cache, "native-pro").apply { mkdirs() }
        workingDirectory.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
    }
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
        // The Bitmap JNI method copies an unchecked output allocation into its
        // input buffer. Use the file boundary instead: lossless private working
        // PNGs let us validate dimensions before touching the caller's bitmap.
        // Dewarp stays disabled: it must never change the user's saved crop.
        val input = File.createTempFile("input-", ".png", workingDirectory)
        val output = File.createTempFile("output-", ".png", workingDirectory)
        try {
            input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            checkStatus(MagicEnhancer.ImageSilverBulletFile(input.path, output.path, 1, 0, 1))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.path, bounds)
            check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height) {
                "Native Pro changed dimensions: ${bitmap.width}x${bitmap.height} -> ${bounds.outWidth}x${bounds.outHeight}"
            }
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
            val processed = checkNotNull(BitmapFactory.decodeFile(output.path, options)) { "Native Pro produced no image" }
            try {
                preserveDarkContent(bitmap, processed)
                Canvas(bitmap).drawBitmap(processed, 0f, 0f, null)
            }
            finally { processed.recycle() }
        } finally { input.delete(); output.delete() }
    }

    private fun preserveDarkContent(original: Bitmap, processed: Bitmap) {
        // Local shadow normalization can mistake broad black strokes for paper
        // shading. Keep dark source detail whenever the native result lightens
        // it. This deliberately favors retaining ink over removing dark marks.
        // Row buffers keep the safeguard independent of the page's pixel count.
        val source = IntArray(original.width)
        val result = IntArray(original.width)
        fun luminance(color: Int) = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8
        for (y in 0 until original.height) {
            original.getPixels(source, 0, original.width, 0, y, original.width, 1)
            processed.getPixels(result, 0, original.width, 0, y, original.width, 1)
            var changed = false
            for (x in source.indices) {
                val ink = luminance(source[x])
                if (ink < 80 && luminance(result[x]) > ink) {
                    result[x] = source[x]
                    changed = true
                }
            }
            if (changed) processed.setPixels(result, 0, original.width, 0, y, original.width, 1)
        }
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

