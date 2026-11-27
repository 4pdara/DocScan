package com.docscan.rebuild.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*
import com.docscan.rebuild.model.*
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.imaging.PageFlattener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import kotlin.math.min

fun fitImageRect(size: Size, aspect: Float): Rect {
    val width = min(size.width, size.height * aspect); val height = width / aspect
    return Rect((size.width - width) / 2, (size.height - height) / 2, (size.width + width) / 2, (size.height + height) / 2)
}

fun Treatment.label(): Int = when (this) {
    Treatment.ORIGINAL -> R.string.original; Treatment.COLOR -> R.string.color; Treatment.GRAY -> R.string.gray; Treatment.INK -> R.string.ink; Treatment.PAPER -> R.string.clean_paper
    Treatment.NATIVE_MAGIC_COLOR -> R.string.native_magic_color
    Treatment.NATIVE_NO_SHADOW -> R.string.native_no_shadow
    Treatment.NATIVE_INVERT -> R.string.native_invert
    Treatment.NATIVE_PRO_LOCAL -> R.string.native_pro_local
    Treatment.LIGHTEN -> R.string.lighten
    Treatment.ECO -> R.string.eco
    Treatment.NATIVE_BOARD -> R.string.native_board
    Treatment.NATIVE_BOARD_PAPER -> R.string.native_board_paper
    Treatment.NATIVE_INVERT_BOARD -> R.string.native_invert_board
}

/** A tool button of the editor's bottom row. */
private data class EditorAction(val id: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val selected: Boolean = false, val enabled: Boolean = true, val run: () -> Unit)

/**
 * The page editor, laid out like a scanner app's edit screen: the page, and below it three tabs of tools —
 * Image (crop, filters, adjust, rotate, edit text, smart erase, colour marks, curved page, text recognition,
 * retake), Marking (signature, text, brush, watermark, header and numbers) and Pages (add, split, delete, save).
 * Opening a saved page shows the tools without choosing one; a new scan opens with the filters ([startTool]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditor(store: ScanStore, page: Sheet, busy: Boolean, snack: SnackbarHostState, onBack: () -> Unit, onSave: (EditRecipe) -> Unit, onExportImage: (EditRecipe) -> Unit, onGallery: (EditRecipe) -> Unit, onOriginal: () -> Unit,
    title: String? = null, pageIndex: Int = 0, pageCount: Int = 1, saveOnBack: Boolean = false,
    onNavigate: ((Int, EditRecipe) -> Unit)? = null, onRename: ((String) -> Unit)? = null,
    onAddPages: ((EditRecipe) -> Unit)? = null, onSplit: ((EditRecipe, Float, Boolean) -> Unit)? = null, onOcr: ((EditRecipe) -> Unit)? = null,
    startTool: String? = null, language: String = "heb+eng", onDelete: (() -> Unit)? = null, onRetake: ((EditRecipe) -> Unit)? = null,
    onWatermarkAll: ((EditRecipe) -> Unit)? = null, onHeader: ((HeaderChoice) -> Unit)? = null) {
    var encoded by rememberSaveable(page.id) { mutableStateOf(page.recipe) }
    val edit = RecipeCodec.decode(encoded)
    fun change(recipe: EditRecipe) { encoded = RecipeCodec.encode(recipe) }
    val fullTools = setOf("erase", "text", "brush", "addtext", "watermark", "hide", "shapes")
    var tool by rememberSaveable(page.id) { mutableStateOf(startTool?.takeIf { it !in fullTools }) }
    var full by rememberSaveable(page.id) { mutableStateOf(startTool?.takeIf { it in fullTools }) }
    var tab by rememberSaveable { mutableStateOf(when (startTool) { "brush", "addtext", "watermark", "hide", "shapes" -> "marks"; else -> "image" }) }
    var original by rememberSaveable { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var signing by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf<SignatureMark?>(null) }
    var header by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var split by remember(page.id) { mutableFloatStateOf(.5f) }
    var rightFirst by rememberSaveable { mutableStateOf(true) }
    var draftTitle by remember(title) { mutableStateOf(title.orEmpty()) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestNavigate by rememberUpdatedState(onNavigate)
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }; var pan by remember(page.id) { mutableStateOf(Offset.Zero) }
    val currentEdit by rememberUpdatedState(edit)
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    // The steps from the shutter to this editor for a page just photographed, when "זמני זיהוי במצלמה" is on (1.3.9).
    val showTimings = remember { ScanSettings(context).showTimings }
    var captureSteps by remember(page.id) { mutableStateOf(if (CaptureTimes.pageId == page.id) CaptureTimes.steps else emptyList()) }
    var flattening by remember(page.id) { mutableStateOf(false) }
    val numberWords = stringResource(R.string.page_number_words, pageIndex + 1, pageCount.coerceAtLeast(1))
    BackHandler(!busy) { if (tool != null && startTool == null) tool = null else if (saveOnBack) onSave(edit) else onBack() }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(draftTitle, { draftTitle = it.take(100) }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename?.invoke(draftTitle.trim()); rename = false }, enabled = draftTitle.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.delete_page_title)) },
        text = { Text(stringResource(R.string.delete_page_message)) },
        confirmButton = { TextButton({ confirmDelete = false; onDelete?.invoke() }, modifier = Modifier.testTag("confirm-delete-page")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    if (signing) SignatureChooser(onChosen = { signing = false; placing = it }, onDismiss = { signing = false })
    placing?.let { mark ->
        SignaturePlacer(store, listOf(page), mark, onApply = { _, placed -> placing = null; change(currentEdit.copy(signatures = currentEdit.signatures + placed)) },
            onRemoveAll = { placing = null; change(currentEdit.copy(signatures = emptyList())) }, onDismiss = { placing = null }, recipe = edit)
    }
    if (header) {
        val unmarked by produceState<android.graphics.Bitmap?>(null, page.id, edit.copy(footer = null, stamp = null, numberText = null)) {
            value = withContext(Dispatchers.IO) { runCatching { ImagePipeline.render(store.source(page), currentEdit.copy(footer = null, stamp = null, numberText = null), 700) }.getOrNull() }
        }
        HeaderDialog(unmarked, edit, pageCount, onDismiss = { header = false }, onApply = { choice ->
            header = false
            val now = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date())
            val label = when (choice.numbers) { null -> null; NumberFormat.PLAIN -> "${pageIndex + 1}"; NumberFormat.SLASH -> "${pageIndex + 1} / $pageCount"; NumberFormat.WORDS -> numberWords }
            change(currentEdit.copy(footer = choice.footer, footerPlace = choice.footerPlace, numbers = choice.numbers, numberPlace = choice.numberPlace, numberText = label,
                stamp = if (choice.stamp) currentEdit.stamp ?: now else null, stampPlace = choice.stampPlace, noteColor = choice.colour))
            onHeader?.invoke(choice)
        })
    }
    // All 14 filters, DocScan's own.
    val treatments = listOf(Treatment.PAPER, Treatment.NATIVE_MAGIC_COLOR, Treatment.NATIVE_NO_SHADOW, Treatment.NATIVE_INVERT, Treatment.NATIVE_PRO_LOCAL, Treatment.ORIGINAL, Treatment.COLOR, Treatment.LIGHTEN, Treatment.GRAY, Treatment.INK, Treatment.ECO, Treatment.NATIVE_BOARD, Treatment.NATIVE_BOARD_PAPER, Treatment.NATIVE_INVERT_BOARD)
    if (export) ModalBottomSheet(onDismissRequest = { export = false }, modifier = Modifier.testTag("editor-export-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.export_scan), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { export = false; onGallery(edit) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.save_gallery)) }
            TextButton(onClick = { export = false; onExportImage(edit) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.save_jpeg)) }
            HorizontalDivider()
            TextButton(onClick = { export = false; onOriginal() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.source_export)) }
        }
    }
    fun open(next: String) { tool = if (tool == next) null else next; original = false; zoom = 1f; pan = Offset.Zero }
    // Turning the page carries everything placed on it along.
    fun rotate() {
        val aspect = CropGeometry.dimensions(edit.frame, page.width, page.height, edit.outputAspect).rotated(edit.turns).aspect
        original = false; change(edit.turnedBy(3, aspect))
    }
    val actions = when (tab) {
        "marks" -> listOf(
            EditorAction("sign", R.string.signature, Icons.Outlined.Draw) { signing = true },
            EditorAction("addtext", R.string.add_text, Icons.Outlined.Title) { full = "addtext" },
            EditorAction("brush", R.string.brush, Icons.Outlined.Brush) { full = "brush" },
            EditorAction("hide", R.string.hide_info, Icons.Outlined.VisibilityOff, edit.hides.isNotEmpty()) { full = "hide" },
            EditorAction("shapes", R.string.shapes, Icons.Outlined.Category) { full = "shapes" },
            EditorAction("watermark", R.string.watermark, Icons.Outlined.WaterDrop) { full = "watermark" },
            EditorAction("header", R.string.header_short, Icons.Outlined.FormatListNumbered) { header = true })
        "pages" -> listOfNotNull(
            if (onAddPages != null) EditorAction("add", R.string.add_pages, Icons.Outlined.AddAPhoto) { onAddPages(edit) } else null,
            EditorAction("split", R.string.split_book, Icons.Outlined.VerticalSplit, tool == "split", onSplit != null) { open("split") },
            if (onDelete != null) EditorAction("delete", R.string.delete_page, Icons.Outlined.Delete) { confirmDelete = true } else null,
            EditorAction("gallery", R.string.to_gallery, Icons.Outlined.PhotoLibrary) { onGallery(edit) },
            EditorAction("jpeg", R.string.as_image, Icons.Outlined.Image) { onExportImage(edit) },
            EditorAction("original", R.string.source_export, Icons.Outlined.SaveAlt) { onOriginal() })
        else -> listOfNotNull(
            EditorAction("crop", R.string.crop, Icons.Outlined.Crop, tool == "crop") { open("crop") },
            EditorAction("filters", R.string.filters, Icons.Outlined.PhotoFilter, tool == "filters") { open("filters") },
            EditorAction("adjust", R.string.adjust, Icons.Outlined.Tune, tool == "adjust") { open("adjust") },
            EditorAction("rotate", R.string.rotate, Icons.Outlined.RotateLeft) { rotate() },
            EditorAction("text", R.string.edit_text, Icons.Outlined.TextFields) { full = "text" },
            EditorAction("erase", R.string.smart_erase, Icons.Outlined.AutoFixHigh) { full = "erase" },
            EditorAction("colour", R.string.colour_marks_short, Icons.Outlined.FormatColorReset, edit.eraseColor) { change(edit.copy(eraseColor = !edit.eraseColor)) },
            EditorAction("cleanup", R.string.cleanup, Icons.Outlined.CleaningServices, tool == "cleanup" || edit.glare || edit.fingers || edit.moire) { open("cleanup") },
            EditorAction("curve", R.string.book_curve, Icons.Outlined.AutoStories, tool == "curve") { open("curve") },
            if (onOcr != null) EditorAction("ocr", R.string.read_text, Icons.Outlined.FindInPage) { onOcr(edit) } else null,
            if (onRetake != null) EditorAction("retake", R.string.retake, Icons.Outlined.CameraAlt) { onRetake(edit) } else null)
    }
    when (full) {
        "erase" -> EraseTool(store, page, edit, language, onDone = { change(it); full = null }, onCancel = { full = null })
        "text" -> TextEditTool(store, page, edit, language, onDone = { change(it); full = null }, onCancel = { full = null })
        "brush" -> BrushTool(store, page, edit, onDone = { change(it); full = null }, onCancel = { full = null })
        "hide" -> HideTool(store, page, edit, language, onDone = { change(it); full = null }, onCancel = { full = null })
        "shapes" -> ShapeTool(store, page, edit, onDone = { change(it); full = null }, onCancel = { full = null })
        "addtext" -> TextMarkTool(store, page, edit, onDone = { change(it); full = null }, onCancel = { full = null })
        "watermark" -> WatermarkTool(store, page, edit, offerAllPages = pageCount > 1 && onWatermarkAll != null, onDone = { recipe, all ->
            change(recipe); full = null; if (all) onWatermarkAll?.invoke(recipe) }, onCancel = { full = null })
        else -> Scaffold(modifier = Modifier.testTag("page-editor"), snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = {
            if (title != null && onRename != null) TextButton(onClick = { draftTitle = title; rename = true }, enabled = !busy) {
                // The whole title is tappable to rename; it keeps the room a pencil would take.
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            } else Text(stringResource(R.string.edit_page), style = MaterialTheme.typography.titleMedium)
        }, navigationIcon = {
            IconButton(onClick = { if (saveOnBack) onSave(edit) else onBack() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
        }, actions = {
            IconButton(onClick = { export = true }, enabled = !busy, modifier = Modifier.testTag("editor-export")) {
                Icon(Icons.Outlined.Download, stringResource(R.string.export_scan))
            }
            Button(onClick = { onSave(edit) }, enabled = !busy, shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.padding(end = 6.dp).testTag("save-edit")) {
                Icon(Icons.Outlined.Check, null, Modifier.size(22.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.done))
            }
        })
    },
        bottomBar = {
            Surface(shadowElevation = 6.dp, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
                Column(Modifier.navigationBarsPadding().testTag("editor-actions")) {
                    when (tool) {
                        "split" -> Column(Modifier.padding(horizontal=16.dp)) {
                            Text(stringResource(R.string.split_hint),style=MaterialTheme.typography.bodySmall)
                            Slider(split,{split=it},valueRange=.15f.. .85f,modifier=Modifier.testTag("book-split-position"))
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(rightFirst,{rightFirst=it});Text(stringResource(R.string.right_page_first),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={onSplit?.invoke(edit,split,rightFirst)},enabled=!busy && runCatching { edit.frame.split(split) }.isSuccess){Text(stringResource(R.string.split_apply))}
                                TextButton(onClick={tool=null}){Text(stringResource(R.string.cancel))}
                            }
                        }
                        "curve" -> Column(Modifier.padding(horizontal=16.dp)) {
                            // Automatic straightening from the text lines (on by default for pages whose edges were detected).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.auto_flatten), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (flattening) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                                Switch(edit.flatten.isNotEmpty(), { on ->
                                    if (!on) change(edit.copy(flatten = emptyList())) else {
                                        flattening = true
                                        scope.launch {
                                            val coef = withContext(Dispatchers.Default) { runCatching { PageFlattener.estimate(store.source(page), currentEdit) }.getOrNull() }
                                            flattening = false
                                            if (coef != null) change(currentEdit.copy(flatten = coef.toList())) else snack.showSnackbar(context.getString(R.string.auto_flatten_none))
                                        }
                                    }
                                }, enabled = !flattening && !busy, modifier = Modifier.testTag("auto-flatten"))
                            }
                            // Curved top and bottom page edges (a book page's bow): found automatically after capture.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.edge_flatten), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(edit.edges.isNotEmpty(), { on ->
                                    if (!on) change(edit.copy(edges = emptyList())) else {
                                        flattening = true
                                        scope.launch {
                                            val curves = withContext(Dispatchers.Default) { runCatching { ImagePipeline.pageEdges(store.source(page), currentEdit.frame) }.getOrNull() }
                                            flattening = false
                                            if (curves != null) change(currentEdit.copy(edges = curves.values)) else snack.showSnackbar(context.getString(R.string.edge_flatten_none))
                                        }
                                    }
                                }, enabled = !flattening && !busy, modifier = Modifier.testTag("edge-flatten"))
                            }
                            Text(stringResource(R.string.curve_hint),style=MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.curve_top),style=MaterialTheme.typography.labelSmall)
                            Slider(edit.curvature.top,{change(edit.copy(curvature=edit.curvature.copy(top=it)))},valueRange=-.2f.. .2f,modifier=Modifier.testTag("curve-top"))
                            Text(stringResource(R.string.curve_bottom),style=MaterialTheme.typography.labelSmall)
                            Slider(edit.curvature.bottom,{change(edit.copy(curvature=edit.curvature.copy(bottom=it)))},valueRange=-.2f.. .2f,modifier=Modifier.testTag("curve-bottom"))
                            Row { TextButton(onClick={change(edit.copy(curvature=BookCurve()))}){Text(stringResource(R.string.reset_curve))};TextButton(onClick={tool=null}){Text(stringResource(R.string.done))} }
                        }
                        "filters" -> LazyRow(Modifier.fillMaxWidth().testTag("filter-strip"), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(treatments, key = { it.name }) { treatment ->
                                val selected = edit.treatment == treatment
                                Column(Modifier.width(82.dp).testTag("filter-${treatment.name}").selectable(selected, role = Role.RadioButton, onClick = { original = false; change(edit.copy(treatment = treatment)) }).background(if (selected) ScanTeal else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PagePicture(store, page, Modifier.size(72.dp, 82.dp).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(6.dp)).padding(4.dp), recipe = edit.copy(treatment = treatment).withoutMarks(), edge = 160)
                                    Text(stringResource(treatment.label()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp, bottom = 3.dp), color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                        "cleanup" -> Column(Modifier.padding(horizontal = 16.dp)) {
                            // Each clean-up is kept in the page's edits and applied before the filter.
                            listOf(Triple(R.string.clean_glare, edit.glare, "clean-glare"), Triple(R.string.clean_fingers, edit.fingers, "clean-fingers"), Triple(R.string.clean_moire, edit.moire, "clean-moire")).forEach { (label, on, tag) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(on, { value -> change(when (tag) { "clean-glare" -> edit.copy(glare = value); "clean-fingers" -> edit.copy(fingers = value); else -> edit.copy(moire = value) }) },
                                        enabled = !busy, modifier = Modifier.testTag(tag))
                                }
                            }
                            Text(stringResource(R.string.cleanup_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        "adjust" -> Column(Modifier.padding(horizontal = 22.dp)) {
                            Text(stringResource(R.string.brightness), style = MaterialTheme.typography.labelMedium)
                            Slider(edit.brightness, { change(edit.copy(brightness = it)) }, valueRange = -50f..50f)
                            Text(stringResource(R.string.contrast), style = MaterialTheme.typography.labelMedium)
                            Slider(edit.contrast, { change(edit.copy(contrast = it)) }, valueRange = .5f..1.8f)
                            Text(stringResource(R.string.detail), style = MaterialTheme.typography.labelMedium)
                            Slider(edit.detail, { change(edit.copy(detail = it)) }, valueRange = 0f..1f, modifier = Modifier.testTag("adjust-detail"))
                        }
                        "crop" -> Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.crop_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { change(edit.copy(frame = Frame.FULL, outputAspect = null)) }) { Text(stringResource(R.string.full_photo)) }
                            }
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(selected = edit.outputAspect == null, onClick = { change(edit.copy(outputAspect = null)) }, label = { Text(stringResource(R.string.aspect_auto)) }) }
                                item { FilterChip(selected = edit.outputAspect == page.width.toFloat() / page.height, onClick = { change(edit.copy(outputAspect = page.width.toFloat() / page.height)) }, label = { Text(stringResource(R.string.aspect_photo)) }) }
                                item { FilterChip(selected = edit.outputAspect == 210f / 297f, onClick = { change(edit.copy(outputAspect = 210f / 297f)) }, label = { Text("A4") }) }
                                item { FilterChip(selected = edit.outputAspect == 1f, onClick = { change(edit.copy(outputAspect = 1f)) }, label = { Text(stringResource(R.string.aspect_square)) }) }
                            }
                            Text(stringResource(R.string.aspect_adjust), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                            val ratio = edit.outputAspect ?: CropGeometry.dimensions(edit.frame, page.width, page.height).aspect
                            Slider(ratio.coerceIn(.25f, 4f), { change(edit.copy(outputAspect = it)) }, valueRange = .25f..4f, modifier = Modifier.padding(horizontal = 16.dp).testTag("aspect-adjust"))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                        listOf("image" to R.string.tab_image, "marks" to R.string.tab_marks, "pages" to R.string.tab_pages).forEach { (id, label) ->
                            val on = tab == id
                            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { tab = id }.padding(horizontal = 18.dp, vertical = 4.dp).testTag("editor-tab-$id"),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(label), color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                                Box(Modifier.padding(top = 3.dp).size(28.dp, 3.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                    LazyRow(Modifier.fillMaxWidth().testTag("editor-toolbar"), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                        items(actions, key = { it.id }) { action ->
                            EditorTool(action.label, action.icon, action.selected, Modifier.width(76.dp).testTag("edit-${action.id}"), enabled = action.enabled && !busy, onClick = action.run)
                        }
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clipToBounds().background(ScanCharcoal).testTag("page-preview")) {
                if (tool == "crop" || tool == "split") {
                    PagePicture(store, page, Modifier.fillMaxSize(), EditRecipe())
                    if(tool=="crop") CropHandles(page.width.toFloat()/page.height,edit.frame) { frame -> change(currentEdit.copy(frame=frame)) }
                    else Canvas(Modifier.fillMaxSize().testTag("split-preview")) {
                        val rect=fitImageRect(size,page.width.toFloat()/page.height)
                        val halves=runCatching { edit.frame.split(split) }.getOrNull()
                        halves?.let { val a=it.first.points[1];val b=it.first.points[2]
                            drawLine(ScanMint,Offset(rect.left+a.x*rect.width,rect.top+a.y*rect.height),Offset(rect.left+b.x*rect.width,rect.top+b.y*rect.height),4.dp.toPx()) }
                    }
                } else {
                    PagePicture(store, page, Modifier.fillMaxSize().pointerInput(page.id, busy, rtl) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var drag = Offset.Zero
                            var multiple = false
                            do {
                                val event = awaitPointerEvent()
                                val change = event.calculatePan()
                                if (event.changes.count { it.pressed } > 1) multiple = true
                                if (multiple || zoom > 1f) {
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                    pan = if (zoom == 1f) Offset.Zero else pan + change
                                    event.changes.forEach { it.consume() }
                                } else drag += change
                            } while (event.changes.any { it.pressed })
                            if (!busy && !multiple && zoom == 1f && kotlin.math.abs(drag.x) > 64.dp.toPx() && kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y) * 1.4f) {
                                val next = pageIndex + if ((drag.x > 0) == rtl) 1 else -1
                                if (next in 0 until pageCount) latestNavigate?.invoke(next, currentEdit)
                            }
                        }
                    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, if (original) EditRecipe() else edit,
                        // Zoomed in, the page is drawn from the full-resolution photo so small print stays sharp.
                        edge = if (zoom > 1.25f) 4096 else 1800,
                        onShown = { CaptureTimes.shown(page.id); if (CaptureTimes.pageId == page.id) captureSteps = CaptureTimes.steps })
                }
                if (showTimings && captureSteps.isNotEmpty()) {
                    val parts = captureSteps.map { stringResource(it.label, it.ms) } + stringResource(R.string.timing_total, captureSteps.sumOf { it.ms })
                    Text(parts.joinToString(" · "), Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp).testTag("capture-timing"), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onNavigate?.invoke(pageIndex - 1, edit) }, enabled = !busy && pageIndex > 0 && onNavigate != null, modifier = Modifier.testTag("review-previous")) {
                        Icon(if (rtl) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft, stringResource(R.string.previous_page))
                    }
                    Text(stringResource(R.string.page_counter, pageIndex + 1, pageCount), Modifier.testTag("review-counter"), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onNavigate?.invoke(pageIndex + 1, edit) }, enabled = !busy && pageIndex + 1 < pageCount && onNavigate != null, modifier = Modifier.testTag("review-next")) {
                        Icon(if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight, stringResource(R.string.next_page))
                    }
                }
                FilledTonalButton(onClick = { original = !original; zoom = 1f; pan = Offset.Zero }, enabled = tool != "crop" && tool != "split", shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Icon(Icons.Outlined.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (original) R.string.show_result else R.string.compare_original), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    }
}

@Composable
private fun EditorTool(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(onClick, modifier = modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = .4f))
        Text(stringResource(label), color = if (enabled) tint else tint.copy(alpha = .4f), style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    } }
}



@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CropHandles(aspect: Float, frame: Frame, onChange: (Frame) -> Unit) {
    val latest by rememberUpdatedState(frame);val change by rememberUpdatedState(onChange)
    var handle by remember { mutableIntStateOf(-1) }
    var size by remember { mutableStateOf(Size.Zero) }
    val rect=fitImageRect(size,aspect)
    fun location(p:Vertex)=Offset(rect.left+p.x*rect.width,rect.top+p.y*rect.height)
    Box(Modifier.fillMaxSize().onSizeChanged { size=Size(it.width.toFloat(),it.height.toFloat()) }) {
        Canvas(Modifier.fillMaxSize().testTag("crop-overlay").magnifier(sourceCenter={if(handle>=0)location(latest.handles()[handle]) else Offset.Unspecified})
            .pointerInput(aspect) {
                // Pointer events may arrive faster than composition. Keep a gesture-local frame
                // instead of repeatedly applying deltas to the last composed frame.
                var dragFrame: Frame? = null
                detectDragGestures(onDragStart={touch ->
                    val r=fitImageRect(Size(this.size.width.toFloat(),this.size.height.toFloat()),aspect)
                    handle=latest.handles().indices.minBy { i -> val p=latest.handles()[i];(touch-Offset(r.left+p.x*r.width,r.top+p.y*r.height)).getDistance() }
                    val p=latest.handles()[handle]
                    if((touch-Offset(r.left+p.x*r.width,r.top+p.y*r.height)).getDistance()>40.dp.toPx())handle=-1
                    dragFrame = if(handle>=0) latest else null
                },onDragEnd={handle=-1;dragFrame=null},onDragCancel={handle=-1;dragFrame=null}) { event,delta ->
                    if(handle>=0) {
                        event.consume()
                        val r=fitImageRect(Size(this.size.width.toFloat(),this.size.height.toFloat()),aspect)
                        val next=(dragFrame?:latest).dragHandle(handle,delta.x/r.width,delta.y/r.height,aspect)
                        dragFrame=next
                        change(next)
                    }
                }
            }) {
            val r=fitImageRect(this.size,aspect);val corners=frame.points.map { Offset(r.left+it.x*r.width,r.top+it.y*r.height) }
            val path=Path().apply {moveTo(corners[0].x,corners[0].y);corners.drop(1).forEach {lineTo(it.x,it.y)};close()}
            drawPath(path,ScanMint,style=Stroke(2.dp.toPx()))
            frame.handles().forEachIndexed { i,p -> val point=Offset(r.left+p.x*r.width,r.top+p.y*r.height)
                if(i==handle)drawCircle(ScanMint.copy(alpha=.25f),24.dp.toPx(),point)
                drawCircle(Color.White,if(i<4)10.dp.toPx() else 9.dp.toPx(),point)
                drawCircle(ScanMint,if(i<4)7.dp.toPx() else 6.dp.toPx(),point)
            }
        }
        frame.handles().forEachIndexed { i,p ->
            val point=location(p);val density=androidx.compose.ui.platform.LocalDensity.current
            val half=with(density){24.dp.toPx()}
            val label=stringResource(if(i<4)R.string.crop_corner else R.string.crop_edge,i%4+1)
            val left=stringResource(R.string.move_left);val right=stringResource(R.string.move_right);val up=stringResource(R.string.move_up);val down=stringResource(R.string.move_down)
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset((point.x-half).toInt(),(point.y-half).toInt()) }.size(48.dp).testTag("crop-handle-$i").semantics {
                contentDescription=label
                customActions=listOf(CustomAccessibilityAction(left){change(latest.dragHandle(i,-.01f,0f,aspect));true},CustomAccessibilityAction(right){change(latest.dragHandle(i,.01f,0f,aspect));true},CustomAccessibilityAction(up){change(latest.dragHandle(i,0f,-.01f,aspect));true},CustomAccessibilityAction(down){change(latest.dragHandle(i,0f,.01f,aspect));true})
            })
        }
    }
}
