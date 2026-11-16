package com.docscan.rebuild.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.imaging.OfflineOcr
import com.docscan.rebuild.imaging.PageMarks
import com.docscan.rebuild.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hide: drag over what should not be seen, as a black box or a mosaic, or let the page's recognised text find
 * personal details (ID, phone and card numbers, e-mail addresses). Hidden areas are part of the page's edits;
 * the photo itself is kept as it was, and text recognition never reads what is hidden.
 */
@Composable
fun HideTool(store: ScanStore, page: Sheet, recipe: EditRecipe, language: String, onDone: (EditRecipe) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var marks by remember { mutableStateOf(recipe.hides) }
    var undo by remember { mutableStateOf(listOf<List<HideMark>>()) }
    var redo by remember { mutableStateOf(listOf<List<HideMark>>()) }
    var kind by remember { mutableStateOf(HideKind.BLACK) }
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    fun push(next: List<HideMark>) { if (next == marks) return; undo = undo + listOf(marks); redo = emptyList(); marks = next }
    fun commit(area: androidx.compose.ui.geometry.Rect) {
        box?.let { (a, b) ->
            val p = area.toPage(a); val q = area.toPage(b)
            if (abs(p.x - q.x) > .005f && abs(p.y - q.y) > .005f) push(marks + HideMark(kind, Vertex(min(p.x, q.x), min(p.y, q.y)), Vertex(max(p.x, q.x), max(p.y, q.y))))
            box = null
        }
    }
    fun findDetails() {
        if (searching) return
        searching = true; result = null
        scope.launch {
            val found = try {
                val text = OfflineOcr(store).layout(page, recipe.copy(hides = marks), language)
                if (text.width <= 0 || text.height <= 0) emptyList() else
                    SensitiveText.find(text.words.map { TextBox(it.text, it.left.toFloat() / text.width, it.top.toFloat() / text.height, it.right.toFloat() / text.width, it.bottom.toFloat() / text.height) })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Personal details search failed", e); emptyList() }
            push(marks + found.map { it.hide(kind) })
            result = found.size; searching = false
        }
    }
    val image = rememberPageImage(store, page, recipe.copy(hides = marks))
    ToolFrame(R.string.hide_info, "hide-tool", onCancel, { onDone(recipe.copy(hides = marks)) }, doneEnabled = !searching, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            Canvas(Modifier.fillMaxSize().testTag("hide-canvas")
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { at -> box = at to at }, onDragEnd = { commit(area) }, onDragCancel = { commit(area) }) { change, _ ->
                        change.consume(); box = box?.let { it.first to change.position }
                    }
                }) {
                box?.let { (a, b) ->
                    val corner = Offset(min(a.x, b.x), min(a.y, b.y)); val extent = Size(abs(a.x - b.x), abs(a.y - b.y))
                    drawRect(ScanMint.copy(alpha = .35f), corner, extent); drawRect(ScanMint, corner, extent, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }, controls = {
        UndoRow(undo.isNotEmpty(), redo.isNotEmpty(), { redo = listOf(marks) + redo; marks = undo.last(); undo = undo.dropLast(1) },
            { undo = undo + listOf(marks); marks = redo.first(); redo = redo.drop(1) }, "hide")
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(R.string.hide_black, Icons.Outlined.Rectangle, kind == HideKind.BLACK, "hide-black", Modifier.weight(1f)) { kind = HideKind.BLACK }
            ToolChoice(R.string.hide_mosaic, Icons.Outlined.GridOn, kind == HideKind.MOSAIC, "hide-mosaic", Modifier.weight(1f)) { kind = HideKind.MOSAIC }
        }
        Text(stringResource(R.string.hide_hint), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(::findDetails, enabled = !searching, modifier = Modifier.testTag("hide-auto")) {
                Icon(Icons.Outlined.PersonSearch, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.hide_auto))
            }
            if (searching) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.reading_text), style = MaterialTheme.typography.bodySmall) }
        }
        result?.let { n ->
            Text(if (n == 0) stringResource(R.string.hide_none) else stringResource(R.string.hide_found, n), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("hide-result"))
        }
        Text(stringResource(R.string.hide_auto_hint), style = MaterialTheme.typography.bodySmall)
    })
}

/** Shapes: drag to draw a rectangle, an oval, an arrow or a line in a colour and thickness. */
@Composable
fun ShapeTool(store: ScanStore, page: Sheet, recipe: EditRecipe, onDone: (EditRecipe) -> Unit, onCancel: () -> Unit) {
    var shapes by remember { mutableStateOf(recipe.shapes) }
    var undo by remember { mutableStateOf(listOf<List<ShapeMark>>()) }
    var redo by remember { mutableStateOf(listOf<List<ShapeMark>>()) }
    var kind by remember { mutableStateOf(ShapeKind.RECT) }
    var colour by remember { mutableIntStateOf(0xD32F2F) }
    var level by remember { mutableFloatStateOf(5f) }
    var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    fun width() = level * .0012f
    fun push(next: List<ShapeMark>) { if (next == shapes) return; undo = undo + listOf(shapes); redo = emptyList(); shapes = next }
    fun commit(area: androidx.compose.ui.geometry.Rect) {
        drag?.let { (a, b) ->
            val p = area.toPage(a); val q = area.toPage(b)
            if (abs(p.x - q.x) + abs(p.y - q.y) > .01f) push(shapes + ShapeMark(kind, p, q, colour, width()))
            drag = null
        }
    }
    val image = rememberPageImage(store, page, recipe.copy(shapes = emptyList()))
    ToolFrame(R.string.shapes, "shapes-tool", onCancel, { onDone(recipe.copy(shapes = shapes)) }, stage = {
        PageStage(image) { rect ->
            val area by rememberUpdatedState(rect)
            Canvas(Modifier.fillMaxSize().testTag("shapes-canvas")
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { at -> drag = at to at }, onDragEnd = { commit(area) }, onDragCancel = { commit(area) }) { change, _ ->
                        change.consume(); drag = drag?.let { it.first to change.position }
                    }
                }) {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save(); canvas.translate(area.left, area.top); canvas.clipRect(0f, 0f, area.width, area.height)
                shapes.forEach { PageMarks.shape(canvas, area.width, area.height, it) }
                drag?.let { (a, b) -> val p = area.toPage(a); val q = area.toPage(b); if (p != q) PageMarks.shape(canvas, area.width, area.height, ShapeMark(kind, p, q, colour, width())) }
                canvas.restore()
            }
        }
    }, controls = {
        UndoRow(undo.isNotEmpty(), redo.isNotEmpty(), { redo = listOf(shapes) + redo; shapes = undo.last(); undo = undo.dropLast(1) },
            { undo = undo + listOf(shapes); shapes = redo.first(); redo = redo.drop(1) }, "shapes")
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(R.string.shape_rect, Icons.Outlined.CropSquare, kind == ShapeKind.RECT, "shape-rect", Modifier.weight(1f)) { kind = ShapeKind.RECT }
            ToolChoice(R.string.shape_oval, Icons.Outlined.Circle, kind == ShapeKind.OVAL, "shape-oval", Modifier.weight(1f)) { kind = ShapeKind.OVAL }
            ToolChoice(R.string.shape_arrow, Icons.AutoMirrored.Outlined.ArrowRightAlt, kind == ShapeKind.ARROW, "shape-arrow", Modifier.weight(1f)) { kind = ShapeKind.ARROW }
            ToolChoice(R.string.shape_line, Icons.Outlined.HorizontalRule, kind == ShapeKind.LINE, "shape-line", Modifier.weight(1f)) { kind = ShapeKind.LINE }
        }
        Text(stringResource(R.string.shape_hint), style = MaterialTheme.typography.bodySmall)
        PaletteRow(MarkPalette.ink.filterNot { it == 0xFFF59D }, colour, { colour = it }, "shape")
        ToolSlider(R.string.mark_size, level, 1f..30f, "shape-size", "${level.roundToInt()}") { level = it }
    })
}
