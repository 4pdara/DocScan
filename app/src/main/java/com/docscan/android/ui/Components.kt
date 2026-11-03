package com.docscan.android.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.android.R
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.data.render
import com.docscan.android.processing.ScanFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun AppIcon(@DrawableRes resource: Int, description: String?, modifier: Modifier = Modifier.size(22.dp)) {
    Icon(painterResource(resource), description, modifier)
}

@Composable
fun ActionButton(@DrawableRes icon: Int, text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        AppIcon(icon, null); Spacer(Modifier.width(7.dp)); Text(text)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
            AppIcon(R.drawable.ic_pdf, null, Modifier.padding(24.dp).size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class PreviewResult(val bitmap: Bitmap? = null, val failed: Boolean = false, val page: PageEntity? = null)

@Composable
fun PagePreview(page: PageEntity, repository: DocumentRepository, modifier: Modifier = Modifier, edge: Int = 640, zoomable: Boolean = false) {
    var zoom by remember(page.id, page.sourceFile) { mutableFloatStateOf(1f) }
    var pan by remember(page.id, page.sourceFile) { mutableStateOf(Offset.Zero) }
    var detail by remember(page.id, page.sourceFile) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val renderLock = remember { Mutex() }
    val requestedEdge = if (zoomable && detail) maxOf(edge, 3200) else edge
    LaunchedEffect(page.crop, page.rotation, page.pageFormat) { zoom = 1f; pan = Offset.Zero }
    val preview by produceState(PreviewResult(), page, requestedEdge) {
        if (value.page != page) value = PreviewResult()
        value = try {
            PreviewResult(bitmap = withContext(Dispatchers.Default) {
                renderLock.withLock { ensureActive(); page.render(repository.source(page), requestedEdge) }
            }, page = page)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PreviewResult(failed = true) }
    }
    fun transform(center: Offset, movement: Offset, factor: Float) {
        val bitmap = preview.bitmap ?: return
        if (viewport.width <= 0 || viewport.height <= 0) return
        val next = (zoom * factor).coerceIn(1f, 5f)
        val focus = center - Offset(viewport.width / 2f, viewport.height / 2f)
        val ratio = next / zoom
        val moved = (pan - focus) * ratio + focus + movement
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val maxX = ((bitmap.width * fit * next - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((bitmap.height * fit * next - viewport.height) / 2).coerceAtLeast(0f)
        zoom = next; pan = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
        if (next > 1.25f) detail = true
    }
    val description = stringResource(R.string.page_preview)
    val gestures = if (zoomable) Modifier.semantics { contentDescription = description }
        .onSizeChanged { viewport = it }
        .pointerInput(page.id, page.sourceFile) {
            detectTransformGestures { center, movement, factor, _ -> transform(center, movement, factor) }
        }.pointerInput(page.id, page.sourceFile) {
            detectTapGestures(onDoubleTap = { center ->
                if (zoom > 1f) { zoom = 1f; pan = Offset.Zero } else transform(center, Offset.Zero, 2.5f)
            })
        } else Modifier
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).then(gestures), contentAlignment = Alignment.Center) {
        when {
            preview.bitmap != null -> Image(requireNotNull(preview.bitmap).asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit)
            preview.failed -> Text(stringResource(R.string.preview_failed), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        if (zoomable && preview.bitmap != null) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), shape = RoundedCornerShape(20.dp)) {
            TextButton(onClick = {
                if (zoom > 1f) { zoom = 1f; pan = Offset.Zero }
                else transform(Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero, 2.5f)
            }) { Text(stringResource(if (zoom > 1f) R.string.zoom_reset else R.string.zoom_in)) }
        }
    }
}

@Composable
fun filterLabel(filter: ScanFilter): String = stringResource(when (filter) {
    ScanFilter.ORIGINAL -> R.string.original
    ScanFilter.ENHANCED -> R.string.enhanced
    ScanFilter.GRAYSCALE -> R.string.grayscale
    ScanFilter.MONOCHROME -> R.string.monochrome
    ScanFilter.CLEAN_COLOR -> R.string.clean_color
    ScanFilter.CLEAN_TEXT -> R.string.clean_text
    ScanFilter.LIGHTEN -> R.string.filter_lighten
    ScanFilter.VIVID -> R.string.filter_vivid
    ScanFilter.ADAPTIVE_MONO -> R.string.filter_adaptive
    ScanFilter.BLACKBOARD -> R.string.filter_blackboard
    ScanFilter.INVERT -> R.string.filter_invert
})
