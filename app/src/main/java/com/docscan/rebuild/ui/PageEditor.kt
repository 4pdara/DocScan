package com.docscan.rebuild.ui

import com.docscan.rebuild.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditor(store: ScanStore, page: Sheet, busy: Boolean, snack: SnackbarHostState, onBack: () -> Unit, onSave: (EditRecipe) -> Unit, onExportImage: (EditRecipe) -> Unit, onGallery: (EditRecipe) -> Unit, onOriginal: () -> Unit) {
    var encoded by rememberSaveable(page.id) { mutableStateOf(page.recipe) }
    val edit = RecipeCodec.decode(encoded)
    fun change(recipe: EditRecipe) { encoded = RecipeCodec.encode(recipe) }
    var tool by rememberSaveable { mutableStateOf("filters") }
    var original by rememberSaveable { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    val currentEdit by rememberUpdatedState(edit)
    BackHandler(!busy) { onBack() }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Text(stringResource(R.string.edit_page), style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { IconButton(onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cancel)) } },
        actions = {
            Box {
                IconButton(onClick = { export = true }, enabled = !busy, modifier = Modifier.testTag("editor-export")) { Icon(Icons.Outlined.Download, stringResource(R.string.export_scan)) }
                DropdownMenu(expanded = export, onDismissRequest = { export = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_gallery)) }, onClick = { export = false; onGallery(edit) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_jpeg)) }, onClick = { export = false; onExportImage(edit) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.source_export)) }, onClick = { export = false; onOriginal() })
                }
            }
            Button(onClick = { onSave(edit) }, enabled = !busy, modifier = Modifier.padding(end = 12.dp).testTag("save-edit")) { Text(stringResource(R.string.save)) }
        }) },
        bottomBar = {
            Surface(shadowElevation = 6.dp) {
                Column(Modifier.navigationBarsPadding().testTag("editor-actions")) {
                    when (tool) {
                        "filters" -> LazyRow(Modifier.fillMaxWidth().testTag("filter-strip"), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Treatment.entries.filter { BuildConfig.CAMSCANNER_NATIVE || !it.requiresReferenceEngine }) { treatment ->
                                val selected = edit.treatment == treatment
                                Column(Modifier.width(76.dp).selectable(selected, role = Role.RadioButton, onClick = { original = false; change(edit.copy(treatment = treatment)) }).padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PagePicture(store, page, Modifier.size(64.dp, 76.dp).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(6.dp)).padding(4.dp), recipe = edit.copy(treatment = treatment), edge = 160)
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
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("editor-toolbar"), horizontalArrangement = Arrangement.SpaceEvenly) {
                        EditorTool(R.string.crop, Icons.Outlined.Crop, tool == "crop") { tool = "crop"; original = false; zoom = 1f; pan = Offset.Zero }
                        EditorTool(R.string.rotate, Icons.Outlined.RotateRight, false) { tool = "filters"; original = false; change(edit.copy(turns = (edit.turns + 1).mod(4))) }
                        EditorTool(R.string.filters, Icons.Outlined.Tune, tool == "filters") { tool = "filters"; original = false }
                        EditorTool(R.string.adjust, Icons.Outlined.Contrast, tool == "adjust") { tool = "adjust"; original = false }
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.quality), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { original = !original; zoom = 1f; pan = Offset.Zero }, enabled = tool != "crop") { Text(stringResource(if (original) R.string.show_result else R.string.view_original)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clipToBounds().background(Color(0xFFE9EDEE)).testTag("page-preview")) {
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
                    PagePicture(store, page, Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, drag, scale, _ -> zoom = (zoom * scale).coerceIn(1f, 5f); pan = if (zoom == 1f) Offset.Zero else pan + drag } }
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, if (original) EditRecipe() else edit)
                }
            }
        }
    }
}

@Composable
private fun EditorTool(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    } }
}

