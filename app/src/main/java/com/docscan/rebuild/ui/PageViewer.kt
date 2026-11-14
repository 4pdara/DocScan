package com.docscan.rebuild.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.data.Sheet

/**
 * One page at a time, swiping between the document's pages, with what can be done to the shown page: edit it,
 * read its text, sign, share or delete it, and (under ⋮) rotate, save, retake. Editing opens the editor's tools;
 * nothing changes until one is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageViewer(store: ScanStore, pages: List<Sheet>, startId: String?, title: String, busy: Boolean, snack: SnackbarHostState, onBack: () -> Unit,
    onPage: (Sheet) -> Unit, onEdit: (Sheet) -> Unit, onOcr: (Sheet) -> Unit, onSign: (Sheet) -> Unit, onShare: (Sheet) -> Unit, onDelete: (Sheet) -> Unit,
    onRotate: (Sheet) -> Unit, onGallery: (Sheet) -> Unit, onSaveImage: (Sheet) -> Unit, onRetake: (Sheet) -> Unit, onOriginal: (Sheet) -> Unit) {
    if (pages.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val start = pages.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { pages.size }
    val current = pages.getOrNull(pager.currentPage.coerceIn(0, pages.lastIndex)) ?: pages.first()
    LaunchedEffect(current.id) { onPage(current) }
    var more by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.delete_page_title)) },
        text = { Text(stringResource(R.string.delete_page_message)) },
        confirmButton = { TextButton({ confirmDelete = false; onDelete(current) }, modifier = Modifier.testTag("confirm-delete-page")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    Scaffold(modifier = Modifier.testTag("page-viewer"), snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = { Column {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.page_counter, pager.currentPage + 1, pages.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("viewer-counter"))
        } }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                Box {
                    IconButton({ more = true }, enabled = !busy, modifier = Modifier.testTag("viewer-more")) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rotate)) }, leadingIcon = { Icon(Icons.Outlined.RotateLeft, null) }, onClick = { more = false; onRotate(current) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.retake)) }, leadingIcon = { Icon(Icons.Outlined.CameraAlt, null) }, onClick = { more = false; onRetake(current) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_gallery)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, onClick = { more = false; onGallery(current) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_jpeg)) }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { more = false; onSaveImage(current) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.source_export)) }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) }, onClick = { more = false; onOriginal(current) })
                    }
                }
            })
    }, bottomBar = {
        Surface(shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp).testTag("viewer-actions")) {
                ViewerAction(R.string.edit, Icons.Outlined.Edit, "viewer-edit", Modifier.weight(1f), !busy) { onEdit(current) }
                ViewerAction(R.string.extract_text, Icons.Outlined.FindInPage, "viewer-ocr", Modifier.weight(1f), !busy) { onOcr(current) }
                ViewerAction(R.string.signature, Icons.Outlined.Draw, "viewer-sign", Modifier.weight(1f), !busy) { onSign(current) }
                ViewerAction(R.string.share, Icons.Outlined.Share, "viewer-share", Modifier.weight(1f), !busy) { onShare(current) }
                ViewerAction(R.string.delete, Icons.Outlined.Delete, "viewer-delete", Modifier.weight(1f), !busy) { confirmDelete = true }
            }
        }
    }) { padding ->
        // Two fingers zoom into the shown page (drawn from the full-resolution photo when zoomed); swiping
        // between pages works again at normal size.
        var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(pager.currentPage) { zoom = 1f; pan = Offset.Zero }
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceVariant).testTag("viewer-pages"),
            key = { pages[it].id }, userScrollEnabled = zoom <= 1f) { index ->
            val shown = index == pager.currentPage
            Box(Modifier.fillMaxSize().padding(14.dp).pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1 || zoom > 1f) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            pan = if (zoom == 1f) Offset.Zero else pan + event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }, contentAlignment = Alignment.Center) {
                PagePicture(store, pages[index], Modifier.fillMaxSize().testTag("viewer-page-${pages[index].id}")
                    .graphicsLayer { if (shown) { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y } },
                    edge = if (shown && zoom > 1.25f) 4096 else 1800, description = stringResource(R.string.page_number, index + 1))
            }
        }
    }
}

@Composable
private fun ViewerAction(label: Int, icon: ImageVector, tag: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(3.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}
