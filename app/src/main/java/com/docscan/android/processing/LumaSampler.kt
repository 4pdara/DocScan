package com.docscan.android.processing

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

object LumaSampler {
    /** Respect Y-plane padding, pixel stride, crop origin and all sensor rotations. */
    fun sample(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int, rotation: Int, maxEdge: Int = 360): GrayImage {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
        require(rotation in listOf(0, 90, 180, 270))
        val scale = minOf(1f, maxEdge.toFloat() / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val outW = if (rotation % 180 == 0) w else h
        val outH = if (rotation % 180 == 0) h else w
        val result = IntArray(w * h)
        val base = buffer.position()
        for (y in 0 until h) for (x in 0 until w) {
            val sx = left + ((x + .5f) * width / w).toInt().coerceAtMost(width - 1)
            val sy = top + ((y + .5f) * height / h).toInt().coerceAtMost(height - 1)
            val value = buffer.get(base + sy * rowStride + sx * pixelStride).toInt() and 255
            val index = when (rotation) {
                90 -> x * h + (h - 1 - y)
                180 -> (h - 1 - y) * w + (w - 1 - x)
                270 -> (w - 1 - x) * h + y
                else -> y * w + x
            }
            result[index] = value
        }
        return GrayImage(outW, outH, result)
    }
}
