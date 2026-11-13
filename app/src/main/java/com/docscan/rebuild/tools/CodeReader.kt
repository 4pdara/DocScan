package com.docscan.rebuild.tools
import android.graphics.Bitmap
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

object CodeReader {
    fun read(bitmap:Bitmap):String? {
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
        val reader=MultiFormatReader()
        return try {reader.decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.CHARACTER_SET to "UTF-8")).text}
        catch(_:NotFoundException){null} finally {reader.reset()}
    }
}
