package com.docscan.rebuild.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.docscan.rebuild.R
import com.docscan.rebuild.imaging.IdPhoto
import com.docscan.rebuild.imaging.ImagePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val idPhotoSizes = listOf(IdPhoto.Size.MM35X45 to R.string.idphoto_35x45, IdPhoto.Size.IN2X2 to R.string.idphoto_2x2, IdPhoto.Size.MM30X40 to R.string.idphoto_30x40, IdPhoto.Size.MM40X50 to R.string.idphoto_40x50)
val idPhotoBackdrops = listOf(IdPhoto.Backdrop.KEEP to R.string.idphoto_keep, IdPhoto.Backdrop.WHITE to R.string.idphoto_white, IdPhoto.Backdrop.BLUE to R.string.idphoto_blue, IdPhoto.Backdrop.GREY to R.string.idphoto_grey)

/**
 * ID photo maker: take or pick a portrait, frame the face in the guide at an official size, choose a plain
 * background, and get a document with print sheets (A4 and 10 x 15 cm) at real size and the single photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdPhotoScreen(busy: Boolean, onBack: () -> Unit, onCreate: (File, RectF, IdPhoto.Size, IdPhoto.Backdrop) -> Unit) {
    val context = LocalContext.current
    val folder = remember { File(context.cacheDir, "exports").apply { mkdirs() } }
    val photoFile = remember { File(folder, "idphoto-source.jpg") }
    var version by rememberSaveable { mutableIntStateOf(if (photoFile.isFile) 1 else 0) }
    var size by rememberSaveable { mutableStateOf(IdPhoto.Size.MM35X45) }
    var backdrop by rememberSaveable { mutableStateOf(IdPhoto.Backdrop.KEEP) }
    // The frame: its centre and its width as a share of the photo's width; the height follows the size's proportions.
    var cx by rememberSaveable { mutableFloatStateOf(.5f) }; var cy by rememberSaveable { mutableFloatStateOf(.42f) }; var cw by rememberSaveable { mutableFloatStateOf(.6f) }
    val bitmap by produceState<Bitmap?>(null, version) {
        value = if (version == 0 || !photoFile.isFile) null else withContext(Dispatchers.IO) { runCatching { ImagePipeline.decode(photoFile, 1600) }.getOrNull() }
    }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", File(folder, "idphoto-camera.jpg")) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val taken = File(folder, "idphoto-camera.jpg")
        if (ok && taken.isFile && taken.length() > 0) { taken.copyTo(photoFile, overwrite = true); taken.delete(); cx = .5f; cy = .42f; cw = .6f; version++ }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) camera.launch(cameraUri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> photoFile.outputStream().use { input.copyTo(it) } }
            cx = .5f; cy = .42f; cw = .6f; version++
        }
    }
    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(cameraUri)
        else permission.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onBack)
    val image = bitmap
    // Frame height as a share of the photo height, from the size's proportions and the photo's.
    fun frameHeight(width: Float, photo: Bitmap) = width * photo.width / (size.aspect * photo.height)
    fun clamp(photo: Bitmap) {
        cw = cw.coerceIn(.15f, 1f)
        var h = frameHeight(cw, photo); if (h > 1f) { cw /= h; h = 1f }
        cx = cx.coerceIn(cw / 2, 1f - cw / 2); cy = cy.coerceIn(h / 2, 1f - h / 2)
    }
    Scaffold(modifier = Modifier.testTag("idphoto-screen"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.idphoto)) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(::takePhoto, enabled = !busy, modifier = Modifier.testTag("idphoto-camera")) { Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.idphoto_take)) }
                OutlinedButton({ gallery.launch("image/*") }, enabled = !busy, modifier = Modifier.testTag("idphoto-gallery")) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.idphoto_pick)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (image == null) Text(stringResource(R.string.idphoto_hint), Modifier.padding(16.dp))
                else {
                    var area by remember { mutableStateOf(IntSize.Zero) }
                    val shown = remember(image) { image.asImageBitmap() }
                    LaunchedEffect(image, size) { clamp(image) }
                    Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
                        Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        val rect = fitImageRect(Size(area.width.toFloat(), area.height.toFloat()), image.width.toFloat() / image.height)
                        val latest by rememberUpdatedState(rect)
                        Canvas(Modifier.fillMaxSize().testTag("idphoto-frame").pointerInput(image) {
                            detectDragGestures { change, drag -> change.consume(); cx += drag.x / latest.width; cy += drag.y / latest.height; clamp(image) }
                        }) {
                            val h = frameHeight(cw, image)
                            val left = rect.left + (cx - cw / 2) * rect.width; val top = rect.top + (cy - h / 2) * rect.height
                            val w = cw * rect.width; val fh = h * rect.height
                            val shade = Color.Black.copy(alpha = .45f)
                            drawRect(shade, Offset(rect.left, rect.top), Size(rect.width, top - rect.top))
                            drawRect(shade, Offset(rect.left, top + fh), Size(rect.width, rect.bottom - top - fh))
                            drawRect(shade, Offset(rect.left, top), Size(left - rect.left, fh))
                            drawRect(shade, Offset(left + w, top), Size(rect.right - left - w, fh))
                            drawRect(ScanMint, Offset(left, top), Size(w, fh), style = Stroke(2.dp.toPx()))
                            // Head guide: the face should fill this oval, eyes about at its middle.
                            drawOval(Color.White.copy(alpha = .8f), Offset(left + w * .22f, top + fh * .1f), Size(w * .56f, fh * .62f), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
            if (image != null) {
                Text(stringResource(R.string.idphoto_frame_hint), style = MaterialTheme.typography.bodySmall)
                Slider(cw, { cw = it; clamp(image) }, valueRange = .15f..1f, modifier = Modifier.testTag("idphoto-zoom"))
            }
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MarkCaption(R.string.idphoto_size)
                PlaceChips(idPhotoSizes, size, { size = it }, tag = "idphoto-size")
                MarkCaption(R.string.idphoto_background)
                PlaceChips(idPhotoBackdrops, backdrop, { backdrop = it }, tag = "idphoto-backdrop")
                if (backdrop != IdPhoto.Backdrop.KEEP) Text(stringResource(R.string.idphoto_background_hint), style = MaterialTheme.typography.bodySmall)
            }
            Button({
                val photo = image ?: return@Button
                val h = frameHeight(cw, photo)
                onCreate(photoFile, RectF(cx - cw / 2, cy - h / 2, cx + cw / 2, cy + h / 2), size, backdrop)
            }, enabled = image != null && !busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("idphoto-create")) {
                Text(stringResource(R.string.idphoto_create))
            }
        }
    }
}
