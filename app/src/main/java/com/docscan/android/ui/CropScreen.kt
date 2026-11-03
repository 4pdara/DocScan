package com.docscan.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docscan.android.R
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.processing.CropPoint
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.ScanFilter
import com.docscan.android.processing.PageFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val cropSaver = Saver<CropQuad, String>(save = { it.encode() }, restore = { CropQuad.decode(it) ?: CropQuad.FULL })

@Composable
fun CropScreen(page: PageEntity, repository: DocumentRepository, busy: Boolean,
    onCancel: () -> Unit, onApply: (CropQuad?, PageFormat) -> Unit) {
    var quad by rememberSaveable(page.id, page.crop, stateSaver = cropSaver) { mutableStateOf(CropQuad.decode(page.crop) ?: CropQuad.FULL) }
    var formatName by rememberSaveable(page.id, page.pageFormat) { mutableStateOf(page.pageFormat) }
    val format = PageFormat.fromKey(formatName)
    var rectangle by rememberSaveable(page.id) { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var failed by remember(page.id) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, page.id) {
        value = try { withContext(Dispatchers.Default) { ImagePipeline.render(repository.source(page), 0, ScanFilter.ORIGINAL, 1400) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; null }
    }
    var detecting by remember { mutableStateOf(false) }
    var noBounds by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.crop_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { options = true }, enabled = !busy && !detecting) { Text(stringResource(R.string.crop_options)) }
        }
        Text(stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodyMedium)
        val image = bitmap
        if (image != null) CropCanvas(image, quad, rectangle, onChanged = { quad = it; noBounds = false }, Modifier.weight(1f).fillMaxWidth())
        else Box(Modifier.weight(1f).fillMaxWidth()) {
            if (failed) Text(stringResource(R.string.preview_failed)) else CircularProgressIndicator()
        }
        if (noBounds) Text(stringResource(R.string.crop_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = image != null && !busy && !detecting, modifier = Modifier.weight(1f), onClick = {
                detecting = true; noBounds = false
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.Default) { ImagePipeline.detect(requireNotNull(image)) }
                        if (result == null) noBounds = true else quad = if (rectangle) result.quad.rectangle() else result.quad
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { noBounds = true }
                    finally { detecting = false }
                }
            }) {
                if (detecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.auto_crop))
            }
            OutlinedButton(enabled = !busy && !detecting, modifier = Modifier.weight(1f), onClick = { quad = CropQuad.FULL; noBounds = false }) {
                Text(stringResource(R.string.reset_crop))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(enabled = image != null && !busy && !detecting && quad.isValid(), modifier = Modifier.weight(1f), onClick = {
                onApply(quad.takeUnless { it == CropQuad.FULL }, format)
            }) { Text(stringResource(R.string.apply_crop)) }
        }
    }
    if (options) CropOptions(rectangle, format, onRectangle = {
        rectangle = it
        if (it) quad = quad.rectangle()
    }, onFormat = { formatName = it.name }, onDismiss = { options = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropOptions(rectangle: Boolean, format: PageFormat, onRectangle: (Boolean) -> Unit,
    onFormat: (PageFormat) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.crop_mode), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !rectangle, onClick = { onRectangle(false) }, label = { Text(stringResource(R.string.crop_perspective)) })
                FilterChip(selected = rectangle, onClick = { onRectangle(true) }, label = { Text(stringResource(R.string.crop_rectangle)) })
            }
            Text(stringResource(R.string.page_format), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.page_format_hint), style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PageFormat.entries) { choice ->
                    FilterChip(selected = format == choice, onClick = { onFormat(choice) }, label = { Text(pageFormatLabel(choice)) })
                }
            }
            TextButton(onDismiss) { Text(stringResource(R.string.done)) }
        }
    }
}

@Composable
private fun CropCanvas(bitmap: Bitmap, quad: CropQuad, rectangle: Boolean, onChanged: (CropQuad) -> Unit, modifier: Modifier = Modifier) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableIntStateOf(-1) }
    val currentQuad by rememberUpdatedState(quad)
    val currentRectangle by rememberUpdatedState(rectangle)
    val currentOnChanged by rememberUpdatedState(onChanged)
    val density = LocalDensity.current
    val radius = with(density) { 48.dp.toPx() }
    val description = stringResource(R.string.crop_hint)
    val scale = minOf(canvasSize.width.toFloat() / bitmap.width, canvasSize.height.toFloat() / bitmap.height)
    val w = bitmap.width * scale; val h = bitmap.height * scale
    val rect = Rect((canvasSize.width - w) / 2, (canvasSize.height - h) / 2, (canvasSize.width + w) / 2, (canvasSize.height + h) / 2)
    fun mapped(p: CropPoint) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)
    fun handles(value: CropQuad) = value.points.map(::mapped).let { corners ->
        corners + List(4) { (corners[it] + corners[(it + 1) % 4]) / 2f }
    }
    Canvas(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics { contentDescription = description }.onSizeChanged { canvasSize = it }
        .pointerInput(bitmap, canvasSize) {
            detectDragGestures(
                onDragStart = { position ->
                    val points = handles(currentQuad)
                    selected = points.indices.minByOrNull { (points[it] - position).getDistance() }
                        ?.takeIf { (points[it] - position).getDistance() <= radius } ?: -1
                },
                onDragEnd = { selected = -1 }, onDragCancel = { selected = -1 },
            ) { change, amount ->
                if (selected >= 0 && rect.width > 0 && rect.height > 0) {
                    change.consume()
                    val target = CropPoint((change.position.x - rect.left) / rect.width, (change.position.y - rect.top) / rect.height)
                    val changed = when {
                        selected >= 4 -> {
                            val edge = selected - 4
                            currentQuad.moveEdge(edge, if (currentRectangle && edge % 2 == 0) 0f else amount.x / rect.width,
                                if (currentRectangle && edge % 2 == 1) 0f else amount.y / rect.height)
                        }
                        currentRectangle -> currentQuad.moveRectangleCorner(selected, target)
                        else -> currentQuad.move(selected, target)
                    }
                    changed?.let(currentOnChanged)
                }
            }
        }) {
        if (rect.width <= 0 || rect.height <= 0) return@Canvas
        drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
        val points = quad.points.map(::mapped)
        val polygon = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        val shade = Path().apply { fillType = PathFillType.EvenOdd; addRect(rect); addPath(polygon) }
        drawPath(shade, Color.Black.copy(alpha = .55f))
        drawPath(polygon, Color(0xFF50E0C2), style = Stroke(3.dp.toPx()))
        for (fraction in listOf(1f / 3, 2f / 3)) {
            drawLine(Color.White.copy(alpha = .5f), points[0] + (points[3] - points[0]) * fraction,
                points[1] + (points[2] - points[1]) * fraction, 1.dp.toPx())
            drawLine(Color.White.copy(alpha = .5f), points[0] + (points[1] - points[0]) * fraction,
                points[3] + (points[2] - points[3]) * fraction, 1.dp.toPx())
        }
        points.forEachIndexed { i, point ->
            drawCircle(Color(0xFF087F70), (if (selected == i) 14 else 11).dp.toPx(), point)
            drawCircle(Color.White, 6.dp.toPx(), point)
        }
        val allHandles = handles(quad)
        allHandles.drop(4).forEachIndexed { i, point ->
            drawCircle(Color.White, (if (selected == i + 4) 7 else 5).dp.toPx(), point)
        }
        if (selected in allHandles.indices) {
            val focus = allHandles[selected]
            val size = minOf(104.dp.toPx(), this.size.width / 2f, this.size.height / 2f)
            if (size > 0) {
                val origin = Offset(if (focus.x < this.size.width / 2) this.size.width - size - 8.dp.toPx() else 8.dp.toPx(), 8.dp.toPx())
                val window = (size / (scale * 2.5f)).roundToInt().coerceIn(1, minOf(bitmap.width, bitmap.height))
                val px = ((focus.x - rect.left) / scale).coerceIn(0f, bitmap.width.toFloat())
                val py = ((focus.y - rect.top) / scale).coerceIn(0f, bitmap.height.toFloat())
                val left = (px - window / 2f).roundToInt().coerceIn(0, bitmap.width - window)
                val top = (py - window / 2f).roundToInt().coerceIn(0, bitmap.height - window)
                drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset(left, top), srcSize = IntSize(window, window),
                    dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(size.roundToInt(), size.roundToInt()))
                drawRect(Color.White, origin, androidx.compose.ui.geometry.Size(size, size), style = Stroke(2.dp.toPx()))
                val cross = origin + Offset((px - left) / window * size, (py - top) / window * size)
                drawLine(Color(0xFF087F70), Offset(origin.x, cross.y), Offset(origin.x + size, cross.y), 1.dp.toPx())
                drawLine(Color(0xFF087F70), Offset(cross.x, origin.y), Offset(cross.x, origin.y + size), 1.dp.toPx())
            }
        }
    }
}
