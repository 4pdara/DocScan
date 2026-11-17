package com.docscan.rebuild.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.min

/**
 * Counting (1.3.5): tap one of the things to count and the app marks everything like it. Size and sensitivity can
 * be changed when its guess is off; tapping a mark removes it and tapping elsewhere adds one. The picture with its
 * numbered marks can be saved as a document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountScreen(picture: Bitmap, busy: Boolean, onBack: () -> Unit, onSave: (ByteArray, Int) -> Unit) {
    var example by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var marks by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    var size by remember { mutableStateOf<Float?>(null) }
    var sensitivity by remember { mutableFloatStateOf(.5f) }
    var working by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    BackHandler(onBack = onBack)
    // Counting again whenever the example, the size or the sensitivity changes.
    LaunchedEffect(example, size, sensitivity) {
        val at = example ?: return@LaunchedEffect
        working = true
        val result = withContext(Dispatchers.Default) {
            ImagePipeline.ensureAvailable()
            val m = Mat(); try { Utils.bitmapToMat(picture, m); ObjectCount.count(m, at.first, at.second, size, sensitivity) } finally { m.release() }
        }
        marks = result.centres; if (size == null) size = result.size; working = false
    }
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
                        val near = marks.indexOfFirst { (mx, my) -> hypot(((mx - x) * picture.width).toDouble(), ((my - y) * picture.height).toDouble()) < (size ?: .05f) * short / 2 }
                        marks = if (near >= 0) marks.filterIndexed { i, _ -> i != near } else marks + (x to y)
                    }
                }) {
                    val radius = ((size ?: .05f) * short / 2 * scale).coerceAtLeast(6f)
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
                    ToolSlider(R.string.count_size, size ?: .05f, .01f.. .5f, "count-size") { size = it }
                    ToolSlider(R.string.count_sensitivity, sensitivity, .1f.. .8f, "count-sensitivity") { sensitivity = it }
                }
                Row {
                    TextButton({ example = null; marks = emptyList(); size = null }, enabled = example != null) { Text(stringResource(R.string.count_again)) }
                    Spacer(Modifier.weight(1f))
                    Button({ onSave(numbered(picture, marks, size ?: .05f), marks.size) }, enabled = marks.isNotEmpty() && !busy && !working, modifier = Modifier.testTag("count-save")) {
                        Text(stringResource(R.string.count_save))
                    }
                }
            }
        }
    }
}

/** The picture with each mark numbered, as a JPEG. */
private fun numbered(picture: Bitmap, marks: List<Pair<Float, Float>>, size: Float): ByteArray {
    val out = picture.copy(Bitmap.Config.ARGB_8888, true)
    try {
        val canvas = Canvas(out); val short = min(out.width, out.height).toFloat(); val r = (size * short / 2 * .45f).coerceAtLeast(10f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(220, 0, 182, 157) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; textSize = r * 1.1f; isFakeBoldText = true }
        marks.forEachIndexed { i, (x, y) ->
            val cx = x * out.width; val cy = y * out.height
            canvas.drawCircle(cx, cy, r, fill); canvas.drawText("${i + 1}", cx, cy - (text.ascent() + text.descent()) / 2, text)
        }
        val total = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = short * .05f; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }
        canvas.drawText("= ${marks.size}", short * .03f, short * .08f, total)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    } finally { out.recycle() }
}
