package com.docscan.android.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscan.android.DocScanViewModel
import com.docscan.android.R
import com.docscan.android.data.DocumentEntity
import com.docscan.android.data.DocumentSummary
import com.docscan.android.data.PageEntity
import com.docscan.android.export.ExportNames
import com.docscan.android.processing.ScanFilter
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScanApp(vm: DocScanViewModel = viewModel()) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val direction = if (configuration.locales[0].language == "en") LayoutDirection.Ltr else LayoutDirection.Rtl
    val documents by vm.documents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("home") }
    var documentId by rememberSaveable { mutableStateOf<String?>(null) }
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var exportTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteDocument by remember { mutableStateOf<DocumentSummary?>(null) }
    var pendingDeletePage by remember { mutableStateOf<PageEntity?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf("") }
    val document = documents.firstOrNull { it.document.id == documentId }?.document
    val pageFlow = remember(documentId) { documentId?.let(vm.repository::observePages) ?: flowOf(emptyList()) }
    val pages by pageFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.clearMessage() } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.importImages(uris, importTarget) { id -> documentId = id; screen = "document" }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportTarget
        if (uri != null && id != null) vm.export(id, uri)
        exportTarget = null
    }
    fun navigateBack() { screen = if (screen == "camera" || screen == "editor") "document" else "home" }
    BackHandler(screen != "home") { navigateBack() }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("DocScan", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        if (screen != "home") IconButton(onClick = { navigateBack() }) { AppIcon(R.drawable.ic_back, stringResource(R.string.back)) }
                    },
                    actions = { if (busy) CircularProgressIndicator(Modifier.padding(16.dp).size(22.dp), strokeWidth = 2.dp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (screen == "home" && !busy) ExtendedFloatingActionButton(
                    onClick = { if (!busy) vm.create { id -> documentId = id; screen = "camera" } },
                    icon = { AppIcon(R.drawable.ic_camera, null) }, text = { Text(stringResource(R.string.new_scan)) },
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    "home" -> HomeScreen(documents, busy,
                        onOpen = { documentId = it; screen = "document" },
                        onImport = { importTarget = null; importPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onDelete = { pendingDeleteDocument = it },
                    )
                    "camera" -> documentId?.let { id -> CameraScreen(
                        pages.size, busy, onDone = { screen = "document" },
                        onCapture = { file, onSaved -> vm.capture(id, file, onSaved) }, onFailure = vm::cameraError,
                    ) }
                    "editor" -> {
                        val page = pages.firstOrNull { it.id == pageId }
                        if (page != null) PageEditor(page, vm, busy)
                        else LaunchedEffect(Unit) { screen = "document" }
                    }
                    else -> DocumentScreen(document, pages, vm, busy,
                        onCamera = { screen = "camera" },
                        onImport = { importTarget = documentId; importPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onEdit = { pageId = it; screen = "editor" },
                        onDelete = { pendingDeletePage = it },
                        onRename = { editedName = document?.title.orEmpty(); renaming = true },
                        onExport = { exportTarget = documentId; exportPicker.launch(ExportNames.pdf(document?.title.orEmpty())) },
                        onShare = {
                            documentId?.let { id -> vm.export(id, onShare = { file ->
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, "DocScan PDF", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
                            }) }
                        },
                    )
                }
            }
        }
        if (pendingDeleteDocument != null || pendingDeletePage != null) {
            val wholeDocument = pendingDeleteDocument != null
            AlertDialog(
                onDismissRequest = { pendingDeleteDocument = null; pendingDeletePage = null },
                title = { Text(stringResource(if (wholeDocument) R.string.delete_document_title else R.string.delete_page_title)) },
                text = { Text(stringResource(if (wholeDocument) R.string.delete_document_body else R.string.delete_page_body)) },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    pendingDeleteDocument?.let { vm.deleteDocument(it.document.id) }
                    pendingDeletePage?.let { vm.deletePage(it.id) }
                    pendingDeleteDocument = null; pendingDeletePage = null
                }) { Text(stringResource(R.string.delete)) } },
                dismissButton = { TextButton(onClick = { pendingDeleteDocument = null; pendingDeletePage = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (renaming) AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(editedName, onValueChange = { editedName = it.take(120) }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true) },
            confirmButton = { TextButton(enabled = editedName.isNotBlank() && !busy, onClick = { documentId?.let { vm.rename(it, editedName) }; renaming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun HomeScreen(documents: List<DocumentSummary>, busy: Boolean, onOpen: (String) -> Unit, onImport: () -> Unit, onDelete: (DocumentSummary) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.library_subtitle), Modifier.padding(top = 8.dp, bottom = 20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.local_badge), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.prototype_label), style = MaterialTheme.typography.bodySmall)
                    ActionButton(R.drawable.ic_image, stringResource(R.string.import_images), !busy, onClick = onImport)
                }
            }
        }
        if (documents.isEmpty()) item { EmptyState(stringResource(R.string.empty_title), stringResource(R.string.empty_body), Modifier.fillMaxWidth()) }
        items(documents, key = { it.document.id }) { summary ->
            ElevatedCard(onClick = { if (!busy) onOpen(summary.document.id) }, shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { AppIcon(R.drawable.ic_pdf, null, Modifier.padding(16.dp).size(28.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(summary.document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.page_count, summary.pageCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = !busy, onClick = { onDelete(summary) }) { AppIcon(R.drawable.ic_delete, stringResource(R.string.delete)) }
                }
            }
        }
    }
}

@Composable
private fun DocumentScreen(
    document: DocumentEntity?, pages: List<PageEntity>, vm: DocScanViewModel, busy: Boolean,
    onCamera: () -> Unit, onImport: () -> Unit, onEdit: (String) -> Unit,
    onDelete: (PageEntity) -> Unit, onRename: () -> Unit, onExport: () -> Unit, onShare: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(document?.title ?: stringResource(R.string.document_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(enabled = !busy && document != null, onClick = onRename) { AppIcon(R.drawable.ic_edit, stringResource(R.string.rename)) }
        }
        Text(stringResource(R.string.page_count, pages.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.drawable.ic_camera, stringResource(R.string.add_pages), !busy, Modifier.weight(1f), onCamera)
            ActionButton(R.drawable.ic_image, stringResource(R.string.import_images), !busy, Modifier.weight(1f), onImport)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(R.drawable.ic_pdf, stringResource(R.string.save_pdf), !busy && pages.isNotEmpty(), Modifier.weight(1f), onExport)
            ActionButton(R.drawable.ic_share, stringResource(R.string.share_pdf), !busy && pages.isNotEmpty(), Modifier.weight(1f), onShare)
        }
        if (pages.isEmpty()) EmptyState(stringResource(R.string.no_pages_title), stringResource(R.string.no_pages_body), Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                OutlinedCard(shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(onClick = { onEdit(page.id) }, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                            PagePreview(page, vm.repository, Modifier.width(96.dp).height(128.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.page_label, index + 1), style = MaterialTheme.typography.titleMedium)
                            Text(filterLabel(ScanFilter.valueOf(page.filter)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(enabled = !busy, onClick = { onEdit(page.id) }) { Text(stringResource(R.string.edit)) }
                            Row {
                                IconButton(enabled = !busy && index > 0, onClick = { vm.move(page.id, -1) }) { AppIcon(R.drawable.ic_up, stringResource(R.string.move_previous)) }
                                IconButton(enabled = !busy && index < pages.lastIndex, onClick = { vm.move(page.id, 1) }) { AppIcon(R.drawable.ic_down, stringResource(R.string.move_next)) }
                                IconButton(enabled = !busy, onClick = { onDelete(page) }) { AppIcon(R.drawable.ic_delete, stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageEditor(page: PageEntity, vm: DocScanViewModel, busy: Boolean) {
    var showOriginal by rememberSaveable(page.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PagePreview(if (showOriginal) page.copy(rotation = 0, filter = ScanFilter.ORIGINAL.name) else page, vm.repository, Modifier.weight(1f).fillMaxWidth(), edge = 1400)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(showOriginal, onCheckedChange = { showOriginal = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.show_original)) }
            IconButton(enabled = !busy && !showOriginal, onClick = { vm.rotate(page.id) }) { AppIcon(R.drawable.ic_rotate, stringResource(R.string.rotate)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ScanFilter.entries) { filter ->
                FilterChip(selected = page.filter == filter.name, enabled = !busy && !showOriginal, onClick = { vm.filter(page.id, filter) }, label = { Text(filterLabel(filter)) })
            }
        }
        Text(stringResource(R.string.original_preserved), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.prototype_filters), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
