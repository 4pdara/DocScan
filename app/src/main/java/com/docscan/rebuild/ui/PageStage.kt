package com.docscan.rebuild.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.Vertex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A page rendering that keeps showing the previous image while the next edit is drawn, so tools do not flicker. */
class PageImage {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var loading by mutableStateOf(false)
    var failure by mutableIntStateOf(0)
}

@Composable
fun rememberPageImage(store: ScanStore, page: Sheet, recipe: EditRecipe, edge: Int = 1400): PageImage {
    val image = remember(page.id) { PageImage() }
    val key = "${page.id}:${page.source}:$recipe:$edge"
    LaunchedEffect(key) {
        val cached = PreviewCache.memory.get(key)
        if (cached != null) { image.bitmap = cached; image.loading = false; return@LaunchedEffect }
        image.loading = true
        try {
            val rendered = PreviewCache.slots.withPermit { withContext(Dispatchers.IO) { ImagePipeline.render(store.source(page), recipe, edge) } }
            PreviewCache.memory.put(key, rendered); image.bitmap = rendered; image.failure = 0
        } catch (e: CancellationException) { throw e }
        catch (e: OutOfMemoryError) { image.failure = R.string.error_memory }
        catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Tool preview failed", e); image.failure = R.string.processing_error }
        finally { image.loading = false }
    }
    return image
}

/** Page fractions to the screen and back, within the rectangle the page is shown in. */
fun Rect.toPage(at: Offset) = Vertex(((at.x - left) / width).coerceIn(-.1f, 1.1f), ((at.y - top) / height).coerceIn(-.1f, 1.1f))
fun Rect.toScreen(v: Vertex) = Offset(left + v.x * width, top + v.y * height)

/** Shows the page fitted in the available space; [overlay] draws and handles touches over it, given where the page is. */
@Composable
fun PageStage(image: PageImage, modifier: Modifier = Modifier, overlay: @Composable BoxScope.(Rect) -> Unit = {}) {
    var area by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { area = it }, contentAlignment = Alignment.Center) {
        val bitmap = image.bitmap
        if (bitmap == null) {
            if (image.failure != 0) Text(stringResource(image.failure), Modifier.padding(16.dp)) else CircularProgressIndicator()
        } else {
            val shown = remember(bitmap) { bitmap.asImageBitmap() }
            Image(shown, null, Modifier.fillMaxSize().testTag("tool-page"), contentScale = ContentScale.Fit)
            val rect = fitImageRect(Size(area.width.toFloat(), area.height.toFloat()), bitmap.width.toFloat() / bitmap.height)
            if (rect.width > 0f && rect.height > 0f) overlay(rect)
        }
        if (image.loading && bitmap != null) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("tool-rendering"))
    }
}

/**
 * A full-screen tool: the page on top, the tool's controls below it, and cancel / title / apply at the bottom,
 * like the editing tools of a scanner app.
 */
@Composable
fun ToolFrame(title: Int, tag: String, onCancel: () -> Unit, onDone: () -> Unit, doneEnabled: Boolean = true,
    stage: @Composable BoxScope.() -> Unit, controls: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp), content = stage)
            Surface(shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), content = controls)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onCancel, Modifier.testTag("tool-cancel")) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
                Text(stringResource(title), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onDone, enabled = doneEnabled, modifier = Modifier.testTag("tool-done")) {
                    Icon(Icons.Outlined.Check, stringResource(R.string.done), tint = if (doneEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** One choice in a tool's row of modes: an icon with its name, highlighted when chosen. */
@Composable
fun ToolChoice(label: Int, icon: ImageVector, selected: Boolean, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp).testTag(tag)
        .semantics { this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = .4f))
        Text(stringResource(label), color = if (enabled) tint else tint.copy(alpha = .4f), style = MaterialTheme.typography.labelSmall, maxLines = 2,
            minLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Colour circles; [colours] are RGB values. */
@Composable
fun PaletteRow(colours: List<Int>, selected: Int, onSelect: (Int) -> Unit, tag: String) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colours.forEach { rgb ->
            val chosen = rgb == selected
            val name = "#%06X".format(rgb)
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or rgb.toLong()))
                .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                .clickable(role = Role.RadioButton) { onSelect(rgb) }
                .semantics { contentDescription = name; this.selected = chosen }.testTag("$tag-%06x".format(rgb)), contentAlignment = Alignment.Center) {
                if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = if (luminance(rgb) > 150) Color.Black else Color.White)
            }
        }
    }
}

fun luminance(rgb: Int) = ((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114) / 1000

/** A labelled slider row used by the tools. */
@Composable
fun ToolSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, shown: String? = null, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.weight(1f).testTag(tag))
        if (shown != null) Text(shown, Modifier.width(48.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
    }
}
