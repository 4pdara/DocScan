package com.docscan.rebuild.ui

import com.docscan.rebuild.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import kotlin.math.min

fun fitImageRect(size: Size, aspect: Float): Rect {
    val width = min(size.width, size.height * aspect); val height = width / aspect
    return Rect((size.width - width) / 2, (size.height - height) / 2, (size.width + width) / 2, (size.height + height) / 2)
}

private fun Treatment.label(): Int = when (this) {
    Treatment.ORIGINAL -> R.string.original; Treatment.COLOR -> R.string.color; Treatment.GRAY -> R.string.gray; Treatment.INK -> R.string.ink; Treatment.PAPER -> if (BuildConfig.CAMSCANNER_NATIVE) R.string.native_paper else R.string.paper
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditor(store: ScanStore, page: Sheet, busy: Boolean, snack: SnackbarHostState, onBack: () -> Unit, onSave: (EditRecipe) -> Unit, onExportImage: (EditRecipe) -> Unit, onGallery: (EditRecipe) -> Unit, onOriginal: () -> Unit,
    title: String? = null, pageIndex: Int = 0, pageCount: Int = 1, saveOnBack: Boolean = false,
    onNavigate: ((Int, EditRecipe) -> Unit)? = null, onRename: ((String) -> Unit)? = null,
    onAddPages: ((EditRecipe) -> Unit)? = null) {
    var encoded by rememberSaveable(page.id) { mutableStateOf(page.recipe) }
    val edit = RecipeCodec.decode(encoded)
    fun change(recipe: EditRecipe) { encoded = RecipeCodec.encode(recipe) }
    var tool by rememberSaveable { mutableStateOf("filters") }
    var original by rememberSaveable { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var draftTitle by remember(title) { mutableStateOf(title.orEmpty()) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestNavigate by rememberUpdatedState(onNavigate)
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }; var pan by remember(page.id) { mutableStateOf(Offset.Zero) }
    val currentEdit by rememberUpdatedState(edit)
    BackHandler(!busy) { if (saveOnBack) onSave(edit) else onBack() }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(draftTitle, { draftTitle = it.take(100) }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename?.invoke(draftTitle.trim()); rename = false }, enabled = draftTitle.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) } })
    val treatments = if (BuildConfig.CAMSCANNER_NATIVE) listOf(Treatment.PAPER, Treatment.NATIVE_MAGIC_COLOR, Treatment.NATIVE_NO_SHADOW, Treatment.NATIVE_INVERT, Treatment.NATIVE_PRO_LOCAL, Treatment.ORIGINAL, Treatment.COLOR, Treatment.LIGHTEN, Treatment.GRAY, Treatment.INK, Treatment.ECO, Treatment.NATIVE_BOARD, Treatment.NATIVE_BOARD_PAPER, Treatment.NATIVE_INVERT_BOARD) else Treatment.entries.filter { !it.requiresReferenceEngine }
    if (export) ModalBottomSheet(onDismissRequest = { export = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.export_scan), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { export = false; onGallery(edit) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.save_gallery)) }
            TextButton(onClick = { export = false; onExportImage(edit) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.save_jpeg)) }
            HorizontalDivider()
            TextButton(onClick = { export = false; onOriginal() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.source_export)) }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = {
            if (title != null && onRename != null) TextButton(onClick = { draftTitle = title; rename = true }, enabled = !busy) {
                Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.Edit, stringResource(R.string.rename), Modifier.size(18.dp))
            } else Text(stringResource(R.string.edit_page), style = MaterialTheme.typography.titleMedium)
        }, navigationIcon = {
            IconButton(onClick = { if (saveOnBack) onSave(edit) else onBack() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
        }, actions = {
            IconButton(onClick = { export = true }, enabled = !busy, modifier = Modifier.testTag("editor-export")) {
                Icon(Icons.Outlined.Download, stringResource(R.string.export_scan))
            }
        })
    },
        bottomBar = {
            Surface(shadowElevation = 6.dp) {
                Column(Modifier.navigationBarsPadding().testTag("editor-actions")) {
                    when (tool) {
                        "filters" -> LazyRow(Modifier.fillMaxWidth().testTag("filter-strip"), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(treatments, key = { it.name }) { treatment ->
                                val selected = edit.treatment == treatment
                                Column(Modifier.width(82.dp).testTag("filter-${treatment.name}").selectable(selected, role = Role.RadioButton, onClick = { original = false; change(edit.copy(treatment = treatment)) }).padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PagePicture(store, page, Modifier.size(72.dp, 82.dp).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(6.dp)).padding(4.dp), recipe = edit.copy(treatment = treatment), edge = 160)
                                    Text(stringResource(treatment.label()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
                                }
                            }
                        }
                        "adjust" -> Column(Modifier.padding(horizontal = 22.dp)) {
                            Text(stringResource(R.string.brightness), style = MaterialTheme.typography.labelMedium)
                            Slider(edit.brightness, { change(edit.copy(brightness = it)) }, valueRange = -50f..50f)
                            Text(stringResource(R.string.contrast), style = MaterialTheme.typography.labelMedium)
                            Slider(edit.contrast, { change(edit.copy(contrast = it)) }, valueRange = .5f..1.8f)
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp).testTag("editor-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                        if (onAddPages != null) EditorTool(R.string.add_pages, Icons.Outlined.AddAPhoto, false, Modifier.weight(1f)) { onAddPages(edit) }
                        EditorTool(R.string.rotate, Icons.Outlined.RotateLeft, false, Modifier.weight(1f)) { tool = "filters"; original = false; change(edit.copy(turns = (edit.turns + 3).mod(4))) }
                        EditorTool(R.string.crop, Icons.Outlined.Crop, tool == "crop", Modifier.weight(1f)) { tool = "crop"; original = false; zoom = 1f; pan = Offset.Zero }
                        EditorTool(if (tool == "adjust") R.string.filters else R.string.adjust, Icons.Outlined.Tune, tool == "adjust", Modifier.weight(1f)) { tool = if (tool == "adjust") "filters" else "adjust"; original = false }
                        Button(onClick = { onSave(edit) }, enabled = !busy, shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(50.dp).testTag("save-edit")) {
                            Icon(Icons.Outlined.Check, stringResource(R.string.save), Modifier.size(28.dp))
                        }
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clipToBounds().background(ScanCharcoal).testTag("page-preview")) {
                if (tool == "crop") {
                    PagePicture(store, page, Modifier.fillMaxSize(), EditRecipe())
                    var handle by remember(page.id) { mutableIntStateOf(-1) }
                    Canvas(Modifier.fillMaxSize().pointerInput(page.id) {
                        detectDragGestures(onDragStart = { touch ->
                            val rect = fitImageRect(Size(size.width.toFloat(), size.height.toFloat()), page.width.toFloat() / page.height)
                            handle = currentEdit.frame.points.indices.minBy { i -> val p = currentEdit.frame.points[i]; (touch - Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)).getDistance() }
                            val p = currentEdit.frame.points[handle]
                            if ((touch - Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)).getDistance() > 48.dp.toPx()) handle = -1
                        }, onDragEnd = { handle = -1 }, onDragCancel = { handle = -1 }) { event, delta ->
                            if (handle >= 0) {
                                event.consume()
                                val rect = fitImageRect(Size(size.width.toFloat(), size.height.toFloat()), page.width.toFloat() / page.height)
                                val vertices = currentEdit.frame.points.toMutableList(); val v = vertices[handle]
                                vertices[handle] = Vertex((v.x + delta.x / rect.width).coerceIn(0f, 1f), (v.y + delta.y / rect.height).coerceIn(0f, 1f))
                                val next = Frame(vertices)
                                if (next.valid()) change(currentEdit.copy(frame = next))
                            }
                        }
                    }) {
                        val rect = fitImageRect(size, page.width.toFloat() / page.height)
                        val corners = edit.frame.points.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                        val path = Path().apply { moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                        drawPath(path, Color(0xFF008F86), style = Stroke(2.dp.toPx()))
                        corners.forEach { drawCircle(Color.White, 10.dp.toPx(), it); drawCircle(Color(0xFF008F86), 7.dp.toPx(), it) }
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
                    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, if (original) EditRecipe() else edit)
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
                FilledTonalButton(onClick = { original = !original; zoom = 1f; pan = Offset.Zero }, enabled = tool != "crop", shape = RoundedCornerShape(6.dp)) {
                    Icon(Icons.Outlined.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (original) R.string.show_result else R.string.compare_original), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EditorTool(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } }
}


