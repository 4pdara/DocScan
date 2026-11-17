package com.docscan.rebuild.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.docscan.rebuild.R
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.model.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Doc Radar (1.3.5): looks through the latest photos in the gallery, on the phone, for ones that show a document
 * (a page outline is found and covers at least a fifth of the photo), and lets them be imported in one go. Reading
 * the gallery needs the photos permission, asked for here; nothing leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocRadarScreen(busy: Boolean, onBack: () -> Unit, onImport: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    // Android 14 and later may give access to chosen photos only; that is enough to look through them.
    val permissions = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun granted() = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted(); asked = true }
    // Coming back from the system settings with access given.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = granted() }
        lifecycle.lifecycle.addObserver(watch); onDispose { lifecycle.lifecycle.removeObserver(watch) }
    }
    val found = remember { mutableStateListOf<Pair<Uri, Bitmap>>() }
    var looked by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<Uri>()) }
    BackHandler(onBack = onBack)
    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        val photos = withContext(Dispatchers.IO) { latestPhotos(context, 300) }
        total = photos.size
        for (uri in photos) {
            if (!isActive) break
            val shown = withContext(Dispatchers.Default) { runCatching { look(context, uri) }.getOrNull() }
            if (shown != null) { found += uri to shown; chosen = chosen + uri }
            looked++
        }
        done = true
    }
    DisposableEffect(Unit) { onDispose { found.forEach { it.second.recycle() } } }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.doc_radar)) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } })
    }, bottomBar = {
        if (found.isNotEmpty()) Button({ onImport(found.map { it.first }.filter { it in chosen }) }, enabled = chosen.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).testTag("radar-import")) {
            Text(pluralStringResource(R.plurals.radar_import, chosen.size, chosen.size))
        }
    }, modifier = Modifier.testTag("radar-screen")) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (!allowed) {
                Text(stringResource(R.string.radar_permission), Modifier.padding(vertical = 12.dp))
                Button({ ask.launch(permissions) }, modifier = Modifier.testTag("radar-allow")) { Text(stringResource(R.string.radar_allow)) }
                // After a refusal the system may not ask again; the app's settings page can still give access.
                if (asked) TextButton({ runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) } }) {
                    Text(stringResource(R.string.radar_settings))
                }
                return@Column
            }
            Text(if (done) pluralStringResource(R.plurals.radar_found, found.size, found.size, total) else stringResource(R.string.radar_looking, looked, total),
                Modifier.padding(vertical = 8.dp).testTag("radar-status"), style = MaterialTheme.typography.bodyMedium)
            if (!done) LinearProgressIndicator({ if (total == 0) 0f else looked / total.toFloat() }, Modifier.fillMaxWidth())
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found, key = { it.first.toString() }) { (uri, picture) ->
                    val on = uri in chosen
                    Box(Modifier.aspectRatio(.75f).clip(RoundedCornerShape(8.dp)).border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.LightGray, RoundedCornerShape(8.dp))
                        .clickable { chosen = if (on) chosen - uri else chosen + uri }.testTag("radar-item")) {
                        Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (on) Icon(Icons.Outlined.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** The newest photos in the gallery, newest first; not the ones DocScan saved there itself. */
internal fun latestPhotos(context: Context, limit: Int): List<Uri> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val out = ArrayList<Uri>()
    val (where, args) = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL OR ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} != ?" to arrayOf(context.packageName) else null to null
    context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), where, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext() && out.size < limit) out += ContentUris.withAppendedId(collection, c.getLong(0))
    }
    return out
}

/** A small, upright copy of the photo, when a document is found on it. */
internal fun look(context: Context, uri: Uri): Bitmap? {
    val small = if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(640, 640), null) else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val turn = runCatching { context.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } }.getOrNull() ?: 0
        if (turn == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
    }
    // 10-bit and HDR photos give other pixel formats; detection works on ordinary 8-bit colour.
    val plain = if (small.config == Bitmap.Config.ARGB_8888) small else small.copy(Bitmap.Config.ARGB_8888, false).also { small.recycle() } ?: return null
    val frame = runCatching { ImagePipeline.detectBitmap(plain) }.getOrNull()
    if (frame == null || frame == Frame.FULL || frame.area() < .2f) { plain.recycle(); return null }
    // A small copy to show in the list.
    val f = 256f / maxOf(plain.width, plain.height)
    if (f >= 1f) return plain
    return Bitmap.createScaledBitmap(plain, maxOf(1, (plain.width * f).toInt()), maxOf(1, (plain.height * f).toInt()), true).also { if (it !== plain) plain.recycle() }
}
