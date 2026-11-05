package com.docscan.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscan.android.DocScanViewModel
import com.docscan.android.R
import com.docscan.android.data.DocumentEntity
import com.docscan.android.data.PageEntity

@Composable
fun DocumentOverview(document: DocumentEntity?, pages: List<PageEntity>, vm: DocScanViewModel, busy: Boolean,
    onCamera: () -> Unit, onImport: () -> Unit, onEdit: (String) -> Unit, onDelete: (PageEntity) -> Unit,
    onRename: () -> Unit, onExport: () -> Unit, onExportImages: () -> Unit, onExportSources: () -> Unit, onGallery: () -> Unit, onShare: () -> Unit,
    onCrop: (PageEntity) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var batchFilter by remember { mutableStateOf(false) }
    var batchDelete by remember { mutableStateOf(false) }
    var qualitySheet by remember { mutableStateOf(false) }
    val ids = vm.selectedPageIds.toList()
    val canOutput = !busy && pages.isNotEmpty() && (!vm.selectingPages || ids.isNotEmpty())
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(document?.title ?: stringResource(R.string.document_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(enabled = !busy && document != null, onClick = onRename) { AppIcon(R.drawable.ic_edit, stringResource(R.string.rename)) }
            Box {
                IconButton(onClick = { menu = true }) { AppIcon(R.drawable.ic_more, stringResource(R.string.more_options)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(if (vm.documentGrid) R.string.continuous_pages else R.string.page_grid)) },
                        onClick = { vm.documentGrid = !vm.documentGrid; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_pages)) }, enabled = !busy, onClick = {
                        vm.selectingPages = !vm.selectingPages; vm.selectedPageIds = arrayListOf(); menu = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_images)) }, enabled = canOutput, onClick = { menu = false; onExportImages() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_sources)) }, enabled = canOutput, onClick = { menu = false; onExportSources() })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (vm.selectingPages) R.string.selected_count else R.string.page_count,
                if (vm.selectingPages) ids.size else pages.size), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy, onClick = { qualitySheet = true }) {
                Text(stringResource(R.string.export_quality_current, exportQualityLabel(vm.exportQuality)))
            }
        }
        if (vm.selectingPages) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.selectedPageIds = ArrayList(pages.map { it.id }) }) { Text(stringResource(R.string.select_all)) }
                IconButton(enabled = canOutput, onClick = { batchFilter = true }) { AppIcon(R.drawable.ic_tune, stringResource(R.string.batch_filters)) }
                IconButton(enabled = canOutput, onClick = { document?.let { vm.batchRotate(it.id, ids) } }) { AppIcon(R.drawable.ic_rotate, stringResource(R.string.rotate)) }
                IconButton(enabled = canOutput, onClick = { batchDelete = true }) { AppIcon(R.drawable.ic_delete, stringResource(R.string.delete)) }
                IconButton(onClick = { vm.selectedPageIds = arrayListOf(); vm.selectingPages = false }) { AppIcon(R.drawable.ic_back, stringResource(R.string.cancel)) }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.drawable.ic_camera, stringResource(R.string.add_pages), !busy, Modifier.weight(1f), onCamera)
            ActionButton(R.drawable.ic_image, stringResource(R.string.import_files), !busy, Modifier.weight(1f), onImport)
        }
        if (document != null && vm.googleReviewDocumentId == document.id) Text(stringResource(R.string.google_review_hint), style = MaterialTheme.typography.bodySmall)
        if (pages.isEmpty()) EmptyState(stringResource(R.string.no_pages_title), stringResource(R.string.no_pages_body), Modifier.weight(1f))
        else if (vm.documentGrid || vm.selectingPages) LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                DocumentPageCard(index, page, pages.size, vm, busy, true, onEdit, onDelete, onCrop)
            }
        } else LazyColumn(Modifier.weight(1f).testTag("continuous-pages"), verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 12.dp)) {
            listItemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                DocumentPageCard(index, page, pages.size, vm, busy, false, onEdit, onDelete, onCrop)
            }
        }
        val shareLabel = stringResource(R.string.share_options)
        Button(onShare, enabled = canOutput, shape = DesignCorner,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 52.dp).semantics { contentDescription = shareLabel }) {
            AppIcon(R.drawable.ic_share, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.save_and_share))
        }
    }
    if (qualitySheet) ExportQualitySheet(vm.exportQuality,
        onApply = { vm.chooseExportQuality(it); qualitySheet = false }, onDismiss = { qualitySheet = false })
    if (batchFilter) BatchFilterSheet(onApply = { filter, strength ->
        batchFilter = false; document?.let { vm.batchFilter(it.id, ids, filter, strength) }
    }, onDismiss = { batchFilter = false })
    if (batchDelete) AlertDialog(onDismissRequest = { batchDelete = false }, title = { Text(stringResource(R.string.delete_pages_title)) },
        text = { Text(stringResource(R.string.delete_page_body)) }, confirmButton = {
            TextButton(onClick = { batchDelete = false; document?.let { vm.batchDelete(it.id, ids) } }) { Text(stringResource(R.string.delete)) }
        }, dismissButton = { TextButton(onClick = { batchDelete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun DocumentPageCard(index: Int, page: PageEntity, count: Int, vm: DocScanViewModel, busy: Boolean, compact: Boolean,
    onEdit: (String) -> Unit, onDelete: (PageEntity) -> Unit, onCrop: (PageEntity) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = DesignCorner) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.page_label, index + 1), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (!compact) TextButton(enabled = !busy, onClick = { onCrop(page) }) { Text(stringResource(R.string.refine_bounds)) }
            val editDescription = stringResource(R.string.edit_page_desc, index + 1)
            TextButton(enabled = !busy, modifier = Modifier.semantics { contentDescription = editDescription }, onClick = { onEdit(page.id) }) { Text(stringResource(R.string.edit)) }
        }
        Box(Modifier.fillMaxWidth()) {
            Surface(onClick = { if (vm.selectingPages) vm.togglePage(page.id) else onEdit(page.id) }, enabled = !busy) {
                PagePreview(page, vm.repository, (if (compact) Modifier.fillMaxWidth().height(158.dp) else Modifier.fillMaxWidth()).testTag("review-page-${page.id}"),
                    edge = if (compact) 360 else 1200, fitPage = !compact)
            }
            if (vm.selectingPages) {
                val description = stringResource(R.string.page_select_desc, index + 1)
                Checkbox(page.id in vm.selectedPageIds, onCheckedChange = { vm.togglePage(page.id) }, enabled = !busy,
                    modifier = Modifier.align(Alignment.TopEnd).semantics { contentDescription = description })
            } else IconButton(enabled = !busy, onClick = { onDelete(page) }, modifier = Modifier.align(Alignment.TopEnd)) {
                AppIcon(R.drawable.ic_delete, stringResource(R.string.delete))
            }
        }
        if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(enabled = !busy && index > 0, onClick = { vm.move(page.id, -1) }) { AppIcon(R.drawable.ic_up, stringResource(R.string.move_previous)) }
            IconButton(enabled = !busy && index < count - 1, onClick = { vm.move(page.id, 1) }) { AppIcon(R.drawable.ic_down, stringResource(R.string.move_next)) }
        }
    }
}
