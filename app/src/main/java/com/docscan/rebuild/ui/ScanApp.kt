package com.docscan.rebuild.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.text.DateFormat
import java.util.Date

class ScanViewModel(val store: ScanStore) : ViewModel() {
    val library = store.dao.library().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trash = store.dao.library(true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var busy by mutableStateOf(false); private set
    val notices = Channel<Int>(Channel.BUFFERED)
    fun perform(success: Int? = null, action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { action(); if (success != null) notices.send(success) }
            catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { notices.send(R.string.error_memory) }
            catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Operation failed", e); notices.send(R.string.error_generic) }
            finally { busy = false }
        }
    }
    suspend fun receive(bookId: String, file: File, detectBounds: Boolean = true) {
        try {
            val page = store.captured(bookId, file)
            // The durable master already exists before proposing the crop.
            try {
                val frame = if (detectBounds) withContext(Dispatchers.Default) { ImagePipeline.detect(store.source(page)) } else null
                if (frame != null) store.save(page, page.edit().copy(frame = frame))
            } catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { notices.send(R.string.error_memory) }
            catch (e: Exception) { notices.send(R.string.processing_error) }
        } finally { file.delete() }
    }
}

private object PreviewCache {
    val slots = Semaphore(2)
    val memory = object : LruCache<String, Bitmap>(16 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024 }
}

@Composable
fun PagePicture(store: ScanStore, page: Sheet, modifier: Modifier = Modifier, recipe: EditRecipe = page.edit(), edge: Int = 1200, description: String? = null, naturalHeight: Boolean = false) {
    val key = "${page.id}:$recipe:$edge"
    var failure by remember(key) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(null, key) {
        value = PreviewCache.memory.get(key)
        if (value == null) {
            try {
                value = PreviewCache.slots.withPermit { withContext(Dispatchers.IO) { ImagePipeline.render(store.source(page), recipe, edge).also { PreviewCache.memory.put(key, it) } } }
            } catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { failure = R.string.error_memory }
            catch (e: Exception) { failure = R.string.processing_error }
        }
    }
    val dimensions = CropGeometry.dimensions(recipe.frame, page.width, page.height, recipe.outputAspect).rotated(recipe.turns)
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: dimensions.aspect
    Box(if (naturalHeight) modifier.aspectRatio(aspect) else modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: if (failure != 0) Text(stringResource(failure), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) else CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanApp(store: ScanStore) {
    val context = LocalContext.current
    val vm: ScanViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ScanViewModel(store) as T
    })
    var route by rememberSaveable { mutableStateOf("library") }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }
    var importBook by rememberSaveable { mutableStateOf<String?>(null) }
    val books by vm.library.collectAsState()
    val trash by vm.trash.collectAsState()
    val pages by remember(bookId) { bookId?.let { store.dao.pages(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val currentTitle = books.firstOrNull { it.book.id == bookId }?.book?.title ?: stringResource(R.string.document)
    val snack = remember { SnackbarHostState() }
    val defaultTitle = { context.getString(R.string.title_scan, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())) }
    LaunchedEffect(vm) { for (message in vm.notices) snack.showSnackbar(context.getString(message)) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.perform(R.string.imported) {
            val target = importBook ?: store.create(defaultTitle()).id
            bookId = target; route = "document"
            for (uri in uris) store.import(target, uri)
        }
    }
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = bookId
        if (uri != null && id != null) vm.perform(R.string.pdf_saved) {
            val file = DocumentOutput(context, store).pdf(id)
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> file.inputStream().use { it.copyTo(out) } } } }
            finally { file.delete() }
        }
    }
    val sourceSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pageId
        if (uri != null && id != null) vm.perform(R.string.source_saved) {
            val page = requireNotNull(store.dao.page(id))
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> store.source(page).inputStream().use { it.copyTo(out) } } }
        }
    }
    var imageRecipe by rememberSaveable { mutableStateOf<String?>(null) }
    var imagePage by rememberSaveable { mutableStateOf<String?>(null) }
    val imageSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val id = imagePage; val encoded = imageRecipe; imagePage = null; imageRecipe = null
        if (uri != null && id != null && encoded != null) vm.perform(R.string.image_saved) {
            val sheet = requireNotNull(store.dao.page(id))
            val file = DocumentOutput(context, store).image(sheet, RecipeCodec.decode(encoded))
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> file.inputStream().use { it.copyTo(out) } } } }
            finally { file.delete() }
        }
    }
    var galleryBook by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryPage by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryRecipe by rememberSaveable { mutableStateOf<String?>(null) }
    fun saveGallery(id: String) {
        val sheetId = galleryPage; val encoded = galleryRecipe; galleryPage = null; galleryRecipe = null
        vm.perform(R.string.gallery_saved) {
            val output = DocumentOutput(context, store)
            if (sheetId != null && encoded != null) output.galleryPage(requireNotNull(store.dao.page(sheetId)), RecipeCodec.decode(encoded)) else output.gallery(id)
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = galleryBook; galleryBook = null
        if (granted && id != null) saveGallery(id)
        else vm.perform { vm.notices.send(R.string.gallery_permission_denied) }
    }
    fun requestGallery(page: Sheet? = null, recipe: EditRecipe? = null) {
        val id = bookId ?: return
        galleryPage = page?.id; galleryRecipe = recipe?.let { RecipeCodec.encode(it) }
        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) saveGallery(id)
        else { galleryBook = id; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }
    fun sharePdf() {
        val id = bookId ?: return
        vm.perform {
            val file = DocumentOutput(context, store).pdf(id)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newRawUri("PDF", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        }
    }
    fun startScan() { vm.perform { bookId = store.create(defaultTitle()).id; route = "camera" } }
    BackHandler(route != "library" || vm.busy) { if (!vm.busy) route = if (route == "editor" || route == "camera") "document" else "library" }
    Box(Modifier.fillMaxSize()) {
        when (route) {
            "camera" -> bookId?.let { id ->
                CaptureScreen(store, pages, vm.busy, snack = snack, onDone = { route = "document" }, onCaptured = { file, detectBounds, complete ->
                    if (vm.busy) { file.delete(); complete(false) }
                    else vm.perform { var ok = false; try { vm.receive(id, file, detectBounds); ok = true } finally { complete(ok) } }
                })
            }
            "editor" -> pages.firstOrNull { it.id == pageId }?.let { page ->
                PageEditor(store, page, vm.busy, snack = snack, onBack = { route = "document" }, onSave = { recipe -> vm.perform(R.string.saved) { store.save(page, recipe); route = "document" } },
                    onExportImage = { recipe -> imagePage = page.id; imageRecipe = RecipeCodec.encode(recipe); imageSaver.launch("scan-${page.position + 1}.jpg") },
                    onGallery = { recipe -> requestGallery(page, recipe) },
                    onOriginal = { val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(page.mime) ?: "img"; sourceSaver.launch("original-${page.position + 1}.$extension") })
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            "document" -> DocumentScreen(store, currentTitle, pages, onBack = { route = "library" }, onEdit = { pageId = it.id; route = "editor" },
                onCamera = { route = "camera" }, onImport = { importBook = bookId; importer.launch(arrayOf("image/*")) },
                onPdf = { pdfSaver.launch("${currentTitle.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.pdf") }, onGallery = { requestGallery() }, onShare = ::sharePdf,
                onMove = { page, direction -> bookId?.let { id -> vm.perform { store.move(id, page.id, direction) } } },
                onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } }, snack = snack)
            else -> LibraryScreen(store, books, trash, onScan = ::startScan, onImport = { importBook = null; importer.launch(arrayOf("image/*")) }, onOpen = { bookId = it; route = "document" },
                onTrash = { ids, restore -> vm.perform(if (restore) R.string.restored else R.string.moved_to_trash) { store.trash(ids, restore) } }, snack = snack)
        }
        if (vm.busy) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .12f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(Modifier.size(26.dp)); Text(stringResource(R.string.busy)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(store: ScanStore, entries: List<LibraryEntry>, trash: List<LibraryEntry>, onScan: () -> Unit, onImport: () -> Unit, onOpen: (String) -> Unit, onTrash: (Set<String>, Boolean) -> Unit, snack: SnackbarHostState) {
    var search by rememberSaveable { mutableStateOf("") }
    var inTrash by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val visible = (if (inTrash) trash else entries).filter { it.book.title.contains(search, true) }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    LaunchedEffect(entries, trash, inTrash, search) { selected = selected.filter { id -> visible.any { it.book.id == id } } }
    BackHandler(selection || inTrash) { if (selection) { selection = false; selected = emptyList() } else inTrash = false }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Column { Text(if (inTrash) stringResource(R.string.trash) else "DocScan", style = MaterialTheme.typography.titleLarge); Text(if (selection) selected.size.toString() else stringResource(R.string.library), style = MaterialTheme.typography.labelMedium) } },
        actions = {
            if (selection) {
                IconButton(onClick = { selected = if (visible.all { it.book.id in selected }) emptyList() else visible.map { it.book.id } }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) }
                IconButton(onClick = { if (selected.isNotEmpty()) { onTrash(selected.toSet(), inTrash); selected = emptyList(); selection = false } }, enabled = selected.isNotEmpty()) {
                    Icon(if (inTrash) Icons.Outlined.RestoreFromTrash else Icons.Outlined.DeleteOutline, stringResource(if (inTrash) R.string.restore else R.string.move_to_trash))
                }
                IconButton(onClick = { selection = false; selected = emptyList() }) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
            } else {
                IconButton(onClick = { selection = true }) { Icon(Icons.Outlined.Checklist, stringResource(R.string.select_documents)) }
                IconButton(onClick = { inTrash = !inTrash; selected = emptyList() }) { Icon(if (inTrash) Icons.Outlined.Folder else Icons.Outlined.DeleteOutline, stringResource(if (inTrash) R.string.library else R.string.trash)) }
                IconButton(onImport) { Icon(Icons.Outlined.FileOpen, stringResource(R.string.import_images)) }
            }
        }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onScan, icon = { Icon(Icons.Outlined.DocumentScanner, null) }, text = { Text(stringResource(R.string.new_scan)) }, modifier = Modifier.testTag("new-scan")) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("library")) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), placeholder = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            if (inTrash) Text(stringResource(R.string.trash_hint), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (visible.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Description, null, Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp)); Text(stringResource(if (inTrash) R.string.empty_trash else R.string.empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.empty_hint), Modifier.padding(vertical = 12.dp)); OutlinedButton(onImport) { Text(stringResource(R.string.import_images)) }
            } else LazyColumn(Modifier.testTag("library-list"), contentPadding = PaddingValues(bottom = 110.dp)) {
                items(visible, key = { it.book.id }) { item ->
                    val documentPages by remember(item.book.id) { store.dao.pages(item.book.id) }.collectAsState(emptyList())
                    val cover = documentPages.firstOrNull()
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selection || inTrash) { selection = true; toggle(item.book.id) } else onOpen(item.book.id) }, onLongClick = { selection = true; toggle(item.book.id) }).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (selection) Checkbox(checked = item.book.id in selected, onCheckedChange = { toggle(item.book.id) }, modifier = Modifier.testTag("select-${item.book.id}"))
                        Surface(Modifier.size(62.dp, 82.dp), shape = RoundedCornerShape(7.dp), tonalElevation = 2.dp) { cover?.let { PagePicture(store, it, edge = 160) } }
                        Column(Modifier.weight(1f)) { Text(item.book.title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp)); Text(pluralStringResource(R.plurals.pages, item.pageCount, item.pageCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Outlined.ChevronLeft, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentScreen(store: ScanStore, title: String, pages: List<Sheet>, onBack: () -> Unit, onEdit: (Sheet) -> Unit, onCamera: () -> Unit, onImport: () -> Unit, onPdf: () -> Unit, onGallery: () -> Unit, onShare: () -> Unit, onMove: (Sheet, Int) -> Unit, onRename: (String) -> Unit, snack: SnackbarHostState) {
    var reorder by rememberSaveable { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }; var newTitle by remember(title) { mutableStateOf(title) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) }, text = { OutlinedTextField(newTitle, { newTitle = it }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (newTitle.isNotBlank()) { onRename(newTitle); rename = false } }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) } })
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Column(Modifier.clickable { rename = true }) { Text(title, maxLines = 1); Text(pluralStringResource(R.plurals.pages, pages.size, pages.size), style = MaterialTheme.typography.labelMedium) } },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
        actions = { IconButton(onImport) { Icon(Icons.Outlined.FileOpen, stringResource(R.string.import_images)) }; IconButton(onClick = { reorder = !reorder }) { Icon(Icons.Outlined.SwapVert, stringResource(R.string.reorder)) } }) },
        bottomBar = { Surface(shadowElevation = 6.dp) { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp).testTag("document-actions"), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onCamera) { Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.add_pages)) }
            IconButton(onShare, enabled = pages.isNotEmpty()) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
            Box {
                Button(onClick = { export = true }, enabled = pages.isNotEmpty(), modifier = Modifier.testTag("export-menu")) {
                    Icon(Icons.Outlined.Download, null); Text(stringResource(R.string.save))
                }
                DropdownMenu(expanded = export, onDismissRequest = { export = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_gallery)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                        onClick = { export = false; onGallery() }, modifier = Modifier.testTag("save-gallery"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                        onClick = { export = false; onPdf() })
                }
            }
        } } }) { padding ->
        if (pages.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_pages)) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("document-pages"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(pages, key = { it.id }) { page ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.page_number, pages.indexOf(page) + 1), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        if (reorder) { IconButton(onClick = { onMove(page, -1) }, enabled = page != pages.first()) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up)) }; IconButton(onClick = { onMove(page, 1) }, enabled = page != pages.last()) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down)) } }
                        TextButton(onClick = { onEdit(page) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Text(stringResource(R.string.edit)) }
                    }
                    Surface(Modifier.fillMaxWidth().clickable { onEdit(page) }, shadowElevation = 2.dp) {
                        PagePicture(store, page, Modifier.fillMaxWidth().testTag("page-image-${page.id}"), naturalHeight = true, description = stringResource(R.string.page_number, pages.indexOf(page) + 1))
                    }
                }
            }
        }
    }
}

