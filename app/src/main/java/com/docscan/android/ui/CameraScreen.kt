package com.docscan.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.docscan.android.R
import java.io.File
import java.util.UUID

@Composable
fun CameraScreen(
    pageCount: Int, busy: Boolean, onDone: () -> Unit,
    onCapture: (File, () -> Unit) -> Unit, onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    // Re-check after returning from Android's permission settings.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
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
    val controller = remember { LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
    } }
    var cameraReady by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    DisposableEffect(controller, lifecycle) {
        try { controller.bindToLifecycle(lifecycle); cameraReady = true }
        catch (_: Exception) { unavailable = true }
        onDispose { controller.unbind() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                Text(stringResource(R.string.manual_mode), Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(R.string.page_count, pageCount), style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (!unavailable) AndroidView(
                factory = { PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxSize(),
            ) else Text(stringResource(R.string.camera_unavailable), color = Color.White, modifier = Modifier.padding(24.dp))
            Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.camera_hint), color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
        Text(stringResource(R.string.manual_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(enabled = cameraReady && !busy && !capturing && !unavailable, onClick = {
                val desired = !torch
                val future = controller.enableTorch(desired)
                future.addListener({ try { future.get(); torch = desired } catch (_: Exception) { onFailure() } }, ContextCompat.getMainExecutor(context))
            }) { AppIcon(R.drawable.ic_flash, stringResource(R.string.flashlight)) }
            Button(enabled = cameraReady && !busy && !capturing && !unavailable, modifier = Modifier.weight(1f).height(58.dp), onClick = {
                capturing = true
                val file = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
                try {
                    controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { onCapture(file) { capturing = false } }
                        override fun onError(exception: ImageCaptureException) { file.delete(); capturing = false; onFailure() }
                    })
                } catch (_: Exception) { file.delete(); capturing = false; onFailure() }
            }) {
                if (busy || capturing) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { AppIcon(R.drawable.ic_camera, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.capture)) }
            }
            TextButton(enabled = !busy && !capturing, onClick = onDone) { Text(stringResource(R.string.done)) }
        }
    }
}
