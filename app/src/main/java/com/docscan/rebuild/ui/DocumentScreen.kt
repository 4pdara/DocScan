package com.docscan.rebuild.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(store: ScanStore, title: String, pages: List<Sheet>, onBack: () -> Unit, onEdit: (Sheet) -> Unit, onCamera: () -> Unit, onImport: () -> Unit, onPdf: () -> Unit, onGallery: () -> Unit, onShare: () -> Unit, onMove: (Sheet, Int) -> Unit, onRename: (String) -> Unit, snack: SnackbarHostState, onOcr: () -> Unit = {}) {
    var reorder by rememberSaveable { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var newTitle by remember(title) { mutableStateOf(title) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(newTitle, { newTitle = it }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (newTitle.isNotBlank()) { onRename(newTitle); rename = false } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) } })
    if (actions) ModalBottomSheet(onDismissRequest = { actions = false }, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("document-more-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("save-gallery").clickable(enabled = pages.isNotEmpty()) { actions = false; onGallery() }) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(30.dp), tint = ScanTeal)
                    Column { Text(stringResource(R.string.save_gallery), style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.gallery_all_pages), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.export_pdf, Icons.Outlined.PictureAsPdf, Modifier.weight(1f), pages.isNotEmpty()) { actions = false; onPdf() }
                DocumentAction(R.string.share, Icons.Outlined.Share, Modifier.weight(1f), pages.isNotEmpty()) { actions = false; onShare() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.import_images, Icons.Outlined.AddPhotoAlternate, Modifier.weight(1f)) { actions = false; onImport() }
                DocumentAction(R.string.rename, Icons.Outlined.Edit, Modifier.weight(1f)) { actions = false; rename = true }
                DocumentAction(R.string.reorder, Icons.Outlined.SwapVert, Modifier.weight(1f), pages.size > 1) { actions = false; reorder = !reorder }
            }
            DocumentAction(R.string.ocr_document, Icons.Outlined.TextSnippet, Modifier.fillMaxWidth().testTag("document-ocr-open"), pages.isNotEmpty()) { actions = false; onOcr() }
            TextButton(onClick = { actions = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = { Column(Modifier.clickable { rename = true }) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            Text(pluralStringResource(R.plurals.pages, pages.size, pages.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.rename)) }
                IconButton(onClick = { actions = true }, modifier = Modifier.testTag("export-menu")) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) }
            })
    }, bottomBar = {
        Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).testTag("document-actions"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onCamera, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.add_pages), maxLines = 1) }
                IconButton(onShare, enabled = pages.isNotEmpty()) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                Button(onPdf, enabled = pages.isNotEmpty(), shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).testTag("save-pdf"), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.export_pdf), maxLines = 1) }
            }
        }
    }) { padding ->
        if (pages.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_pages)) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("document-pages"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                Column {

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onEdit(page) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.edit)) }
                        Spacer(Modifier.weight(1f))
                        if (reorder) {
                            IconButton(onClick = { onMove(page, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up)) }
                            IconButton(onClick = { onMove(page, 1) }, enabled = index < pages.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down)) }
                        }
                        Text(stringResource(R.string.page_counter, index + 1, pages.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(Modifier.fillMaxWidth().clickable { onEdit(page) }, shadowElevation = 3.dp) {
                        PagePicture(store, page, Modifier.fillMaxWidth().testTag("page-image-${page.id}"), naturalHeight = true, description = stringResource(R.string.page_number, index + 1))
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentAction(label: Int, icon: ImageVector, modifier: Modifier, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = modifier.heightIn(min = 82.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, Modifier.size(26.dp)); Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}
