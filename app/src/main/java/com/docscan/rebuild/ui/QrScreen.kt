package com.docscan.rebuild.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.docscan.rebuild.R
import com.docscan.rebuild.imaging.CodeReader
import com.docscan.rebuild.imaging.ScannedCode
import com.google.zxing.client.result.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reads QR codes and barcodes from the camera or from a picture, on the device. Nothing is sent anywhere. */
@Composable
fun QrScreen(onBack: () -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val main = remember { ContextCompat.getMainExecutor(context) }
    var results by remember { mutableStateOf<List<ScannedCode>>(emptyList()) }
    var noneInImage by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }; var torch by remember { mutableStateOf(false) }
    val paused = remember { AtomicBoolean(false) }
    SideEffect { paused.set(results.isNotEmpty()) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2600) sample *= 2
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    bitmap?.let { try { CodeReader.image(it) } finally { it.recycle() } }.orEmpty()
                }.getOrDefault(emptyList())
            }
            if (found.isEmpty()) noneInImage = true else results = found
        }
    }
    BackHandler { if (results.isNotEmpty()) results = emptyList() else onBack() }
    DisposableEffect(allowed, lifecycle) {
        var disposed = false; var provider: ProcessCameraProvider? = null
        val executor = Executors.newSingleThreadExecutor()
        var cases: Array<UseCase> = emptyArray()
        if (allowed) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    provider = future.get()
                    val view = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    var last = 0L
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (!paused.get() && now - last >= 120) {
                                last = now
                                val plane = image.planes[0]; val buffer = plane.buffer
                                val luma = ByteArray(plane.rowStride * image.height); buffer.rewind(); buffer.get(luma, 0, minOf(buffer.remaining(), luma.size))
                                CodeReader.frame(luma, plane.rowStride, image.width, image.height)?.let { code ->
                                    paused.set(true); main.execute { if (!disposed) results = listOf(code) }
                                }
                            }
                        } catch (_: Exception) { } finally { image.close() }
                    }
                    cases = arrayOf(view, analysis)
                    camera = provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, view, analysis)
                } catch (_: Exception) { }
            }, main)
        }
        onDispose { disposed = true; if (cases.isNotEmpty()) provider?.unbind(*cases); executor.shutdown(); camera = null }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("qr-screen")) {
        if (allowed) AndroidView({ preview }, Modifier.fillMaxSize())
        else Text(stringResource(R.string.camera_permission), Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
        // Viewfinder: where to hold the code.
        Box(Modifier.align(Alignment.Center).size(250.dp).border(3.dp, ScanMint, RoundedCornerShape(18.dp)))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Outlined.Close, stringResource(R.string.back), tint = Color.White) }
            Text(stringResource(R.string.qr_scan), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconButton({ torch = !torch; camera?.cameraControl?.enableTorch(torch) }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) {
                Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, stringResource(R.string.flash), tint = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.qr_hint), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton({ picker.launch("image/*") }, modifier = Modifier.testTag("qr-from-image")) {
                Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.qr_from_image))
            }
        }
    }
    if (noneInImage) AlertDialog(onDismissRequest = { noneInImage = false }, text = { Text(stringResource(R.string.qr_none)) },
        confirmButton = { TextButton({ noneInImage = false }) { Text(stringResource(R.string.ok)) } })
    if (results.isNotEmpty()) CodeResults(results) { results = emptyList() }
}

@Composable
private fun CodeResults(codes: List<ScannedCode>, onAgain: () -> Unit) {
    val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    fun open(intent: Intent) = try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
    AlertDialog(onDismissRequest = onAgain, title = { Text(stringResource(R.string.qr_found)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            codes.forEach { code ->
                val parsed = code.parsed
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(kindLabel(code)) + " · " + code.format.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(code.display, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("qr-text")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val action: Intent? = when (parsed) {
                            is URIParsedResult -> Intent(Intent.ACTION_VIEW, Uri.parse(parsed.getURI()))
                            is TelParsedResult -> Intent(Intent.ACTION_DIAL, Uri.parse(parsed.getTelURI()))
                            is EmailAddressParsedResult -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + parsed.tos.orEmpty().joinToString(","))).apply {
                                parsed.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; parsed.body?.let { putExtra(Intent.EXTRA_TEXT, it) } }
                            is SMSParsedResult -> Intent(Intent.ACTION_SENDTO, Uri.parse(parsed.getSMSURI()))
                            is GeoParsedResult -> Intent(Intent.ACTION_VIEW, Uri.parse(parsed.getGeoURI()))
                            else -> null
                        }
                        if (action != null) Button({ open(action) }) { Text(stringResource(R.string.qr_open)) }
                        OutlinedButton({ clipboard.setText(AnnotatedString(if (parsed is WifiParsedResult) parsed.password.orEmpty() else code.text)) }) {
                            Text(stringResource(if (parsed is WifiParsedResult) R.string.qr_copy_password else R.string.copy))
                        }
                        OutlinedButton({ open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.text), null)) }) { Text(stringResource(R.string.share)) }
                    }
                }
            }
        } },
        confirmButton = { TextButton(onAgain, modifier = Modifier.testTag("qr-again")) { Text(stringResource(R.string.qr_again)) } })
}

private fun kindLabel(code: ScannedCode) = when (code.kind) {
    ParsedResultType.URI -> R.string.qr_kind_link
    ParsedResultType.WIFI -> R.string.qr_kind_wifi
    ParsedResultType.TEL -> R.string.qr_kind_phone
    ParsedResultType.EMAIL_ADDRESS -> R.string.qr_kind_email
    ParsedResultType.SMS -> R.string.qr_kind_sms
    ParsedResultType.GEO -> R.string.qr_kind_location
    ParsedResultType.ADDRESSBOOK -> R.string.qr_kind_contact
    ParsedResultType.PRODUCT, ParsedResultType.ISBN -> R.string.qr_kind_product
    else -> R.string.qr_kind_text
}
