package com.docscan.android.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.docscan.android.export.PrintExport
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.docscan.android.data.withoutEdits
import com.docscan.android.export.ExportNames
import com.docscan.android.processing.CropQuad
import com.docscan.android.processing.PageFormat
import com.docscan.android.processing.ScanFilter
import com.docscan.android.processing.AutoCaptureGate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScanApp(vm: DocScanViewModel = viewModel()) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val direction = if (configuration.locales[0].language == "en") LayoutDirection.Ltr else LayoutDirection.Rtl
    val documents by vm.documents.collectAsStateWithLifecycle()
    val working by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var screen by vm::screen
    var documentId by vm::documentId
    var pageId by vm::pageId
    var retakePageId by vm::retakePageId
    var returnToCamera by vm::returnToCamera
    val captureGate = remember(documentId) { AutoCaptureGate() }
    var importTarget by vm::importTarget
    var exportTarget by vm::exportTarget
    var imageExportTarget by vm::imageExportTarget
    var pendingDeleteDocument by remember { mutableStateOf<DocumentSummary?>(null) }
    var pendingDeletePage by remember { mutableStateOf<PageEntity?>(null) }
    var importing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf("") }
    var archiveAction by rememberSaveable { mutableStateOf<String?>(null) }
    val archiveOperation by vm.archiveOperation.collectAsStateWithLifecycle()
    val archiveProgress by vm.archiveProgress.collectAsStateWithLifecycle()
    val archiveCancelling by vm.archiveCancelling.collectAsStateWithLifecycle()
    val document = documents.firstOrNull { it.document.id == documentId }?.document
    val pageFlow: Flow<List<PageEntity>?> = remember(documentId) { documentId?.let(vm.repository::observePages) ?: flowOf(emptyList()) }
    val loadedPages by key(documentId) { pageFlow.collectAsStateWithLifecycle(initialValue = null) }
    val pages = loadedPages.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val googleScan = rememberGoogleScanAction(onPages = { uris ->
        vm.importImages(uris, null, detectBounds = false) { id ->
            vm.googleReviewDocumentId = id; vm.documentGrid = false
            documentId = id; returnToCamera = false; screen = "document"
        }
    }, onError = vm::googleScannerError)
    val busy = working || googleScan.preparing
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.clearMessage() } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.importImages(uris, importTarget) { id -> documentId = id; screen = "document" }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importPdfs(uris, importTarget) { id -> vm.openDocument(id) }
    }
    val archiveWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupArchive(uri)
    }
    val archiveReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreArchive(uri)
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        archiveAction?.let { action ->
            ArchiveConfirmDialog(action == "backup", onConfirm = {
                archiveAction = null
                if (action == "backup") {
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    archiveWriter.launch("DocScan-Backup-$timestamp.zip")
                } else archiveReader.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }, onDismiss = { archiveAction = null })
        }
        archiveOperation?.let {
            ArchiveProgressDialog(it == "backup", archiveProgress.finishing, archiveCancelling, vm::cancelArchive)
        }
    }
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true && grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val id = vm.galleryTarget
        val ids = vm.outputPageIds.takeIf { it.isNotEmpty() }?.toList()
        vm.galleryTarget = null; vm.outputPageIds = arrayListOf()
        if (granted && id != null) vm.saveGallery(id, ids)
        else if (!granted) Toast.makeText(context, R.string.gallery_permission_denied, Toast.LENGTH_LONG).show()
    }
    fun outputIds(): List<String>? = if (vm.selectingPages) vm.selectedPageIds.toList() else null
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportTarget
        if (uri != null && id != null) vm.export(id, uri, ids = vm.outputPageIds.takeIf { it.isNotEmpty() }?.toList())
        exportTarget = null; vm.outputPageIds = arrayListOf()
    }
    val imageFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = imageExportTarget
        if (uri != null && id != null) vm.exportImages(id, uri, vm.outputPageIds.takeIf { it.isNotEmpty() }?.toList())
        imageExportTarget = null; vm.outputPageIds = arrayListOf()
    }
    fun navigateBack() {
        if (screen == "document" && vm.selectingPages) { vm.selectingPages = false; vm.selectedPageIds = arrayListOf(); return }
        if (screen == "home" && vm.selectingDocuments) { vm.selectingDocuments = false; vm.selectedDocuments = arrayListOf(); return }
        if (screen == "crop") vm.clearCropDraft()
        screen = when (screen) {
            "crop" -> "editor"
            "camera" -> if (retakePageId != null) { retakePageId = null; "editor" } else "document"
            "editor" -> if (returnToCamera) "camera" else "document"
            else -> "home"
        }
    }
    BackHandler(screen != "home" || vm.selectingDocuments) {
        if (screen == "home" && vm.selectingDocuments) { vm.selectingDocuments = false; vm.selectedDocuments = arrayListOf() }
        else if (screen == "document" && vm.selectingPages) { vm.selectingPages = false; vm.selectedPageIds = arrayListOf() }
        else navigateBack()
    }
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
            bottomBar = {
                if (screen == "home") Surface(tonalElevation = 1.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            enabled = !busy,
                            onClick = { vm.create { id -> documentId = id; screen = "camera" } },
                            modifier = Modifier.heightIn(min = 56.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            AppIcon(R.drawable.ic_camera, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.new_scan))
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    "home" -> LibraryScreen(documents, vm, busy,
                        googlePreparing = googleScan.preparing, onGoogleScan = googleScan.launch, onCancelGoogle = googleScan.cancel,
                        onOpen = { vm.openDocument(it) },
                        onImport = { importTarget = null; importing = true },
                        onDelete = { pendingDeleteDocument = it },
                        onBackup = { archiveAction = "backup" }, onRestore = { archiveAction = "restore" },
                    )
                    "camera" -> documentId?.let { id ->
                        val target = retakePageId
                        CameraScreen(
                        pages, vm.repository, captureGate, busy, manualOnly = target != null, onDone = { vm.documentGrid = false; navigateBack() },
                        onReview = { pageId = it; returnToCamera = true; screen = "editor" },
                        onCapture = { file, detectionMode, onSaved ->
                            if (target == null) vm.capture(id, file, detectionMode, onSaved)
                            else if (screen != "camera" || retakePageId != target) { file.delete(); onSaved(false) }
                            else vm.retake(target, file, detectionMode) { saved ->
                                onSaved(saved)
                                if (saved) { retakePageId = null; screen = "editor" }
                            }
                        }, onFailure = vm::cameraError,
                    ) }
                    "editor" -> {
                        val page = pages.firstOrNull { it.id == pageId }
                        if (page != null) PageEditor(page, pages, vm, busy, onCrop = { vm.beginCrop(it) },
                            onRetake = { retakePageId = it; screen = "camera" })
                        else if (loadedPages != null) LaunchedEffect(documentId, pageId) {
                            val selectedPage = pageId
                            val selectedDocument = documentId
                            // A cached list can lag behind a saved route. Confirm deletion in Room first.
                            if (selectedDocument == null || vm.repository.observePages(selectedDocument).first().none { it.id == selectedPage }) {
                                screen = "document"
                            }
                        }
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    "crop" -> {
                        val page = pages.firstOrNull { it.id == pageId }
                        if (page != null) CropScreen(page, vm.repository, busy,
                            quad = CropQuad.decode(vm.cropPoints) ?: CropQuad.FULL,
                            format = PageFormat.fromKey(vm.cropFormat), rectangle = vm.cropRectangle,
                            detectOnOpen = vm.cropDetectOnOpen, onDetectionComplete = { vm.cropDetectOnOpen = false },
                            onQuadChange = { vm.cropPoints = it.encode() },
                            onFormatChange = { vm.cropFormat = it.name },
                            onRectangleChange = { vm.cropRectangle = it },
                            onCancel = { vm.clearCropDraft(); screen = "editor" },
                            onApply = { quad, format -> vm.crop(page.id, quad, format) { vm.clearCropDraft(); screen = "editor" } })
                        else if (loadedPages != null) LaunchedEffect(documentId, pageId) {
                            val selectedPage = pageId
                            val selectedDocument = documentId
                            // A cached list can lag behind a saved route. Confirm deletion in Room first.
                            if (selectedDocument == null || vm.repository.observePages(selectedDocument).first().none { it.id == selectedPage }) {
                                screen = "document"
                            }
                        }
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    else -> if (loadedPages != null) DocumentOverview(document, pages, vm, busy,
                        onCamera = { screen = "camera" },
                        onImport = { importTarget = documentId; importing = true },
                        onEdit = { pageId = it; returnToCamera = false; screen = "editor" },
                        onDelete = { pendingDeletePage = it },
                        onRename = { editedName = document?.title.orEmpty(); renaming = true },
                        onExport = { vm.outputPageIds = ArrayList(outputIds().orEmpty()); exportTarget = documentId; exportPicker.launch(ExportNames.pdf(document?.title.orEmpty())) },
                        onExportImages = { vm.outputPageIds = ArrayList(outputIds().orEmpty()); imageExportTarget = documentId; imageFolderPicker.launch(null) },
                        onGallery = {
                            documentId?.let { id ->
                                val ids = outputIds()
                                if (Build.VERSION.SDK_INT <= 28 && listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE).any {
                                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                }) {
                                    vm.galleryTarget = id; vm.outputPageIds = ArrayList(ids.orEmpty())
                                    galleryPermission.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
                                } else vm.saveGallery(id, ids)
                            }
                        },
                        onShare = { sharing = true },
                        onCrop = { returnToCamera = false; vm.beginCrop(it, detectBounds = true) },
                    )
                }
            }
        }
        if (importing) ImportSheet(onImages = { importing = false; importPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPdf = { importing = false; pdfPicker.launch(arrayOf("application/pdf")) }, onDismiss = { importing = false })
        if (sharing) ShareSheet(onShare = { format ->
            sharing = false; documentId?.let { id -> vm.share(id, format, outputIds()) { intent ->
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_options)))
            } }
        }, onPrint = {
            sharing = false; documentId?.let { id -> vm.print(id, outputIds()) { file -> PrintExport.launch(context, file, document?.title.orEmpty()) } }
        }, onDismiss = { sharing = false })
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
private fun PageEditor(initialPage: PageEntity, pages: List<PageEntity>, vm: DocScanViewModel, busy: Boolean,
    onCrop: (PageEntity) -> Unit, onRetake: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == initialPage.id }.coerceAtLeast(0)) { pages.size }
    val page = pages.getOrNull(pager.settledPage) ?: initialPage
    var zoomed by remember { mutableStateOf(false) }
    val blocked = busy || pager.isScrollInProgress
    LaunchedEffect(page.id) { vm.pageId = page.id; zoomed = false }
    var showOriginal by rememberSaveable(page.id) { mutableStateOf(false) }
    var filterPicker by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pages.size > 1) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.page_position, pager.currentPage + 1, pages.size))
            Text(stringResource(R.string.swipe_pages), style = MaterialTheme.typography.labelSmall)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("page-pager"),
            key = { pages[it].id }, userScrollEnabled = !busy && !zoomed && !filterPicker && !adjusting) { index ->
            val item = pages[index]
            PagePreview(if (showOriginal && item.id == page.id) item.withoutEdits() else item, vm.repository,
                Modifier.fillMaxSize(), edge = 1400, zoomable = item.id == page.id,
                onZoomChanged = { if (item.id == page.id) zoomed = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorTool(R.drawable.ic_crop, stringResource(R.string.crop_title), !blocked && !showOriginal, Modifier.weight(1f)) { onCrop(page) }
            EditorTool(R.drawable.ic_image, stringResource(R.string.open_filters), !blocked && !showOriginal, Modifier.weight(1f)) { filterPicker = true }
            EditorTool(R.drawable.ic_tune, stringResource(R.string.image_adjustments), !blocked && !showOriginal, Modifier.weight(1f)) { adjusting = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(showOriginal, onCheckedChange = { showOriginal = it })
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.show_original), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(enabled = !blocked && !showOriginal, onClick = { onRetake(page.id) }) { Text(stringResource(R.string.retake)) }
            IconButton(enabled = !blocked && !showOriginal, onClick = { vm.rotate(page.id) }) { AppIcon(R.drawable.ic_rotate, stringResource(R.string.rotate)) }
        }
        Text(stringResource(R.string.editor_summary, filterLabel(ScanFilter.valueOf(page.filter)),
            pageFormatLabel(com.docscan.android.processing.PageFormat.fromKey(page.pageFormat)), page.sharpness),
            style = MaterialTheme.typography.labelMedium)
        if (vm.repository.source(page).extension == "pdf") Text(stringResource(R.string.pdf_edit_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.original_preserved), style = MaterialTheme.typography.bodySmall)
    }
    if (filterPicker) FilterPicker(page, vm.repository, busy,
        onFilter = { vm.filter(page.id, it) }, onStrength = { vm.filterStrength(page.id, it) }, onDismiss = { filterPicker = false },
        onApplyToAll = if (pages.size > 1) ({ vm.applyFilterToDocument(page.id); filterPicker = false }) else null)
    if (adjusting) AdjustmentSheet(page, vm.repository,
        onApply = { vm.adjustments(page.id, it); adjusting = false }, onDismiss = { adjusting = false })
}
