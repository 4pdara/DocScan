package com.docscan.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docscan.android.DocScanViewModel
import com.docscan.android.R
import com.docscan.android.data.DocumentSummary

@Composable
fun LibraryScreen(documents: List<DocumentSummary>, vm: DocScanViewModel, busy: Boolean, googlePreparing: Boolean,
    onGoogleScan: () -> Unit, onCancelGoogle: () -> Unit, onOpen: (String) -> Unit,
    onImport: () -> Unit, onDelete: (DocumentSummary) -> Unit, onBackup: () -> Unit, onRestore: () -> Unit) {
    var tools by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val filtered = documents.filter { it.document.title.contains(vm.libraryQuery, ignoreCase = true) }
    val visible = when (vm.librarySort) {
        "TITLE" -> filtered.sortedBy { it.document.title.lowercase() }
        "CREATED" -> filtered.sortedByDescending { it.document.createdAt }
        "OLDEST" -> filtered.sortedBy { it.document.createdAt }
        else -> filtered.sortedByDescending { it.document.updatedAt }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.library_count, documents.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = !busy, onClick = onImport) { AppIcon(R.drawable.ic_image, stringResource(R.string.import_files)) }
            Box {
                IconButton(onClick = { tools = true }) { AppIcon(R.drawable.ic_more, stringResource(R.string.more_options)) }
                DropdownMenu(tools, onDismissRequest = { tools = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_documents)) }, enabled = !busy, onClick = {
                        vm.selectingDocuments = !vm.selectingDocuments; vm.selectedDocuments = arrayListOf(); tools = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.scan_google)) }, enabled = !busy, onClick = { tools = false; onGoogleScan() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.archive_backup)) }, enabled = !busy && documents.isNotEmpty(), onClick = { tools = false; onBackup() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.archive_restore)) }, enabled = !busy, onClick = { tools = false; onRestore() })
                }
            }
        }
        OutlinedTextField(vm.libraryQuery, { vm.libraryQuery = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.search_documents)) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { sorting = true }) { Text(stringResource(R.string.sort_documents)) }
                DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                    for ((key, label) in listOf("UPDATED" to R.string.sort_updated, "CREATED" to R.string.sort_created, "TITLE" to R.string.sort_title, "OLDEST" to R.string.sort_oldest)) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { vm.librarySort = key; sorting = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.libraryGrid = !vm.libraryGrid }) { Text(stringResource(if (vm.libraryGrid) R.string.list_view else R.string.grid_view)) }
        }
        if (googlePreparing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.google_scan_preparing), Modifier.weight(1f))
            TextButton(onClick = onCancelGoogle) { Text(stringResource(R.string.cancel)) }
        }
        if (vm.selectingDocuments) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.selected_count, vm.selectedDocuments.size), Modifier.weight(1f))
            TextButton(enabled = !busy && vm.selectedDocuments.size >= 2, onClick = { merging = true }) { Text(stringResource(R.string.merge_documents)) }
            TextButton(onClick = { vm.selectingDocuments = false; vm.selectedDocuments = arrayListOf() }) { Text(stringResource(R.string.cancel)) }
        }
        if (visible.isEmpty()) EmptyState(stringResource(R.string.empty_title), stringResource(R.string.empty_body), Modifier.weight(1f))
        else if (vm.libraryGrid) LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.document.id }) { summary -> LibraryCard(summary, vm, busy, true, onOpen, onDelete) }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.document.id }) { summary -> LibraryCard(summary, vm, busy, false, onOpen, onDelete) }
        }
    }
    if (merging) MergeDialog(vm.selectedDocuments.mapNotNull { id -> documents.firstOrNull { it.document.id == id } },
        onConfirm = { ids, title -> merging = false; vm.mergeDocuments(ids, title) }, onDismiss = { merging = false })
}

@Composable
private fun LibraryCard(summary: DocumentSummary, vm: DocScanViewModel, busy: Boolean, grid: Boolean,
    onOpen: (String) -> Unit, onDelete: (DocumentSummary) -> Unit) {
    val pages by remember(summary.document.id) { vm.repository.observePages(summary.document.id) }.collectAsStateWithLifecycle(emptyList())
    ElevatedCard(onClick = { if (!busy) { if (vm.selectingDocuments) vm.toggleDocument(summary.document.id) else onOpen(summary.document.id) } }, shape = RoundedCornerShape(20.dp)) {
        if (grid) Column(Modifier.padding(12.dp)) {
            pages.firstOrNull()?.let { PagePreview(it, vm.repository, Modifier.fillMaxWidth().height(150.dp), edge = 280) }
            LibraryCardText(summary, vm, busy, onDelete)
        } else Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pages.firstOrNull()?.let { PagePreview(it, vm.repository, Modifier.width(64.dp).height(86.dp), edge = 180) }
                ?: AppIcon(R.drawable.ic_pdf, null, Modifier.size(48.dp))
            Box(Modifier.weight(1f)) { LibraryCardText(summary, vm, busy, onDelete) }
        }
    }
}
@Composable
private fun LibraryCardText(summary: DocumentSummary, vm: DocScanViewModel, busy: Boolean, onDelete: (DocumentSummary) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(summary.document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.page_count, summary.pageCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vm.selectingDocuments) Checkbox(summary.document.id in vm.selectedDocuments, enabled = !busy,
            onCheckedChange = { vm.toggleDocument(summary.document.id) })
        else IconButton(enabled = !busy, onClick = { onDelete(summary) }) { AppIcon(R.drawable.ic_delete, stringResource(R.string.delete)) }
    }
}
