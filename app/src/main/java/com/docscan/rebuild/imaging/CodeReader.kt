package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.client.result.ParsedResult
import com.google.zxing.client.result.ParsedResultType
import com.google.zxing.client.result.ResultParser
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import kotlin.math.max
import kotlin.math.roundToInt

/** A decoded QR code or barcode: its raw text, format and the kind of content (link, Wi-Fi, phone, ...). */
data class ScannedCode(val text: String, val format: BarcodeFormat, val kind: ParsedResultType, val parsed: ParsedResult) {
    /** Text shown to the user: the parsed form (a Wi-Fi network as name and password, a contact as its fields). */
    val display: String get() = parsed.displayResult.ifBlank { text }
}

/** QR codes and barcodes with ZXing (Apache-2.0), entirely on the device. */
object CodeReader {
    private val formats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.CODABAR)
    private fun hints(hard: Boolean) = mapOf(DecodeHintType.POSSIBLE_FORMATS to formats, DecodeHintType.CHARACTER_SET to "UTF-8")
        .let { if (hard) it + (DecodeHintType.TRY_HARDER to true) else it }

    private fun code(result: Result) = ResultParser.parseResult(result).let { ScannedCode(result.text, result.barcodeFormat, it.type, it) }

    /** One camera frame (the Y plane): fast, a single code. */
    fun frame(luma: ByteArray, rowStride: Int, width: Int, height: Int): ScannedCode? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return decodeOne(source, hard = false)
    }

    private fun decodeOne(source: LuminanceSource, hard: Boolean): ScannedCode? {
        val reader = MultiFormatReader().apply { setHints(hints(hard)) }
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)), BinaryBitmap(HybridBinarizer(source.invert())))) {
            try { return code(reader.decodeWithState(bitmap)) } catch (_: Exception) { } finally { reader.reset() }
        }
        return null
    }

    /** A photo or scanned page: every code found, trying harder and at a few sizes. */
    fun image(bitmap: Bitmap): List<ScannedCode> {
        val found = LinkedHashMap<String, ScannedCode>()
        for (edge in listOf(1600, 2600, 900)) {
            val scale = minOf(1.0, edge.toDouble() / max(bitmap.width, bitmap.height))
            val image = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
            try {
                val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
                val source = RGBLuminanceSource(image.width, image.height, pixels)
                for (binary in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                    try {
                        GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(binary, hints(true)).forEach { found.putIfAbsent(it.text, code(it)) }
                    } catch (_: Exception) { }
                }
                if (found.isEmpty()) decodeOne(source, hard = true)?.let { found.putIfAbsent(it.text, it) }
                // Barcodes printed sideways: ZXing reads 1D codes along rows only.
                if (found.isEmpty()) {
                    val turned = Bitmap.createBitmap(image, 0, 0, image.width, image.height, android.graphics.Matrix().apply { postRotate(90f) }, false)
                    try {
                        val rotated = IntArray(turned.width * turned.height).also { turned.getPixels(it, 0, turned.width, 0, 0, turned.width, turned.height) }
                        decodeOne(RGBLuminanceSource(turned.width, turned.height, rotated), hard = true)?.let { found.putIfAbsent(it.text, it) }
                    } finally { if (turned !== image) turned.recycle() }
                }
            } finally { if (image !== bitmap) image.recycle() }
            if (found.isNotEmpty()) break
        }
        return found.values.toList()
    }
}
