@file:androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)

package com.docscan.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.docscan.android.R
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.processing.AutoCaptureGate
import com.docscan.android.processing.DetectionMode
import com.docscan.android.processing.CaptureDecision
import com.docscan.android.processing.CaptureStatus
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(
    pages: List<PageEntity>, repository: DocumentRepository, gate: AutoCaptureGate,
    busy: Boolean, manualOnly: Boolean, onDone: () -> Unit, onReview: (String) -> Unit,
    onCapture: (File, DetectionMode, (Boolean) -> Unit) -> Unit, onFailure: () -> Unit,
    onBusyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LifecycleResumeEffect(Unit) {
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppIcon(R.drawable.ic_camera, null, Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.permission_body), Modifier.padding(vertical = 16.dp))
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.allow_camera)) }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) {
                Text(stringResource(R.string.open_settings))
            }
        }
        return
    }
    val preferences = remember(context) { context.getSharedPreferences("scan-preferences", android.content.Context.MODE_PRIVATE) }
    var automatic by rememberSaveable { mutableStateOf(!manualOnly && preferences.getBoolean("automatic", true)) }
    var highQuality by rememberSaveable { mutableStateOf(manualOnly || preferences.getBoolean("high-quality", false)) }
    var angledDistance by rememberSaveable { mutableStateOf(preferences.getBoolean("angled-distance", false)) }
    val detectionMode by rememberUpdatedState(if (angledDistance) DetectionMode.ANGLED_DISTANCE else DetectionMode.STANDARD)
    var options by remember { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val controller = remember(highQuality, retry) { LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        imageCaptureMode = if (highQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    } }
    val previewView = remember(controller) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        this.controller = controller
    } }
    val thumbnails = rememberLazyListState()
    LaunchedEffect(pages.size) { if (pages.isNotEmpty()) thumbnails.animateScrollToItem(pages.lastIndex) }
    var cameraReady by remember(controller) { mutableStateOf(false) }
    var unavailable by remember(controller) { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val busyChanged by rememberUpdatedState(onBusyChanged)
    LaunchedEffect(capturing) { busyChanged(capturing) }
    DisposableEffect(Unit) { onDispose { busyChanged(false) } }
    var torch by remember(controller) { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    var decision by remember { mutableStateOf(CaptureDecision(CaptureStatus.SEARCHING)) }
    var outline by remember { mutableStateOf(emptyList<Offset>()) }
    var lastFrame by remember { mutableLongStateOf(0) }
    val feedback = LocalHapticFeedback.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    LaunchedEffect(controller) {
        delay(12_000)
        if (!cameraReady) unavailable = true
    }

    LifecycleResumeEffect(Unit) {
        resumed = true; gate.interrupt()
        onPauseOrDispose { resumed = false; gate.interrupt(); outline = emptyList() }
    }
    LaunchedEffect(automatic, paused, angledDistance) { gate.interrupt(); decision = CaptureDecision(CaptureStatus.SEARCHING) }
    LaunchedEffect(controller) {
        while (true) {
            delay(600)
            if (SystemClock.elapsedRealtime() - lastFrame > 750) {
                gate.interrupt(); outline = emptyList(); decision = CaptureDecision(CaptureStatus.SEARCHING)
            }
        }
    }

    fun capturePage() {
        if (!cameraReady || busy || capturing || unavailable || !resumed) return
        val captureMode = detectionMode
        capturing = true; gate.markCaptured()
        val file = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
        fun failed() {
            file.delete(); capturing = false; paused = true
            gate.reset(); decision = CaptureDecision(CaptureStatus.PAUSED)
            onFailure()
        }
        try {
            controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onCapture(file, captureMode) { saved ->
                        capturing = false
                        if (saved) {
                            decision = CaptureDecision(CaptureStatus.WAIT_FOR_NEXT)
                            if (resumed) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else { paused = true; gate.reset() }
                    }
                }
                override fun onError(exception: ImageCaptureException) { failed() }
            })
        } catch (_: Exception) { failed() }
    }
    val currentFrameHandler by rememberUpdatedState<(CameraFrame) -> Unit> { frame ->
        val now = SystemClock.elapsedRealtime()
        if (now - frame.timestamp <= 700 && resumed) {
            lastFrame = now
            outline = frame.quad?.let { quad ->
                previewView.outputTransform?.let { target ->
                    val points = quad.points.flatMap { listOf(it.x * frame.width, it.y * frame.height) }.toFloatArray()
                    try {
                        CoordinateTransform(frame.transform, target).mapPoints(points)
                        points.toList().chunked(2).map { Offset(it[0], it[1]) }
                    } catch (_: IllegalArgumentException) { null }
                }
            }.orEmpty()
            decision = gate.update(frame.quad, now, blocked = !automatic || paused || capturing || busy || !cameraReady, fingerprint = frame.fingerprint)
            if (decision.capture) capturePage()
        }
    }
    DisposableEffect(controller, lifecycle) {
        val active = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val observer = Observer<PreviewView.StreamState> { cameraReady = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(lifecycle, observer)
        controller.setImageAnalysisAnalyzer(executor, CameraFrameAnalyzer(detectionMode = { detectionMode }) { frame ->
            mainExecutor.execute { if (active.get()) currentFrameHandler(frame) }
        })
        try { controller.bindToLifecycle(lifecycle) } catch (_: Exception) { unavailable = true }
        controller.initializationFuture.addListener({
            if (active.get()) try { controller.initializationFuture.get() } catch (_: Exception) { unavailable = true }
        }, mainExecutor)
        onDispose {
            active.set(false); cameraReady = false
            previewView.previewStreamState.removeObserver(observer)
            controller.clearImageAnalysisAnalyzer(); controller.unbind(); executor.shutdown()
        }
    }
    val statusText = when {
        capturing || busy -> R.string.scan_saving
        !cameraReady && !unavailable -> R.string.camera_starting
        !automatic -> R.string.camera_hint
        paused -> R.string.scan_paused
        decision.status == CaptureStatus.WAIT_FOR_NEXT -> R.string.scan_replace_page
        decision.status == CaptureStatus.HOLD_STEADY -> R.string.scan_hold_steady
        else -> R.string.scan_find_page
    }
    val fastLabel = stringResource(R.string.fast_scan)
    Column(Modifier.fillMaxSize().testTag("camera-screen").padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.capture_automatic), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(automatic, modifier = Modifier.semantics { contentDescription = fastLabel }, enabled = !busy && !capturing && !manualOnly,
                onCheckedChange = { automatic = it; paused = false; preferences.edit { putBoolean("automatic", it) } })
            Box {
                IconButton(enabled = !busy && !capturing, onClick = { options = true }) { AppIcon(R.drawable.ic_more, stringResource(R.string.more_options)) }
                DropdownMenu(options, onDismissRequest = { options = false }) {
                    val angleLabel = stringResource(R.string.angle_distance_detection)
                    DropdownMenuItem(text = { Text(angleLabel) }, trailingIcon = {
                        Checkbox(angledDistance, modifier = Modifier.semantics { contentDescription = angleLabel }, onCheckedChange = {
                            angledDistance = it; preferences.edit { putBoolean("angled-distance", it) }; options = false
                        })
                    }, onClick = {
                        angledDistance = !angledDistance; preferences.edit { putBoolean("angled-distance", angledDistance) }; options = false
                    })
                    Text(stringResource(R.string.angle_distance_hint), Modifier.widthIn(max = 260.dp).padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.capture_quality_hint), Modifier.widthIn(max = 260.dp).padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.page_count, pages.size), style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.capture_quality), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            FilterChip(!highQuality, onClick = { highQuality = false; preferences.edit { putBoolean("high-quality", false) } },
                enabled = !busy && !capturing, label = { Text(stringResource(R.string.capture_fast)) }, modifier = Modifier.testTag("quality-fast"))
            FilterChip(highQuality, onClick = { highQuality = true; preferences.edit { putBoolean("high-quality", true) } },
                enabled = !busy && !capturing, label = { Text(stringResource(R.string.capture_high)) },
                modifier = Modifier.testTag("quality-high").semantics { contentDescription = context.getString(R.string.high_quality_capture) })
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("camera-preview").clip(DesignCorner).background(Color.Black), contentAlignment = Alignment.Center) {
            // AndroidView.factory is invoked once for a composed node. Replacing only
            // the remembered PreviewView left the old surface on screen after a quality
            // switch. Key the native host to the actual controller/view being observed.
            if (!unavailable) key(controller) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_unavailable), color = Color.White)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.camera_retry)) }
            }
            Canvas(Modifier.matchParentSize()) {
                if (outline.size == 4) {
                    val path = Path().apply {
                        moveTo(outline[0].x, outline[0].y)
                        outline.drop(1).forEach { lineTo(it.x, it.y) }; close()
                    }
                    drawPath(path, if (DesignConfig.fixedTabs) Color(0xFFFF5544) else Color(0xFF82A8FF), style = Stroke(3.dp.toPx()))
                    outline.forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
                }
            }
            if (angledDistance) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), color = Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.angle_distance_detection), Modifier.padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(statusText), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    if (automatic && !paused && decision.status == CaptureStatus.HOLD_STEADY) {
                        LinearProgressIndicator(progress = { decision.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
        if (pages.isNotEmpty() && !manualOnly) LazyRow(state = thumbnails, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                Column(Modifier.width(58.dp).clickable(enabled = !busy && !capturing) { onReview(page.id) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    PagePreview(page, repository, Modifier.fillMaxWidth().height(56.dp), edge = 160)
                    Text(stringResource(R.string.page_label, index + 1), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (automatic) R.string.auto_scan_hint else R.string.manual_scan_hint), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (automatic) TextButton(enabled = !busy && !capturing, onClick = { paused = !paused }) {
                Text(stringResource(if (paused) R.string.resume_scan else R.string.pause_scan))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(enabled = cameraReady && !busy && !capturing && !unavailable, onClick = {
                val desired = !torch; val future = controller.enableTorch(desired)
                future.addListener({ try { future.get(); torch = desired } catch (_: Exception) { onFailure() } }, mainExecutor)
            }) { AppIcon(R.drawable.ic_flash, stringResource(R.string.flashlight)) }
            Spacer(Modifier.weight(1f))
            Button(enabled = cameraReady && !busy && !capturing && !unavailable && resumed,
                modifier = Modifier.size(68.dp).testTag("capture-button").semantics { contentDescription = context.getString(R.string.capture) },
                shape = CircleShape, contentPadding = PaddingValues(0.dp), onClick = { capturePage() }) {
                if (capturing || busy) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else AppIcon(R.drawable.ic_camera, null, Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(enabled = !busy && !capturing, shape = DesignCorner, onClick = onDone) { Text(stringResource(if (manualOnly) R.string.cancel else R.string.done)) }
        }
    }
}
