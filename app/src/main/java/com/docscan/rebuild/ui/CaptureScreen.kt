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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import com.docscan.rebuild.model.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private data class CameraObservation(val frame: Frame?, val aspect: Float, val progress: Float, val signature: List<Float>, val trigger: Boolean)

@Composable
fun CaptureScreen(store: ScanStore, pages: List<Sheet>, busy: Boolean, snack: SnackbarHostState, onDone: () -> Unit, onCaptured: (File, (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val gate = remember { CaptureStability() }; val capturing = remember { AtomicBoolean(false) }; val autoFlag = remember { AtomicBoolean(true) }
    var automatic by rememberSaveable { mutableStateOf(true) }; SideEffect { autoFlag.set(automatic) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }; var camera by remember { mutableStateOf<Camera?>(null) }
    var observation by remember { mutableStateOf(CameraObservation(null, .75f, 0f, emptyList(), false)) }
    var streaming by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }; var error by remember { mutableStateOf(false) }; var torch by remember { mutableStateOf(false) }
    val latestCaptured by rememberUpdatedState(onCaptured)
    fun shoot() {
        val imageCapture = capture ?: return
        if (busy || !capturing.compareAndSet(false, true)) return
        taking = true; gate.markCaptured(observation.signature)
        val file = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                latestCaptured(file) { success -> taking = false; capturing.set(false); if (!success) gate.failed() }
            }
            override fun onError(exception: ImageCaptureException) { file.delete(); taking = false; capturing.set(false); gate.failed(); error = true }
        })
    }
    val latestShoot by rememberUpdatedState({ shoot() })
    BackHandler { if (!taking && !busy) onDone() }
    DisposableEffect(context) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
        }
    }
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
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (!capturing.get() && now - lastAnalysis >= 180) {
                                lastAnalysis = now
                                val result = inspectFrame(image, gate, autoFlag.get(), now)
                                main.execute { if (!disposed) { observation = result; if (result.trigger) latestShoot() } }
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
    Column(Modifier.fillMaxSize().background(Color(0xFF101717)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!taking && !busy) onDone() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            Text(stringResource(R.string.camera), Modifier.weight(1f), color = Color.White)
            IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) { Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, stringResource(R.string.flash), tint = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (allowed) {
                AndroidView({ preview }, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val rect = fitImageRect(size, observation.aspect)
                    observation.frame?.let { frame ->
                        val points = frame.points.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                        val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                        drawPath(path, Color(0xFF00DBBE), style = Stroke(2.dp.toPx()))
                        points.forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
                    }
                }
                if ((capture == null || !streaming) && !error) Text(stringResource(R.string.camera_starting), color = Color.White)
            } else Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_permission), color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.grant_camera)) }
            }
            if (error) Text(stringResource(R.string.camera_error), Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = .8f)).padding(12.dp), color = Color.White)
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        LinearProgressIndicator(progress = { observation.progress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF00DBBE))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(automatic, { automatic = it; gate.failed() }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = Color.White))
            Text(stringResource(R.string.auto_capture), color = Color.White)
            Spacer(Modifier.weight(1f)); Text(pluralStringResource(R.plurals.saved_count, pages.size, pages.size), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.height(62.dp)) {
            items(pages.takeLast(12), key = { it.id }) { page -> PagePicture(store, page, Modifier.size(44.dp, 60.dp).background(Color.White), edge = 120) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (observation.frame == null) R.string.find_page else if (observation.progress >= 1f) R.string.page_saved else R.string.hold_still), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelSmall)
            FilledIconButton(onClick = { shoot() }, enabled = capture != null && streaming && !taking && !busy, modifier = Modifier.size(70.dp)) { Icon(Icons.Outlined.CameraAlt, stringResource(R.string.capture), Modifier.size(34.dp)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Button(onClick = onDone, enabled = !taking && !busy) { Text(stringResource(R.string.done)) } }
        }
    }
}

private fun inspectFrame(image: ImageProxy, gate: CaptureStability, automatic: Boolean, now: Long): CameraObservation {
    ImagePipeline.ensureAvailable()
    val plane = image.planes[0]; val bytes = plane.buffer.duplicate(); val step = max(1, image.width / 720)
    val width = (image.width + step - 1) / step; val height = (image.height + step - 1) / step
    val pixels = ByteArray(width * height)
    for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = bytes.get(y * step * plane.rowStride + x * step * plane.pixelStride)
    val gray = Mat(height, width, CvType.CV_8UC1); gray.put(0, 0, pixels)
    val laplace = Mat(); val mean = MatOfDouble(); val deviation = MatOfDouble()
    try {
        val frame = ImagePipeline.detectGray(gray)
        Imgproc.Laplacian(gray, laplace, CvType.CV_64F); Core.meanStdDev(laplace, mean, deviation)
        val sharp = deviation.toArray().firstOrNull()?.let { it * it >= 45 } ?: false
        val signature = if (frame == null) emptyList() else fingerprint(gray, frame)
        val decision = if (automatic && (sharp || frame == null)) gate.sample(frame, signature, now) else { gate.unstable(); CaptureStability.Decision(0f, false) }
        val rotation = image.imageInfo.rotationDegrees
        return CameraObservation(frame?.turned(rotation), if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width, decision.progress, signature, decision.capture)
    } finally { gray.release(); laplace.release(); mean.release(); deviation.release() }
}

private fun fingerprint(gray: Mat, frame: Frame): List<Float> {
    val from = MatOfPoint2f(*frame.points.map { Point(it.x * (gray.cols() - 1).toDouble(), it.y * (gray.rows() - 1).toDouble()) }.toTypedArray())
    val to = MatOfPoint2f(Point(0.0, 0.0), Point(63.0, 0.0), Point(63.0, 63.0), Point(0.0, 63.0))
    val transform = Imgproc.getPerspectiveTransform(from, to); val small = Mat()
    try {
        Imgproc.warpPerspective(gray, small, transform, Size(64.0, 64.0))
        return (0 until 64).map { cell -> val region = small.submat(Rect((cell % 8) * 8, (cell / 8) * 8, 8, 8)); try { Core.mean(region).`val`[0].toFloat() } finally { region.release() } }
    } finally { from.release(); to.release(); transform.release(); small.release() }
}
