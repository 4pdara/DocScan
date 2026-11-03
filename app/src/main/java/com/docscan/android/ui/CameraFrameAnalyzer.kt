@file:androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)

package com.docscan.android.ui

import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.DocumentDetector
import com.docscan.android.processing.LumaSampler
import com.docscan.android.processing.PageFingerprint

data class CameraFrame(val quad: CropQuad?, val width: Int, val height: Int,
    val transform: OutputTransform, val timestamp: Long, val fingerprint: PageFingerprint?)

class CameraFrameAnalyzer(private val onFrame: (CameraFrame) -> Unit) : ImageAnalysis.Analyzer {
    private var lastAnalysis = -1L
    private val transforms = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
        isUsingRotationDegrees = true
    }

    override fun getDefaultTargetResolution() = Size(640, 480)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (lastAnalysis >= 0 && now - lastAnalysis < 100) return
            lastAnalysis = now
            val crop = image.cropRect
            val rotation = image.imageInfo.rotationDegrees
            val plane = image.planes[0]
            val gray = LumaSampler.sample(plane.buffer.duplicate(), plane.rowStride, plane.pixelStride,
                crop.left, crop.top, crop.width(), crop.height(), rotation)
            val detection = DocumentDetector.detect(gray)?.takeIf { it.confidence >= .15f }
            onFrame(CameraFrame(detection?.quad,
                if (rotation % 180 == 0) crop.width() else crop.height(),
                if (rotation % 180 == 0) crop.height() else crop.width(),
                transforms.getOutputTransform(image), now, detection?.let { PageFingerprint.from(gray, it.quad) }))
        } catch (_: Exception) {
            // A malformed camera frame must never block the next ImageProxy.
        } finally { image.close() }
    }
}
