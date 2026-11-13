package com.docscan.rebuild.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import androidx.core.view.WindowCompat
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.imaging.CaptureQuality
import com.docscan.rebuild.model.*
import org.opencv.core.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private data class CameraObservation(val frame: Frame?, val aspect: Float, val progress: Float, val signature: List<Float>, val trigger: Boolean)

@Composable
fun CaptureScreen(store: ScanStore, pages: List<Sheet>, busy: Boolean, snack: SnackbarHostState, onDone: () -> Unit, onImport: () -> Unit, onCaptured: (File, Boolean, Boolean, Frame?, (Boolean) -> Unit) -> Unit, prompt: Int? = null) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val gate = remember { CaptureStability() }; val capturing = remember { AtomicBoolean(false) }; val autoFlag = remember { AtomicBoolean(true) }
    var automatic by rememberSaveable { mutableStateOf(true) }; SideEffect { autoFlag.set(automatic) }
    var batch by rememberSaveable { mutableStateOf(true) }
    var detectBounds by rememberSaveable { mutableStateOf(true) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }; var camera by remember { mutableStateOf<Camera?>(null) }
    var observation by remember { mutableStateOf(CameraObservation(null, .75f, 0f, emptyList(), false)) }
    var streaming by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }; var error by remember { mutableStateOf(false) }; var torch by remember { mutableStateOf(false) }
    val latestCaptured by rememberUpdatedState(onCaptured)
    fun shoot() {
        val imageCapture = capture ?: return
        if (busy || !capturing.compareAndSet(false, true)) return
        taking = true; gate.markCaptured(observation.signature)
        val cropCaptured = detectBounds; val batchCaptured = batch; val seen = observation.frame
        val file = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                latestCaptured(file, cropCaptured, batchCaptured, seen) { success -> taking = false; capturing.set(false); if (!success) gate.failed() }
            }
            override fun onError(exception: ImageCaptureException) { file.delete(); taking = false; capturing.set(false); gate.failed(); error = true }
        })
    }
    val latestShoot by rememberUpdatedState({ shoot() })
    BackHandler { if (!taking && !busy) onDone() }
    DisposableEffect(allowed, lifecycle, preview) {
        var disposed = false; var provider: ProcessCameraProvider? = null
        val streamObserver = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        preview.previewStreamState.observe(lifecycle, streamObserver)
        val executor = Executors.newSingleThreadExecutor()
        var previewUseCase: Preview? = null; var photoUseCase: ImageCapture? = null; var analysisUseCase: ImageAnalysis? = null
        if (allowed) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    provider = future.get()
                    val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                    val view = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build().also { it.surfaceProvider = preview.surfaceProvider }
                    val photo = ImageCapture.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    val analysis = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    previewUseCase = view; photoUseCase = photo; analysisUseCase = analysis
                    var lastAnalysis = 0L
                    val tracker = BoundaryTracker()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (!capturing.get() && now - lastAnalysis >= 180) {
                                lastAnalysis = now
                                val result = inspectFrame(image, gate, tracker, autoFlag.get(), now)
                                main.execute { if (!disposed) { observation = result; if (result.trigger && autoFlag.get()) latestShoot() } }
                            }
                        } catch (e: Exception) { main.execute { if (!disposed) error = true } }
                        finally { image.close() }
                    }
                    camera = provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, view, photo, analysis)
                    capture = photo
                    preview.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(event.x, event.y)).build())
                            preview.performClick()
                        }
                        true
                    }
                } catch (e: Exception) { error = true }
            }, main)
        }
        onDispose {
            disposed = true; preview.previewStreamState.removeObserver(streamObserver); streaming = false; analysisUseCase?.clearAnalyzer()
            val cases = listOfNotNull(previewUseCase, photoUseCase, analysisUseCase).toTypedArray()
            if (cases.isNotEmpty()) provider?.unbind(*cases)
            executor.shutdown(); capture = null
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!taking && !busy) onDone() }) { Icon(Icons.Outlined.Close, stringResource(R.string.back), tint = Color.White) }
            Text("HD", Modifier.border(1.dp, Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { detectBounds = !detectBounds; if (!detectBounds) { automatic = false; autoFlag.set(false) }; gate.failed() }) { Icon(Icons.Outlined.DocumentScanner, stringResource(R.string.detect_bounds), tint = if (detectBounds) ScanMint else Color.White) }
            IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) { Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, stringResource(R.string.flash), tint = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (allowed) {
                AndroidView({ preview }, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val rect = fitImageRect(size, observation.aspect)
                    observation.frame?.takeIf { detectBounds }?.let { frame ->
                        val points = frame.points.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                        val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                        drawPath(path, ScanMint.copy(alpha = .12f))
                        drawPath(path, ScanMint, style = Stroke(2.dp.toPx()))
                        points.forEach { drawCircle(ScanMint, 4.dp.toPx(), it) }
                    }
                }
                if ((capture == null || !streaming) && !error) Text(stringResource(R.string.camera_starting), color = Color.White)
            } else Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_permission), color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.grant_camera)) }
            }
            if (error) Text(stringResource(R.string.camera_error), Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = .8f)).padding(12.dp), color = Color.White)
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).background(Color(0xFF292B2C), RoundedCornerShape(24.dp)).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !batch, onClick = { batch = false }, enabled = !taking && !busy, label = { Text(stringResource(R.string.capture_single)) }, modifier = Modifier.testTag("capture-single"), shape = RoundedCornerShape(20.dp))
            FilterChip(selected = batch, onClick = { batch = true }, enabled = !taking && !busy, label = { Text(stringResource(R.string.capture_batch)) }, modifier = Modifier.testTag("capture-batch"), shape = RoundedCornerShape(20.dp))
        }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        LinearProgressIndicator(progress = { observation.progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = ScanMint, trackColor = Color(0xFF252727))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            FilterChip(selected = detectBounds, onClick = { detectBounds = !detectBounds; if (!detectBounds) { automatic = false; autoFlag.set(false) }; gate.failed() },
                label = { Text(stringResource(if (detectBounds) R.string.detect_bounds else R.string.full_photo)) },
                colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedLabelColor = Color.White, selectedContainerColor = Color(0xFF3B4F4C)))
            // Auto capture: a labelled switch (the bare checkbox it replaced had a label that was invisible on black).
            Row(Modifier.testTag("auto-capture").toggleable(automatic, role = Role.Switch, onValueChange = { automatic = it; autoFlag.set(it); if (it) detectBounds = true; gate.failed() })
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auto_capture), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Switch(automatic, null, Modifier.scale(.8f), colors = SwitchDefaults.colors(checkedTrackColor = ScanMint, uncheckedThumbColor = Color.LightGray, uncheckedTrackColor = Color(0xFF3A3C3D), uncheckedBorderColor = Color.Gray))
            }
        }
        // A guided capture (the ID card tool) says which side to photograph next.
        if (prompt != null) Text(stringResource(prompt), Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).testTag("capture-prompt"), style = MaterialTheme.typography.titleMedium, color = ScanMint)
        Text(stringResource(if (observation.frame == null) R.string.find_page else if (observation.progress >= 1f) R.string.page_saved else R.string.hold_still), Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onImport, enabled = !taking && !busy) { Icon(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.import_images), Modifier.size(28.dp)) }
                Text(stringResource(R.string.import_images), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            FilledIconButton(onClick = { shoot() }, enabled = capture != null && streaming && !taking && !busy,
                modifier = Modifier.size(78.dp).border(4.dp, ScanMint, CircleShape).padding(8.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(Icons.Outlined.CameraAlt, stringResource(R.string.capture), Modifier.size(30.dp))
            }
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (pages.isNotEmpty()) PagePicture(store, pages.last(), Modifier.size(30.dp, 38.dp).background(Color.White), edge = 120)
                Text(pluralStringResource(R.plurals.saved_count, pages.size, pages.size), style = MaterialTheme.typography.labelSmall)
                TextButton(onDone, enabled = !taking && !busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

private fun inspectFrame(image: ImageProxy, gate: CaptureStability, tracker: BoundaryTracker, automatic: Boolean, now: Long): CameraObservation {
    ImagePipeline.ensureAvailable()
    val plane = image.planes[0]; val bytes = plane.buffer.duplicate(); val step = max(1, image.width / 720)
    val width = (image.width + step - 1) / step; val height = (image.height + step - 1) / step
    val pixels = ByteArray(width * height)
    val colours = ByteArray(width * height * 4)
    val u = image.planes[1]; val v = image.planes[2]
    val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
    for (y in 0 until height) for (x in 0 until width) {
        val px = x * step; val py = y * step; val index = y * width + x
        val luminance = bytes.get(bytes.position() + py * plane.rowStride + px * plane.pixelStride)
        pixels[index] = luminance
        val uu = (ub.get(ub.position() + (py / 2) * u.rowStride + (px / 2) * u.pixelStride).toInt() and 255) - 128
        val vv = (vb.get(vb.position() + (py / 2) * v.rowStride + (px / 2) * v.pixelStride).toInt() and 255) - 128
        val yy = luminance.toInt() and 255
        colours[index * 4] = (yy + 1.402 * vv).toInt().coerceIn(0,255).toByte()
        colours[index * 4 + 1] = (yy - .344136 * uu - .714136 * vv).toInt().coerceIn(0,255).toByte()
        colours[index * 4 + 2] = (yy + 1.772 * uu).toInt().coerceIn(0,255).toByte()
        colours[index * 4 + 3] = 255.toByte()
    }
    val gray = Mat(height, width, CvType.CV_8UC1); gray.put(0, 0, pixels)
    val rgba = Mat(height, width, CvType.CV_8UC4); rgba.put(0, 0, colours)
    try {
        val frame = tracker.sample(ImagePipeline.detectGray(gray, rgba))
        val sharp = frame != null && CaptureQuality.sharp(gray, rgba)
        val signature = if (frame == null) emptyList() else CaptureQuality.signature(rgba, frame)
        val decision = if (automatic && (sharp || frame == null)) gate.sample(frame, signature, now) else { gate.unstable(); CaptureStability.Decision(0f, false) }
        val rotation = image.imageInfo.rotationDegrees
        return CameraObservation(frame?.turned(rotation), if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width, decision.progress, signature, decision.capture)
    } finally { gray.release(); rgba.release() }
}

