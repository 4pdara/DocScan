package com.docscan.android.processing

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePhotoDetectionTest {
    private val expected = CropQuad(CropPoint(.210f, .195f), CropPoint(.728f, .235f), CropPoint(.882f, .794f), CropPoint(.068f, .781f))
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/book-angle.jpg.b64")).use {
        val bytes = Base64.getMimeDecoder().decode(it.readBytes())
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    @Test fun coloredPrintedColumnsDoNotReplaceTheOuterPageBoundary() {
        for (ink in listOf(Color.BLUE, Color.RED, Color.GREEN)) {
            val bitmap = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(210,204,185))
                val canvas = Canvas(bitmap)
                canvas.drawRect(55f,50f,345f,450f,Paint().apply { color = Color.rgb(225,226,230) })
                for (x in 110..290 step 30) canvas.drawRect(x.toFloat(),120f,x+6f,400f,Paint().apply { color = ink })
                val found = requireNotNull(ImagePipeline.detect(bitmap))
                val outer = CropQuad(CropPoint(.1375f,.1f),CropPoint(.8625f,.1f),CropPoint(.8625f,.9f),CropPoint(.1375f,.9f))
                assertTrue("Ink must not become the page: $ink ${found.quad}",found.quad.maxDistance(outer)<.03f)
            } finally { bitmap.recycle() }
        }
    }
    @Test fun actualAngledBookOnLightBackgroundIsFoundWithoutEnablingWiderMode() {
        val image = fixture()
        try {
            for (mode in DetectionMode.entries) {
                val found = requireNotNull(ImagePipeline.detect(image, mode)) { "Missing phone photograph in $mode" }
                assertTrue("Wrong phone-photo corners in $mode: ${found.quad}", found.quad.maxDistance(expected) < .055f)
                assertTrue(found.confidence >= mode.liveConfidence)
            }
        } finally { image.recycle() }
    }
    @Test fun distantNeutralPageCanBeFoundInNormalModeWithColorEvidence() {
        val bitmap = Bitmap.createBitmap(400,500,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(210,204,185))
            Canvas(bitmap).drawRect(160f,170f,260f,310f,Paint().apply { color = Color.rgb(225,226,230) })
            val expected = CropQuad(CropPoint(.4f,.34f),CropPoint(.65f,.34f),CropPoint(.65f,.62f),CropPoint(.4f,.62f))
            val found = requireNotNull(ImagePipeline.detect(bitmap))
            assertTrue(found.quad.maxDistance(expected)<.025f)
        } finally { bitmap.recycle() }
    }
    @Test fun cameraYuvPlanesFindTheSameBookAndKeepUnmodifiedLumaForFingerprinting() {
        val image = fixture()
        try {
            val w = image.width; val h = image.height
            val pixels = IntArray(w*h); image.getPixels(pixels, 0, w, 0, 0, w, h)
            val y = ByteArray(w*h); val u = ByteArray((w+1)/2*((h+1)/2)); val v = ByteArray(u.size)
            for (row in 0 until h) for (col in 0 until w) {
                val rgb = pixels[row*w+col]; val r = rgb ushr 16 and 255; val g = rgb ushr 8 and 255; val b = rgb and 255
                y[row*w+col] = ((r*77+g*150+b*29) ushr 8).toByte()
                if (row%2==0 && col%2==0) {
                    val at = row/2*((w+1)/2)+col/2
                    u[at] = (((-43*r-85*g+128*b) shr 8)+128).coerceIn(0,255).toByte()
                    v[at] = (((128*r-107*g-21*b) shr 8)+128).coerceIn(0,255).toByte()
                }
            }
            val plain = LumaSampler.sample(ByteBuffer.wrap(y), w, 1, 0, 0, w, h, 0, 360)
            val camera = LumaSampler.sample(ByteBuffer.wrap(y), w, 1, 0, 0, w, h, 0, 360,
                LumaSampler.Chroma(ByteBuffer.wrap(u), (w+1)/2, 1), LumaSampler.Chroma(ByteBuffer.wrap(v), (w+1)/2, 1))
            assertArrayEquals(plain.pixels, camera.pixels)
            val found = requireNotNull(DocumentDetector.detect(camera))
            assertTrue("Camera and saved photo must agree: ${found.quad}", found.quad.maxDistance(expected) < .055f)
        } finally { image.recycle() }
    }
}
