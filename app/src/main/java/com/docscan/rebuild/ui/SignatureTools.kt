package com.docscan.rebuild.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.docscan.rebuild.R
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.data.SignatureCodec
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.imaging.PageMarks
import com.docscan.rebuild.model.MarkColor
import com.docscan.rebuild.model.SignatureMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Signatures drawn on this phone, kept only on it (at most six), to place again without redrawing. */
class SavedSignatures(context: Context) {
    private val prefs = context.getSharedPreferences("signatures", Context.MODE_PRIVATE)
    fun all(): List<SignatureMark> = runCatching {
        val list = JSONArray(prefs.getString("saved", "[]")); (0 until list.length()).mapNotNull { SignatureCodec.decode(list.optJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun add(mark: SignatureMark) = write(listOf(mark) + all().take(5))
    fun remove(index: Int) = write(all().filterIndexed { i, _ -> i != index })
    private fun write(list: List<SignatureMark>) { prefs.edit().putString("saved", JSONArray(list.map { SignatureCodec.encode(it) }).toString()).apply() }
}

/** Draws a signature mark into a Compose canvas exactly as on the saved page. */
@Composable
private fun SignaturePreview(mark: SignatureMark, modifier: Modifier) {
    Canvas(modifier) {
        // As wide as the canvas allows while fitting its height; centred.
        val share = minOf(1f, size.height * mark.aspect / size.width)
        val shown = mark.copy(x = 0f, y = 0f, width = share)
        val boxW = size.width * share; val boxH = boxW / mark.aspect
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save(); canvas.translate((size.width - boxW) / 2, (size.height - boxH) / 2)
        PageMarks.signature(canvas, size.width, size.height, shown)
        canvas.restore()
    }
}

/** Pick a saved signature or draw a new one. */
@Composable
fun SignatureChooser(onChosen: (SignatureMark) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { SavedSignatures(context.applicationContext) }
    var list by remember { mutableStateOf(saved.all()) }
    var drawing by remember { mutableStateOf(list.isEmpty()) }
    if (drawing) SignaturePad(onCancel = { if (list.isEmpty()) onDismiss() else drawing = false }) { mark -> saved.add(mark); onChosen(mark) }
    else AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.signature)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.signature_choose), style = MaterialTheme.typography.bodySmall)
            list.forEachIndexed { index, mark ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignaturePreview(mark, Modifier.weight(1f).height(64.dp).background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                        .clickable { onChosen(mark) }.padding(6.dp).testTag("saved-signature-$index"))
                    IconButton({ saved.remove(index); list = saved.all() }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                }
            }
            OutlinedButton({ drawing = true }, Modifier.fillMaxWidth().testTag("new-signature")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.signature_new)) }
        } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** A white pad to sign on with a finger. */
@Composable
fun SignaturePad(onCancel: () -> Unit, onDone: (SignatureMark) -> Unit) {
    var strokes by remember { mutableStateOf(listOf<List<Offset>>()) }
    var current by remember { mutableStateOf(listOf<Offset>()) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.signature_draw), style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().aspectRatio(2f).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Color.LightGray, RoundedCornerShape(10.dp))
                    .testTag("signature-pad")
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { current = listOf(it) }, onDragEnd = { strokes = strokes + listOf(current); current = emptyList() },
                            onDragCancel = { strokes = strokes + listOf(current); current = emptyList() }) { change, _ -> current = current + change.position }
                    }
                    .pointerInput(Unit) { detectTapGestures { strokes = strokes + listOf(listOf(it)) } }) {
                    // Baseline to sign on.
                    drawLine(Color(0xFFCCCCCC), Offset(size.width * .08f, size.height * .75f), Offset(size.width * .92f, size.height * .75f), 2f)
                    (strokes + listOf(current)).filter { it.isNotEmpty() }.forEach { stroke ->
                        val path = Path().apply { moveTo(stroke[0].x, stroke[0].y); stroke.drop(1).forEach { lineTo(it.x, it.y) } }
                        if (stroke.size == 1) drawCircle(Color.Black, 3.dp.toPx(), stroke[0]) else drawPath(path, Color.Black, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ strokes = emptyList(); current = emptyList() }) { Text(stringResource(R.string.signature_clear)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onCancel) { Text(stringResource(R.string.cancel)) }
                    Button({
                        SignatureCodec.normalize(strokes.map { s -> s.map { it.x to it.y } })?.let { (normalized, aspect) -> onDone(SignatureMark(normalized, aspect)) }
                    }, enabled = strokes.isNotEmpty(), modifier = Modifier.testTag("signature-done")) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

/**
 * Places a signature on a page: drag it, tap to move it there, change its size and colour, and pick the page.
 * The page is shown as saved, without its other signatures' handles; existing signatures stay drawn.
 */
@Composable
fun SignaturePlacer(store: ScanStore, pages: List<Sheet>, signature: SignatureMark, onApply: (Sheet, SignatureMark) -> Unit, onRemoveAll: (Sheet) -> Unit, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var mark by remember { mutableStateOf(signature) }
    val page = pages.getOrNull(index) ?: return
    val shown by produceState<Bitmap?>(null, page.id, page.recipe) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { ImagePipeline.render(store.source(page), page.edit(), 1100) }.getOrNull() }
    }
    var area by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(m: SignatureMark): SignatureMark {
        val heightShare = if (area.height > 0) m.width * area.width / m.aspect / area.height else 0f
        return m.copy(x = m.x.coerceIn(0f, 1f - m.width), y = m.y.coerceIn(0f, (1f - heightShare).coerceAtLeast(0f)))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF202323)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ index-- }, enabled = index > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color.White) }
                    Text(stringResource(R.string.page_counter, index + 1, pages.size), color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ index++ }, enabled = index < pages.lastIndex) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val bitmap = shown
                    if (bitmap == null) CircularProgressIndicator()
                    else Box(Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height).onSizeChanged { area = it }.testTag("signature-page")
                        .pointerInput(page.id) { detectDragGestures { change, drag -> change.consume(); mark = clamp(mark.copy(x = mark.x + drag.x / size.width, y = mark.y + drag.y / size.height)) } }
                        .pointerInput(page.id) { detectTapGestures { at ->
                            val boxH = mark.width * size.width / mark.aspect
                            mark = clamp(mark.copy(x = at.x / size.width - mark.width / 2, y = (at.y - boxH / 2) / size.height))
                        } }) {
                        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
                        Canvas(Modifier.fillMaxSize()) {
                            val left = mark.x * size.width; val top = mark.y * size.height; val w = mark.width * size.width; val h = w / mark.aspect
                            drawRect(Color(0xFF28C7B5), Offset(left, top), androidx.compose.ui.geometry.Size(w, h), style = Stroke(2.dp.toPx()))
                            PageMarks.signature(drawContext.canvas.nativeCanvas, size.width, size.height, mark)
                        }
                    }
                }
                Text(stringResource(R.string.signature_place_hint), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mark_size), color = Color.White, modifier = Modifier.width(64.dp))
                    Slider(mark.width, { mark = clamp(mark.copy(width = it)) }, valueRange = .08f..0.9f, modifier = Modifier.weight(1f).testTag("signature-size"))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MarkColor.BLACK to R.string.color_black, MarkColor.BLUE to R.string.color_blue, MarkColor.RED to R.string.color_red).forEach { (colour, label) ->
                        FilterChip(mark.color == colour, { mark = mark.copy(color = colour) }, label = { Text(stringResource(label)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page.edit().signatures.isNotEmpty()) TextButton({ onRemoveAll(page) }) { Text(stringResource(R.string.signature_remove_page), color = Color(0xFFFF8A80)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button({ onApply(page, clamp(mark)) }, modifier = Modifier.testTag("apply-signature")) { Text(stringResource(R.string.signature_apply)) }
                }
            }
        }
    }
}
