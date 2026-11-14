package com.docscan.rebuild.imaging

import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.model.EditRecipe
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Recognized words of a page and the size of the rendering their boxes refer to. */
data class PageText(val words: List<com.docscan.rebuild.export.OcrWord>, val width: Int, val height: Int)

/** Hebrew/English recognition stays on the device. Models are bundled; no network permission is needed. */
class OfflineOcr(private val store: ScanStore) {
    suspend fun recognize(sheet: Sheet, languages: String = "heb+eng"): String = read(sheet, languages) { _, text, _ -> text }

    /** Text and the box of every word, for tables (the Excel export). The page's text is stored as by [recognize]. */
    suspend fun words(sheet: Sheet, languages: String = "heb+eng"): List<com.docscan.rebuild.export.OcrWord> = read(sheet, languages) { engine, _, _ -> boxes(engine) }

    /**
     * The words of the page as it looks with [recipe] (the editor's unsaved edit), without the marks drawn over it,
     * with their boxes in the pixels of a [PageText.width] x [PageText.height] rendering. Nothing is stored.
     */
    suspend fun layout(sheet: Sheet, recipe: EditRecipe, languages: String = "heb+eng"): PageText =
        read(sheet, languages, recipe.withoutMarks(), 2400, keep = false) { engine, _, page -> PageText(boxes(engine), page.width, page.height) }

    private fun boxes(engine: TessBaseAPI): List<com.docscan.rebuild.export.OcrWord> {
        val words = ArrayList<com.docscan.rebuild.export.OcrWord>()
        val iterator = engine.resultIterator ?: return words
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.replace("\u200e", "")?.replace("\u200f", "")?.trim()
                val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!text.isNullOrEmpty() && box != null) words += com.docscan.rebuild.export.OcrWord(text, box.left, box.top, box.right, box.bottom)
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally { iterator.delete() }
        return words
    }

    private suspend fun <T> read(sheet: Sheet, languages: String, recipe: EditRecipe = sheet.edit().withoutMarks(), edge: Int? = null, keep: Boolean = true, result: (TessBaseAPI, String, android.graphics.Bitmap) -> T): T = withContext(Dispatchers.Default) {
        require(languages in listOf("heb+eng","heb","eng"))
        lock.withLock {
            val root=File(store.context.filesDir,"ocr");val data=File(root,"tessdata").apply { check(isDirectory || mkdirs()) }
            for((language,expected) in models) {
                val target=File(data,"$language.traineddata")
                fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){ "%02x".format(it.toInt() and 255) }
                if(!target.isFile || digest(target)!=expected) {
                    val temp=File(data,"$language.part")
                    try {
                        store.context.assets.open("tessdata/$language.traineddata").use { input -> FileOutputStream(temp).use { output -> input.copyTo(output);output.fd.sync() } }
                        check(digest(temp)==expected);check(temp.renameTo(target))
                    } finally { temp.delete() }
                }
            }
            coroutineContext.ensureActive()
            val bitmap=ImagePipeline.render(store.source(sheet),recipe,edge)
            val engine=TessBaseAPI()
            try {
                check(engine.init(root.path,languages,TessBaseAPI.OEM_LSTM_ONLY)) { "OCR initialization failed" }
                engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                // preserve_interword_spaces drops the space before many Hebrew words (measured on a printed title page).
                engine.setImage(bitmap)
                val text=tidy(engine.getUTF8Text().orEmpty())
                coroutineContext.ensureActive()
                if(keep) check(store.dao.recognized(sheet.id,text,sheet.recipe)==1) { "Page changed during OCR" }
                result(engine, text, bitmap)
            } finally { engine.recycle();bitmap.recycle() }
        }
    }
    companion object {
        /** One printed line per text line: no direction marks, padding or empty lines; Hebrew gershayim restored. */
        fun tidy(raw:String):String=raw.replace("\u200e","").replace("\u200f","").lines()
            .map { it.replace(Regex("[ \\t]+")," ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            .replace(Regex("(?<=\\p{InHebrew})''(?=\\p{InHebrew})"),"\"")
        private val lock=Mutex()
        private val models=mapOf("heb" to "11f9e43ab227f786352a50f75c94c2e9906f1baba86d93276da19da7ce0904db","eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    }
}
