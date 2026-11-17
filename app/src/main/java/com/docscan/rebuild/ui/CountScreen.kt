package com.docscan.rebuild.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as DrawArea
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.imaging.ObjectCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import kotlin.math.hypot
import kotlin.math.min

/**
 * Counting (1.3.5): tap one of the things to count and the app marks everything like it. Size and sensitivity can
 * be changed when its guess is off; tapping a mark removes it and tapping elsewhere adds one. Saving keeps the photo
 * itself unchanged and puts the numbered marks on it as editable marks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountScreen(picture: Bitmap, busy: Boolean, onBack: () -> Unit, onSave: (List<Pair<Float, Float>>, Float) -> Unit) {
    var example by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var marks by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    // The size the user set (null: as measured), and the size measured on the example; only the first restarts counting.
    var chosen by remember { mutableStateOf<Float?>(null) }
    var measured by remember { mutableStateOf<Float?>(null) }
    var sensitivity by remember { mutableFloatStateOf(.5f) }
    var working by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    BackHandler(onBack = onBack)
    LaunchedEffect(example, chosen, sensitivity) {
        val at = example ?: run { working = false; return@LaunchedEffect }
        // Waits for a slider to settle before counting again.
        delay(200)
        working = true
        val result = withContext(Dispatchers.Default) {
            runCatching {
                ImagePipeline.ensureAvailable()
                val m = Mat(); try { Utils.bitmapToMat(picture, m); ObjectCount.count(m, at.first, at.second, chosen, sensitivity) } finally { m.release() }
            }.getOrNull()
        }
        if (result != null) { marks = result.centres; if (chosen == null) measured = result.size }
        working = false
    }
    val size = chosen ?: measured ?: .05f
    val short = min(picture.width, picture.height).toFloat()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.count_title, marks.size)) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } })
    }, modifier = Modifier.testTag("count-screen")) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp).onSizeChanged { box = it }) {
                // Where the picture sits in the box (fitted, centred).
                val scale = if (box.width == 0) 1f else min(box.width / picture.width.toFloat(), box.height / picture.height.toFloat())
                val left = (box.width - picture.width * scale) / 2; val top = (box.height - picture.height * scale) / 2
                fun toPicture(o: Offset) = ((o.x - left) / (picture.width * scale)) to ((o.y - top) / (picture.height * scale))
                Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                DrawArea(Modifier.fillMaxSize().testTag("count-canvas").pointerInput(picture, box) {
                    detectTapGestures { o ->
                        val (x, y) = toPicture(o); if (x !in 0f..1f || y !in 0f..1f) return@detectTapGestures
                        if (example == null) { example = x to y; return@detectTapGestures }
                        val near = marks.indexOfFirst { (mx, my) -> hypot(((mx - x) * picture.width).toDouble(), ((my - y) * picture.height).toDouble()) < (chosen ?: measured ?: .05f) * short / 2 }
                        marks = if (near >= 0) marks.filterIndexed { i, _ -> i != near } else marks + (x to y)
                    }
                }) {
                    val radius = (size * short / 2 * scale).coerceAtLeast(6f)
                    marks.forEach { (x, y) ->
                        val c = Offset(left + x * picture.width * scale, top + y * picture.height * scale)
                        drawCircle(Color(0xCC00B69D), radius * .35f, c); drawCircle(Color.White, radius * .35f, c, style = Stroke(2.dp.toPx()))
                    }
                    example?.let { (x, y) -> drawCircle(Color(0xFFFF715D), radius, Offset(left + x * picture.width * scale, top + y * picture.height * scale), style = Stroke(2.dp.toPx())) }
                }
                if (working) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(if (example == null) R.string.count_hint else R.string.count_adjust), style = MaterialTheme.typography.bodySmall)
                if (example != null) {
                    ToolSlider(R.string.count_size, size, .01f.. .5f, "count-size") { chosen = it }
                    ToolSlider(R.string.count_sensitivity, sensitivity, .1f.. .8f, "count-sensitivity") { sensitivity = it }
                }
                Row {
                    TextButton({ example = null; marks = emptyList(); chosen = null; measured = null }, enabled = example != null) { Text(stringResource(R.string.count_again)) }
                    Spacer(Modifier.weight(1f))
                    Button({ onSave(marks, size) }, enabled = marks.isNotEmpty() && !busy && !working, modifier = Modifier.testTag("count-save")) {
                        Text(stringResource(R.string.count_save))
                    }
                }
            }
        }
    }
}

/**
 * The count as marks on the page (fractions of the upright photo): a ring around each thing and its number, so the
 * photo stays as it was and the marks can be edited like any other.
 */
fun countMarks(marks: List<Pair<Float, Float>>, size: Float, width: Int, height: Int): Pair<List<com.docscan.rebuild.model.ShapeMark>, List<com.docscan.rebuild.model.TextMark>> {
    val short = min(width, height).toFloat(); val rx = size * short / 2 / width; val ry = size * short / 2 / height
    val rings = marks.map { (x, y) -> com.docscan.rebuild.model.ShapeMark(com.docscan.rebuild.model.ShapeKind.OVAL, com.docscan.rebuild.model.Vertex(x - rx, y - ry), com.docscan.rebuild.model.Vertex(x + rx, y + ry), 0x00B69D, .004f) }
        .filter { it.valid() }
    val numbers = marks.mapIndexed { i, (x, y) -> com.docscan.rebuild.model.TextMark("${i + 1}", x, y, (size * .45f).coerceIn(.012f, .08f), 0xD32F2F, com.docscan.rebuild.model.MarkFont.BOLD) }
        .filter { it.valid() }
    return rings to numbers
}
