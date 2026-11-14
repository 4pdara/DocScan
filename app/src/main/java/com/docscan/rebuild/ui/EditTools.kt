package com.docscan.rebuild.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.imaging.OfflineOcr
import com.docscan.rebuild.imaging.PageMarks
import com.docscan.rebuild.imaging.PageText
import com.docscan.rebuild.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A recognized word's box in page fractions. */
data class WordBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(v: Vertex, pad: Float = .006f) = v.x in left - pad..right + pad && v.y in top - pad..bottom + pad
    /** The area to erase for this word: its box with a margin for the soft edge of the ink. */
    fun erase(): EraseMark {
        val h = bottom - top; val padY = h * .2f; val padX = h * .12f
        return EraseMark(EraseKind.RECT, listOf(Vertex((left - padX).coerceIn(-.1f, 1.1f), (top - padY).coerceIn(-.1f, 1.1f)), Vertex((right + padX).coerceIn(-.1f, 1.1f), (bottom + padY).coerceIn(-.1f, 1.1f))))
    }
}

fun PageText.boxes(): List<WordBox> = if (width <= 0 || height <= 0) emptyList() else
    words.map { WordBox(it.text, it.left.toFloat() / width, it.top.toFloat() / height, it.right.toFloat() / width, it.bottom.toFloat() / height) }

/** Words in reading order, one line per printed line; Hebrew lines read from the right. */
fun readingOrder(words: List<WordBox>): String {
    if (words.isEmpty()) return ""
    val hebrew = words.sumOf { w -> w.text.count { it in '֐'..'׿' } }
    val letters = words.sumOf { w -> w.text.count { it.isLetter() } }
    val rtl = hebrew * 2 >= letters
    val lines = ArrayList<MutableList<WordBox>>()
    for (word in words.sortedBy { (it.top + it.bottom) / 2 }) {
        val line = lines.lastOrNull(); val centre = (word.top + word.bottom) / 2
        if (line != null && centre > line.minOf { it.top } && centre < line.maxOf { it.bottom }) line += word else lines += mutableListOf(word)
    }
    return lines.joinToString("\n") { line -> (if (rtl) line.sortedByDescending { it.right } else line.sortedBy { it.left }).joinToString(" ") { it.text } }
}

private fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("DocScan", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

/** One line or a few lines of text to type. */
@Composable
fun TextDialog(title: Int, initial: String, singleLine: Boolean = false, limit: Int = 500, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = { OutlinedTextField(value, { value = it.take(limit) }, singleLine = singleLine, maxLines = if (singleLine) 1 else 6, modifier = Modifier.fillMaxWidth().testTag("tool-text-field")) },
        confirmButton = { TextButton({ onConfirm(value) }, modifier = Modifier.testTag("tool-text-ok")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun UndoRow(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, tag: String, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onUndo, enabled = canUndo, modifier = Modifier.testTag("$tag-undo")) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.undo)) }
        IconButton(onRedo, enabled = canRedo, modifier = Modifier.testTag("$tag-redo")) { Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.redo)) }
        Spacer(Modifier.weight(1f))
        extra()
    }
}

private fun Path.through(points: List<Offset>) = apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }

/**
 * Smart erase: paint, frame, or tap what should go, and it is replaced by the paper around it. Each step can be
 * undone; "compare" shows the page as it was before this tool.
 */
@Composable
fun EraseTool(store: ScanStore, page: Sheet, recipe: EditRecipe, language: String, onDone: (EditRecipe) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var marks by remember { mutableStateOf(recipe.erase) }
    var undo by remember { mutableStateOf(listOf<List<EraseMark>>()) }
    var redo by remember { mutableStateOf(listOf<List<EraseMark>>()) }
    var mode by remember { mutableStateOf("brush") }
    var thickness by remember { mutableFloatStateOf(.03f) }
    var compare by remember { mutableStateOf(false) }
    var stroke by remember { mutableStateOf(listOf<Offset>()) }
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var words by remember { mutableStateOf<List<WordBox>?>(null) }
    var reading by remember { mutableStateOf(false) }
    fun push(next: List<EraseMark>) { if (next == marks) return; undo = undo + listOf(marks); redo = emptyList(); marks = next }
    fun readWords() {
        if (words != null || reading) return
        reading = true
        scope.launch {
            words = try { OfflineOcr(store).layout(page, recipe.copy(erase = marks), language).boxes() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Word layout failed", e); emptyList() }
            reading = false
        }
    }
    fun commit(area: Rect) {
        if (stroke.isNotEmpty()) { push(marks + EraseMark(EraseKind.STROKE, stroke.take(5000).map { area.toPage(it) }, thickness)); stroke = emptyList() }
        box?.let { (a, b) ->
            val p = area.toPage(a); val q = area.toPage(b)
            if (abs(p.x - q.x) > .01f && abs(p.y - q.y) > .005f) push(marks + EraseMark(EraseKind.RECT, listOf(Vertex(min(p.x, q.x), min(p.y, q.y)), Vertex(max(p.x, q.x), max(p.y, q.y)))))
            box = null
        }
    }
    val image = rememberPageImage(store, page, if (compare) recipe else recipe.copy(erase = marks))
    ToolFrame(R.string.smart_erase, "erase-tool", onCancel, { onDone(recipe.copy(erase = marks)) }, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            Canvas(Modifier.fillMaxSize().testTag("erase-canvas")
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { at -> when (mode) { "brush" -> stroke = listOf(at); "rect" -> box = at to at } },
                        onDragEnd = { commit(area) }, onDragCancel = { commit(area) }) { change, _ ->
                        when (mode) {
                            "brush" -> { change.consume(); if (stroke.isEmpty() || (change.position - stroke.last()).getDistance() > 2f) stroke = stroke + change.position }
                            "rect" -> { change.consume(); box = box?.let { it.first to change.position } }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val v = area.toPage(at)
                        when (mode) {
                            "brush" -> push(marks + EraseMark(EraseKind.STROKE, listOf(v), thickness))
                            "magic" -> push(marks + EraseMark(EraseKind.SHAPE, listOf(v), .12f))
                            "words" -> words?.firstOrNull { it.contains(v) }?.let { w -> push(marks + w.erase()); words = words?.minus(w) }
                        }
                    }
                }) {
                val width = thickness * min(area.width, area.height)
                val ink = ScanMint.copy(alpha = .5f)
                if (stroke.size == 1) drawCircle(ink, width / 2, stroke[0])
                else if (stroke.size > 1) drawPath(Path().through(stroke), ink, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                box?.let { (a, b) ->
                    val corner = Offset(min(a.x, b.x), min(a.y, b.y)); val extent = Size(abs(a.x - b.x), abs(a.y - b.y))
                    drawRect(ScanMint.copy(alpha = .3f), corner, extent); drawRect(ScanMint, corner, extent, style = Stroke(2.dp.toPx()))
                }
                if (mode == "words") words?.forEach { w ->
                    val tl = area.toScreen(Vertex(w.left, w.top)); val br = area.toScreen(Vertex(w.right, w.bottom))
                    drawRect(ScanMint, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }, controls = {
        UndoRow(undo.isNotEmpty(), redo.isNotEmpty(), { redo = listOf(marks) + redo; marks = undo.last(); undo = undo.dropLast(1) },
            { undo = undo + listOf(marks); marks = redo.first(); redo = redo.drop(1) }, "erase") {
            FilterChip(compare, { compare = !compare }, label = { Text(stringResource(R.string.compare_original)) },
                leadingIcon = { Icon(Icons.Outlined.Compare, null, Modifier.size(18.dp)) }, modifier = Modifier.testTag("erase-compare"))
        }
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(R.string.brush, Icons.Outlined.Brush, mode == "brush", "erase-brush", Modifier.weight(1f)) { mode = "brush" }
            ToolChoice(R.string.erase_rect, Icons.Outlined.CropSquare, mode == "rect", "erase-rect", Modifier.weight(1f)) { mode = "rect" }
            ToolChoice(R.string.erase_magic, Icons.Outlined.AutoFixHigh, mode == "magic", "erase-magic", Modifier.weight(1f)) { mode = "magic" }
            ToolChoice(R.string.erase_words, Icons.Outlined.TextFields, mode == "words", "erase-words", Modifier.weight(1f)) { mode = "words"; readWords() }
        }
        Text(stringResource(when (mode) { "rect" -> R.string.erase_hint_rect; "magic" -> R.string.erase_hint_magic; "words" -> R.string.erase_hint_words; else -> R.string.erase_hint_brush }),
            style = MaterialTheme.typography.bodySmall)
        if (mode == "brush") ToolSlider(R.string.mark_size, thickness, .008f.. .1f, "erase-size") { thickness = it }
        if (mode == "words") {
            if (reading) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.reading_text))
            } else if (words?.isEmpty() == true) Text(stringResource(R.string.no_words), style = MaterialTheme.typography.bodySmall)
            else TextButton({ words?.let { all -> push(marks + all.map { it.erase() }); words = emptyList() } }, enabled = !words.isNullOrEmpty(),
                modifier = Modifier.testTag("erase-all-text")) { Icon(Icons.Outlined.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.erase_all_text)) }
        }
    })
}

/** Ink colour (the average of the dark pixels) and whether the print looks bold, in the boxes of [words]. */
private fun inkOf(bitmap: Bitmap, words: List<WordBox>): Pair<Int, Boolean> {
    var r = 0L; var g = 0L; var b = 0L; var dark = 0; var total = 0
    for (w in words) {
        val x0 = (w.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1); val x1 = (w.right * bitmap.width).toInt().coerceIn(x0 + 1, bitmap.width)
        val y0 = (w.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1); val y1 = (w.bottom * bitmap.height).toInt().coerceIn(y0 + 1, bitmap.height)
        val pixels = IntArray((x1 - x0) * (y1 - y0)); bitmap.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
        val lum = IntArray(pixels.size) { luminance(pixels[it] and 0xFFFFFF) }
        val cut = ((lum.minOrNull() ?: 0) + (lum.maxOrNull() ?: 255)) / 2
        for (i in pixels.indices) {
            total++
            if (lum[i] < cut) { dark++; val p = pixels[i]; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255 }
        }
    }
    if (dark == 0) return 0x1A1A1A to false
    return (((r / dark).toInt() shl 16) or ((g / dark).toInt() shl 8) or (b / dark).toInt()) to (dark.toFloat() / total > .3f)
}

/**
 * Edit text: the words recognized on the page can be selected, copied, deleted (erased from the page) or replaced
 * with typed text in a similar size, colour and weight.
 */
@Composable
fun TextEditTool(store: ScanStore, page: Sheet, recipe: EditRecipe, language: String, onDone: (EditRecipe) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var working by remember { mutableStateOf(recipe) }
    var words by remember { mutableStateOf<List<WordBox>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<WordBox>()) }
    var editing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        words = try { OfflineOcr(store).layout(page, recipe, language).boxes() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Word layout failed", e); failed = true; emptyList() }
    }
    val image = rememberPageImage(store, page, working)
    fun remove(list: Collection<WordBox>) {
        working = working.copy(erase = working.erase + list.map { it.erase() })
        words = words?.filterNot { it in list }; chosen = emptySet()
    }
    fun replace(list: Collection<WordBox>, text: String) {
        if (list.isEmpty()) return
        val bitmap = image.bitmap
        val heights = list.map { it.bottom - it.top }.sorted(); val letter = heights[heights.size / 2]
        val (ink, bold) = bitmap?.let { inkOf(it, list.toList()) } ?: (0x1A1A1A to false)
        val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: (page.width.toFloat() / page.height)
        // A word box is about 0.72 of the font size; sizes are shares of the page's short side.
        val size = (letter / .72f / min(aspect, 1f)).coerceIn(.006f, .3f)
        val mark = text.trim().takeIf { it.isNotEmpty() }?.let {
            TextMark(it.take(500), (list.minOf { w -> w.left } + list.maxOf { w -> w.right }) / 2, (list.minOf { w -> w.top } + list.maxOf { w -> w.bottom }) / 2, size, ink, if (bold) MarkFont.CLASSIC else MarkFont.SERIF)
        }
        working = working.copy(erase = working.erase + list.map { it.erase() }, texts = working.texts + listOfNotNull(mark))
        words = words?.filterNot { it in list }; chosen = emptySet()
    }
    ToolFrame(R.string.edit_text, "text-tool", onCancel, { onDone(working) }, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            Canvas(Modifier.fillMaxSize().testTag("text-canvas").pointerInput(Unit) {
                detectTapGestures { at ->
                    val v = area.toPage(at)
                    words?.firstOrNull { it.contains(v) }?.let { w -> chosen = if (w in chosen) chosen - w else chosen + w }
                }
            }) {
                words?.forEach { w ->
                    val tl = area.toScreen(Vertex(w.left, w.top)); val br = area.toScreen(Vertex(w.right, w.bottom)); val extent = Size(br.x - tl.x, br.y - tl.y)
                    val on = w in chosen
                    if (on) drawRect(ScanMint.copy(alpha = .35f), tl, extent)
                    drawRect(ScanMint.copy(alpha = if (on) 1f else .55f), tl, extent, style = Stroke(1.5.dp.toPx()))
                }
            }
            if (words == null) Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(18.dp).testTag("text-reading"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(10.dp)); Text(stringResource(R.string.reading_text), color = Color.White)
            }
        }
    }, controls = {
        val all = words.orEmpty()
        val count = (if (chosen.isEmpty()) all else chosen.toList()).sumOf { it.text.length }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (failed) R.string.processing_error else if (words?.isEmpty() == true) R.string.no_words else R.string.edit_text_hint),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.characters, count), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("text-count"))
        }
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(R.string.edit, Icons.Outlined.Edit, false, "text-edit", Modifier.weight(1f), enabled = chosen.isNotEmpty()) { editing = readingOrder(chosen.toList()) }
            ToolChoice(R.string.copy, Icons.Outlined.ContentCopy, false, "text-copy", Modifier.weight(1f), enabled = chosen.isNotEmpty()) { copyText(context, readingOrder(chosen.toList())) }
            ToolChoice(R.string.delete, Icons.Outlined.Delete, false, "text-delete", Modifier.weight(1f), enabled = chosen.isNotEmpty()) { remove(chosen) }
            val everything = all.isNotEmpty() && chosen.size == all.size
            ToolChoice(if (everything) R.string.clear_selection else R.string.select_all, Icons.Outlined.SelectAll, everything, "text-select-all", Modifier.weight(1f), enabled = all.isNotEmpty()) {
                chosen = if (everything) emptySet() else all.toSet()
            }
        }
    })
    editing?.let { current ->
        TextDialog(R.string.replace_text, current, onDismiss = { editing = null }) { typed -> replace(chosen, typed); editing = null }
    }
}

/** Brush: draw with a pen or a highlighter in a colour and size; the eraser removes whole strokes it touches. */
@Composable
fun BrushTool(store: ScanStore, page: Sheet, recipe: EditRecipe, onDone: (EditRecipe) -> Unit, onCancel: () -> Unit) {
    var strokes by remember { mutableStateOf(recipe.ink) }
    var undo by remember { mutableStateOf(listOf<List<InkStroke>>()) }
    var redo by remember { mutableStateOf(listOf<List<InkStroke>>()) }
    var kind by remember { mutableStateOf("pen") }
    var colour by remember { mutableIntStateOf(0x1E88E5) }
    var level by remember { mutableFloatStateOf(6f) }
    var current by remember { mutableStateOf(listOf<Offset>()) }
    fun push(next: List<InkStroke>) { if (next == strokes) return; undo = undo + listOf(strokes); redo = emptyList(); strokes = next }
    fun width() = level * if (kind == "marker") .0025f else .0012f
    fun finish(area: Rect) {
        if (current.isEmpty()) return
        if (kind == "eraser") {
            val reach = max(14f, width() * min(area.width, area.height) * 2f)
            push(strokes.filterNot { s -> s.points.any { p -> val at = area.toScreen(p); current.any { (it - at).getDistance() < reach } } })
        } else push(strokes + InkStroke(current.take(5000).map { area.toPage(it) }, width(), colour, if (kind == "marker") 128 else 255))
        current = emptyList()
    }
    val image = rememberPageImage(store, page, recipe.copy(ink = emptyList()))
    ToolFrame(R.string.brush, "brush-tool", onCancel, { onDone(recipe.copy(ink = strokes)) }, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            Canvas(Modifier.fillMaxSize().testTag("brush-canvas")
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { current = listOf(it) }, onDragEnd = { finish(area) }, onDragCancel = { finish(area) }) { change, _ ->
                        change.consume()
                        if (current.isEmpty() || (change.position - current.last()).getDistance() > 2f) current = current + change.position
                    }
                }
                .pointerInput(Unit) { detectTapGestures { current = listOf(it); finish(area) } }) {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save(); canvas.translate(area.left, area.top); canvas.clipRect(0f, 0f, area.width, area.height)
                strokes.forEach { PageMarks.ink(canvas, area.width, area.height, it) }
                if (current.isNotEmpty() && kind != "eraser")
                    PageMarks.ink(canvas, area.width, area.height, InkStroke(current.map { area.toPage(it) }, width(), colour, if (kind == "marker") 128 else 255))
                canvas.restore()
                if (kind == "eraser" && current.isNotEmpty()) drawCircle(Color.White, max(14f, width() * min(area.width, area.height) * 2f), current.last(), style = Stroke(2.dp.toPx()))
            }
        }
    }, controls = {
        UndoRow(undo.isNotEmpty(), redo.isNotEmpty(), { redo = listOf(strokes) + redo; strokes = undo.last(); undo = undo.dropLast(1) },
            { undo = undo + listOf(strokes); strokes = redo.first(); redo = redo.drop(1) }, "brush")
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(R.string.pen, Icons.Outlined.Create, kind == "pen", "brush-pen", Modifier.weight(1f)) { kind = "pen" }
            ToolChoice(R.string.highlighter, Icons.Outlined.Highlight, kind == "marker", "brush-marker", Modifier.weight(1f)) { kind = "marker"; if (colour == 0x1E88E5 || colour == 0x000000) colour = 0xFFE600 }
            ToolChoice(R.string.eraser, Icons.Outlined.Clear, kind == "eraser", "brush-eraser", Modifier.weight(1f)) { kind = "eraser" }
        }
        if (kind != "eraser") PaletteRow(MarkPalette.ink + listOf(0x1E88E5).filterNot { it in MarkPalette.ink }, colour, { colour = it }, "ink")
        ToolSlider(R.string.mark_size, level, 1f..40f, "brush-size", "${level.roundToInt()}") { level = it }
    })
}

private val textColours = listOf(0x1A1A1A, 0x787878, 0xFFFFFF, 0xC8281E, 0x1E50C8, 0x1E8C3C, 0xF07840, 0x6A3FC8, 0x008F86, 0xFFE600)

/** Add text: tap the page to write there, drag text to move it, and change its font, colour and size. */
@Composable
fun TextMarkTool(store: ScanStore, page: Sheet, recipe: EditRecipe, onDone: (EditRecipe) -> Unit, onCancel: () -> Unit) {
    var texts by remember { mutableStateOf(recipe.texts) }
    var chosen by remember { mutableIntStateOf(-1) }
    var asking by remember { mutableStateOf<Vertex?>(null) }
    var editing by remember { mutableStateOf(false) }
    var lastFont by remember { mutableStateOf(MarkFont.PLAIN) }
    var lastColour by remember { mutableIntStateOf(0x1A1A1A) }
    var lastSize by remember { mutableFloatStateOf(.035f) }
    fun update(i: Int, change: (TextMark) -> TextMark) { texts = texts.mapIndexed { j, t -> if (j == i) change(t) else t } }
    val image = rememberPageImage(store, page, recipe.copy(texts = emptyList()))
    ToolFrame(R.string.add_text, "addtext-tool", onCancel, { onDone(recipe.copy(texts = texts)) }, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            fun hit(at: Offset): Int = texts.indices.lastOrNull { i ->
                RectF(PageMarks.textBox(area.width, area.height, texts[i])).apply { inset(-14f, -14f) }.contains(at.x - area.left, at.y - area.top)
            } ?: -1
            Canvas(Modifier.fillMaxSize().testTag("addtext-canvas")
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val i = hit(at)
                        if (i >= 0) chosen = i else if (chosen >= 0) chosen = -1 else asking = area.toPage(at)
                    }
                }
                .pointerInput(Unit) {
                    var moving = -1
                    detectDragGestures(onDragStart = { at -> moving = hit(at); if (moving >= 0) chosen = moving }, onDragEnd = { moving = -1 }, onDragCancel = { moving = -1 }) { change, delta ->
                        if (moving >= 0) {
                            change.consume()
                            update(moving) { it.copy(x = (it.x + delta.x / area.width).coerceIn(0f, 1f), y = (it.y + delta.y / area.height).coerceIn(0f, 1f)) }
                        }
                    }
                }) {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save(); canvas.translate(area.left, area.top); canvas.clipRect(0f, 0f, area.width, area.height)
                texts.forEach { PageMarks.text(canvas, area.width, area.height, it) }
                canvas.restore()
                texts.getOrNull(chosen)?.let { t ->
                    val b = PageMarks.textBox(area.width, area.height, t)
                    drawRect(ScanMint, Offset(area.left + b.left - 8f, area.top + b.top - 6f), Size(b.width() + 16f, b.height() + 12f), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }, controls = {
        val selected = texts.getOrNull(chosen)
        if (selected == null) {
            Text(stringResource(R.string.add_text_hint), style = MaterialTheme.typography.bodySmall)
            Button({ asking = Vertex(.5f, .5f) }, Modifier.testTag("addtext-new")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_text)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ editing = true }, Modifier.testTag("addtext-edit")) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.edit)) }
                TextButton({ texts = texts.filterIndexed { j, _ -> j != chosen }; chosen = -1 }, Modifier.testTag("addtext-delete")) {
                    Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.delete))
                }
                Spacer(Modifier.weight(1f))
                TextButton({ chosen = -1; asking = Vertex(.5f, .5f) }) { Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.add_text)) }
            }
            FontChips(selected.font, { f -> update(chosen) { it.copy(font = f) }; lastFont = f })
            PaletteRow(textColours, selected.rgb, { c -> update(chosen) { it.copy(rgb = c) }; lastColour = c }, "text-colour")
            ToolSlider(R.string.mark_size, selected.size, .012f.. .2f, "addtext-size") { s -> update(chosen) { it.copy(size = s) }; lastSize = s }
        }
    })
    asking?.let { at ->
        TextDialog(R.string.add_text, "", onDismiss = { asking = null }) { typed ->
            if (typed.isNotBlank()) { texts = texts + TextMark(typed.trim().take(500), at.x.coerceIn(0f, 1f), at.y.coerceIn(0f, 1f), lastSize, lastColour, lastFont); chosen = texts.lastIndex }
            asking = null
        }
    }
    if (editing) texts.getOrNull(chosen)?.let { t ->
        TextDialog(R.string.edit, t.text, onDismiss = { editing = false }) { typed ->
            if (typed.isBlank()) { texts = texts.filterIndexed { j, _ -> j != chosen }; chosen = -1 } else update(chosen) { it.copy(text = typed.trim().take(500)) }
            editing = false
        }
    }
}

/** A preset watermark as a free one in the same place, so it can be dragged and turned. */
private fun freeWatermark(recipe: EditRecipe, aspect: Float): Triple<MarkPlace, MarkStyle, MarkSpot> {
    val style = recipe.watermarkStyle
    val edge = style.copy(scale = (style.scale * .55f).coerceIn(.3f, 3f))
    return when (recipe.watermarkPlace) {
        MarkPlace.FREE, MarkPlace.TILE -> Triple(recipe.watermarkPlace, style, recipe.watermarkSpot)
        MarkPlace.DIAGONAL -> Triple(MarkPlace.FREE, style, MarkSpot(.5f, .5f, Math.toDegrees(-atan2(1.0, aspect.toDouble())).toFloat()))
        MarkPlace.CENTER -> Triple(MarkPlace.FREE, style, MarkSpot(.5f, .5f, 0f))
        MarkPlace.TOP -> Triple(MarkPlace.FREE, edge, MarkSpot(.5f, .07f, 0f))
        MarkPlace.BOTTOM -> Triple(MarkPlace.FREE, edge, MarkSpot(.5f, .93f, 0f))
        MarkPlace.SIDE -> Triple(MarkPlace.FREE, edge, MarkSpot(.07f, .5f, -90f))
    }
}

/**
 * Watermark: written on the page and moved by dragging, once or repeated over the whole page, in any font,
 * colour, strength, size and angle. [offerAllPages] adds a choice to put it on every page of the document.
 */
@Composable
fun WatermarkTool(store: ScanStore, page: Sheet, recipe: EditRecipe, offerAllPages: Boolean, onDone: (EditRecipe, Boolean) -> Unit, onCancel: () -> Unit) {
    val aspect = remember(page.id, recipe.frame, recipe.turns) { CropGeometry.dimensions(recipe.frame, page.width, page.height, recipe.outputAspect).rotated(recipe.turns).aspect }
    val start = remember { freeWatermark(recipe, aspect) }
    var text by remember { mutableStateOf(recipe.watermark.orEmpty()) }
    var place by remember { mutableStateOf(start.first) }
    var style by remember { mutableStateOf(start.second) }
    var spot by remember { mutableStateOf(start.third) }
    var allPages by remember { mutableStateOf(offerAllPages) }
    var asking by remember { mutableStateOf(recipe.watermark == null) }
    val image = rememberPageImage(store, page, recipe.copy(watermark = null))
    ToolFrame(R.string.watermark, "watermark-tool", onCancel, {
        onDone(recipe.copy(watermark = text.trim().take(40).ifBlank { null }, watermarkPlace = place, watermarkStyle = style, watermarkSpot = spot), allPages && offerAllPages)
    }, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            Canvas(Modifier.fillMaxSize().testTag("watermark-canvas")
                .pointerInput(Unit) {
                    detectDragGestures { change, delta ->
                        if (place == MarkPlace.FREE) {
                            change.consume()
                            spot = spot.copy(x = (spot.x + delta.x / area.width).coerceIn(0f, 1f), y = (spot.y + delta.y / area.height).coerceIn(0f, 1f))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at -> if (place == MarkPlace.FREE) { val v = area.toPage(at); spot = spot.copy(x = v.x.coerceIn(0f, 1f), y = v.y.coerceIn(0f, 1f)) } }
                }) {
                val shown = text.trim()
                if (shown.isNotEmpty()) {
                    val canvas = drawContext.canvas.nativeCanvas
                    canvas.save(); canvas.translate(area.left, area.top); canvas.clipRect(0f, 0f, area.width, area.height)
                    PageMarks.watermark(canvas, area.width, area.height, shown, place, style, spot)
                    canvas.restore()
                    if (place == MarkPlace.FREE) {
                        val (bw, bh) = PageMarks.watermarkBox(area.width, area.height, shown, style)
                        val c = area.toScreen(Vertex(spot.x, spot.y)); val turn = Math.toRadians(spot.angle.toDouble())
                        val cs = cos(turn).toFloat(); val sn = sin(turn).toFloat()
                        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
                            val x = sx * (bw / 2 + 10f); val y = sy * (bh / 2 + 8f); Offset(c.x + x * cs - y * sn, c.y + x * sn + y * cs)
                        }
                        drawPath(Path().through(corners).apply { close() }, ScanMint, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
    }, controls = {
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(R.string.change_text, Icons.Outlined.Edit, false, "watermark-edit-text", Modifier.weight(1f)) { asking = true }
            ToolChoice(R.string.watermark_single, Icons.Outlined.CropFree, place == MarkPlace.FREE, "watermark-single", Modifier.weight(1f)) { place = MarkPlace.FREE }
            ToolChoice(R.string.watermark_tile, Icons.Outlined.GridOn, place == MarkPlace.TILE, "watermark-tile", Modifier.weight(1f)) { place = MarkPlace.TILE }
            ToolChoice(R.string.remove_watermark, Icons.Outlined.Delete, false, "watermark-remove", Modifier.weight(1f), enabled = text.isNotBlank()) { text = "" }
        }
        Text(stringResource(if (text.isBlank()) R.string.watermark_start_hint else if (place == MarkPlace.FREE) R.string.watermark_drag_hint else R.string.watermark_tile_hint),
            style = MaterialTheme.typography.bodySmall)
        FontChips(style.font, { style = style.copy(font = it) })
        PaletteRow(MarkPalette.watermark, style.rgb, { style = style.copy(rgb = it) }, "watermark-colour")
        ToolSlider(R.string.mark_strength, style.alpha.toFloat(), 15f..230f, "watermark-alpha", "${style.alpha * 100 / 255}%") { style = style.copy(alpha = it.roundToInt()) }
        ToolSlider(R.string.mark_size, style.scale, .3f..3f, "watermark-size") { style = style.copy(scale = it) }
        ToolSlider(R.string.rotate, spot.angle, -90f..90f, "watermark-angle", "${spot.angle.roundToInt()}°") { spot = spot.copy(angle = it) }
        if (offerAllPages) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(allPages, { allPages = it }, modifier = Modifier.testTag("watermark-all-pages")); Text(stringResource(R.string.all_pages))
        }
    })
    if (asking) TextDialog(R.string.watermark_text, text, singleLine = true, limit = 40, onDismiss = { asking = false }) { typed -> text = typed; asking = false }
}

/** The header and footer choices applied to every page of a document. */
data class HeaderChoice(val footer: String?, val footerPlace: StampPlace, val numbers: NumberFormat?, val numberPlace: StampPlace, val stamp: Boolean, val stampPlace: StampPlace, val colour: MarkColor)

/** Text line, page numbers and time at the page edges, previewed on [base] (the first page without marks). */
@Composable
fun HeaderDialog(base: Bitmap?, start: EditRecipe, pageCount: Int, onApply: (HeaderChoice) -> Unit, onDismiss: () -> Unit) {
    var footer by remember { mutableStateOf(start.footer.orEmpty()) }
    var footerPlace by remember { mutableStateOf(start.footerPlace) }
    var numbers by remember { mutableStateOf(start.numbers) }
    var numberPlace by remember { mutableStateOf(start.numberPlace) }
    var stamp by remember { mutableStateOf(start.stamp != null) }
    var stampPlace by remember { mutableStateOf(start.stampPlace) }
    var colour by remember { mutableStateOf(start.noteColor) }
    val now = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()) }
    val count = pageCount.coerceAtLeast(1)
    val words = stringResource(R.string.page_number_words, 1, count)
    val formats = listOf<Pair<NumberFormat?, String>>(null to stringResource(R.string.numbers_off), NumberFormat.PLAIN to "1", NumberFormat.SLASH to "1 / $count", NumberFormat.WORDS to words)
    val preview = EditRecipe(footer = footer.trim().ifBlank { null }, footerPlace = footerPlace, numbers = numbers, numberPlace = numberPlace,
        numberText = formats.firstOrNull { it.first == numbers && numbers != null }?.second, stamp = if (stamp) start.stamp ?: now else null, stampPlace = stampPlace, noteColor = colour)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.header_numbers)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkPreview(base, preview)
            MarkCaption(R.string.footer)
            OutlinedTextField(footer, { footer = it.take(60) }, singleLine = true, placeholder = { Text(stringResource(R.string.footer_example)) },
                modifier = Modifier.fillMaxWidth().testTag("footer-text"))
            PlaceChips(notePlaceLabels, footerPlace, { footerPlace = it }, tag = "footer-place")
            MarkCaption(R.string.page_numbers)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formats.forEach { (format, label) ->
                    FilterChip(numbers == format, { numbers = format }, label = { Text(label) }, modifier = Modifier.testTag("numbers-${format?.name?.lowercase() ?: "off"}"))
                }
            }
            if (numbers != null) PlaceChips(notePlaceLabels, numberPlace, { numberPlace = it }, tag = "number-place")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.timestamp), Modifier.weight(1f))
                Switch(stamp, { stamp = it }, modifier = Modifier.testTag("header-stamp"))
            }
            if (stamp) PlaceChips(notePlaceLabels, stampPlace, { stampPlace = it }, tag = "stamp-place")
            MarkCaption(R.string.note_color)
            ColourChoice(colour, { colour = it })
        } },
        confirmButton = { TextButton({ onApply(HeaderChoice(footer.trim().ifBlank { null }, footerPlace, numbers, numberPlace, stamp, stampPlace, colour)) },
            modifier = Modifier.testTag("apply-header")) { Text(stringResource(R.string.apply_all_pages)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
