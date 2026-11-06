package com.docscan.rebuild.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(store: ScanStore, entries: List<LibraryEntry>, trash: List<LibraryEntry>, onScan: () -> Unit, onImport: () -> Unit, onOpen: (String) -> Unit, onTrash: (Set<String>, Boolean) -> Unit, snack: SnackbarHostState) {
    var search by rememberSaveable { mutableStateOf("") }
    var inTrash by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val visible = (if (inTrash) trash else entries).filter { it.book.title.contains(search, true) }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clear() { selection = false; selected = emptyList() }
    LaunchedEffect(entries, trash, inTrash, search) { selected = selected.filter { id -> visible.any { it.book.id == id } } }
    BackHandler(selection || inTrash) { if (selection) clear() else inTrash = false }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = {
            Column {
                Text(if (inTrash) stringResource(R.string.trash) else stringResource(R.string.my_documents), style = MaterialTheme.typography.titleLarge)
                Text(if (selection) stringResource(R.string.selected_count, selected.size) else "DocScan · ${stringResource(R.string.preview_label)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }, actions = {
            if (selection) {
                IconButton(onClick = { selected = if (visible.all { it.book.id in selected }) emptyList() else visible.map { it.book.id } }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) }
                IconButton(onClick = ::clear) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
            } else {
                IconButton(onClick = { selection = true }) { Icon(Icons.Outlined.Checklist, stringResource(R.string.select_documents)) }
                IconButton(onClick = { inTrash = !inTrash; selected = emptyList() }) { Icon(if (inTrash) Icons.Outlined.Folder else Icons.Outlined.DeleteOutline, stringResource(if (inTrash) R.string.library else R.string.trash)) }
                IconButton(onImport) { Icon(Icons.Outlined.PhotoLibrary, stringResource(R.string.import_images)) }
            }
        })
    }, floatingActionButton = {
        if (!selection && !inTrash) ExtendedFloatingActionButton(onClick = onScan,
            icon = { Icon(Icons.Outlined.CameraAlt, null) }, text = { Text(stringResource(R.string.new_scan)) },
            containerColor = ScanTeal, contentColor = androidx.compose.ui.graphics.Color.White, modifier = Modifier.testTag("new-scan"))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("library")) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp))
            if (selection) {
                Button(onClick = { onTrash(selected.toSet(), inTrash); clear() }, enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Icon(if (inTrash) Icons.Outlined.RestoreFromTrash else Icons.Outlined.DeleteOutline, stringResource(if (inTrash) R.string.restore else R.string.move_to_trash))
                    Spacer(Modifier.width(8.dp)); Text(stringResource(if (inTrash) R.string.restore else R.string.move_to_trash))
                }
            }
            if (inTrash) Text(stringResource(R.string.trash_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(64.dp), tint = ScanTeal)
                    Spacer(Modifier.height(18.dp)); Text(stringResource(if (inTrash) R.string.empty_trash else R.string.empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.empty_hint), Modifier.padding(vertical = 12.dp))
                    if (!inTrash) OutlinedButton(onImport) { Text(stringResource(R.string.import_images)) }
                }
            } else LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.weight(1f).testTag("library-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.book.id }) { item ->
                    val documentPages by remember(item.book.id) { store.dao.pages(item.book.id) }.collectAsState(emptyList())
                    val checked = item.book.id in selected
                    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) ScanTeal else MaterialTheme.colorScheme.outlineVariant),
                        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (selection || inTrash) { selection = true; toggle(item.book.id) } else onOpen(item.book.id) },
                            onLongClick = { selection = true; toggle(item.book.id) })) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(174.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(10.dp), contentAlignment = Alignment.Center) {
                                documentPages.firstOrNull()?.let { PagePicture(store, it, Modifier.fillMaxSize(), edge = 320) }
                                    ?: Icon(Icons.Outlined.Description, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                                if (selection) Checkbox(checked, { toggle(item.book.id) }, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)).testTag("select-${item.book.id}"))
                            }
                            Row(Modifier.padding(start = 12.dp, top = 9.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(3.dp)); Text(pluralStringResource(R.plurals.pages, item.pageCount, item.pageCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!selection) IconButton(onClick = { selection = true; toggle(item.book.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "${stringResource(R.string.more_actions)}: ${item.book.title}") }
                            }
                        }
                    }
                }
            }
        }
    }
}
