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
import com.docscan.rebuild.imaging.OfflineOcr
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
            catch (e: LinkageError) { android.util.Log.e("DocScanRebuild", "Native feature unavailable", e); notices.send(R.string.processing_error) }
            catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Operation failed", e); notices.send(R.string.error_generic) }
            finally { busy = false }
        }
    }
    suspend fun receive(bookId: String, file: File, detectBounds: Boolean = true): Sheet {
        try {
            val page = store.captured(bookId, file)
            // The durable master already exists before proposing the crop.
            try {
                val frame = if (detectBounds) withContext(Dispatchers.Default) { ImagePipeline.detect(store.source(page)) } else null
                if (frame != null) store.save(page, page.edit().copy(frame = frame))
            } catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { notices.send(R.string.error_memory) }
            catch (e: Exception) { notices.send(R.string.processing_error) }
            return store.dao.page(page.id) ?: page
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
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize().testTag("rendered-page-image"), contentScale = ContentScale.Fit) }
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
    var ocrText by rememberSaveable { mutableStateOf("") }
    var ocrLanguage by rememberSaveable { mutableStateOf("heb") }
    // A new scan stays a draft until the user saves it; leaving a draft deletes it after confirmation.
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrMode by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var importBook by rememberSaveable { mutableStateOf<String?>(null) }
    val books by vm.library.collectAsState()
    val trash by vm.trash.collectAsState()
    val pages by remember(bookId) { bookId?.let { store.dao.pages(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val currentTitle = books.firstOrNull { it.book.id == bookId }?.book?.title ?: stringResource(R.string.document)
    val snack = remember { SnackbarHostState() }
    val defaultTitle = { context.getString(R.string.title_scan, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())) }
    LaunchedEffect(vm) { for (message in vm.notices) snack.showSnackbar(context.getString(message)) }
    LaunchedEffect(Unit) {
        runCatching { store.removeEmpty(setOfNotNull(draft, bookId)) }.onFailure { android.util.Log.w("DocScanRebuild", "Empty document cleanup failed", it) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.perform(R.string.imported) {
            val target = importBook ?: store.create(defaultTitle()).id
            if (target == draft) draft = null
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
    val backupSaver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null)vm.perform(R.string.backup_saved) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { LibraryBackup(store).export(it) } }
        }
    }
    val backupLoader=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)vm.perform(R.string.backup_restored) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { LibraryBackup(store).restore(it) } }
        }
    }
    val textSaver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null)vm.perform(R.string.text_saved) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use { it.write(ocrText) } }
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
    fun sharePdf(targetId: String? = bookId) {
        val id = targetId ?: return
        vm.perform {
            val file = DocumentOutput(context, store).pdf(id)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newRawUri("PDF", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        }
    }
    fun startScan(recognize: Boolean = false) { vm.perform { val created = store.create(defaultTitle()).id; bookId = created; draft = created; ocrMode = recognize; route = "camera" } }
    fun discardDraft() { val id = draft ?: return; confirmDiscard = false; vm.perform { store.discard(id); draft = null; ocrMode = false; bookId = null; pageId = null; route = "library" } }
    /** Leaving an unsaved scan: nothing captured is removed silently; captured pages need confirmation. */
    fun leaveDraft() { if (pages.isEmpty()) discardDraft() else confirmDiscard = true }
    fun recognize(id: String) { vm.perform { val page = requireNotNull(store.dao.page(id)); ocrText = OfflineOcr(store).recognize(page, ocrLanguage) } }
    BackHandler(route != "library" || vm.busy) {
        if (vm.busy) return@BackHandler
        if (draft != null && (route == "editor" || route == "review" || route == "camera" || route == "document")) leaveDraft()
        else route = if (route == "ocr") "editor" else if (route == "editor" || route == "review" || route == "camera") "document" else "library"
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.unsaved_title)) }, text = { Text(stringResource(R.string.unsaved_message)) },
        confirmButton = { TextButton(::discardDraft, modifier = Modifier.testTag("discard-scan")) { Text(stringResource(R.string.leave_without_saving), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } })
    ScannerScreenTheme(dark = route == "camera" || route == "editor" || route == "review") {
    Box(Modifier.fillMaxSize()) {
        when (route) {
            "ocr" -> OcrScreen(ocrText,{ocrText=it},ocrLanguage,snack,
                onBack={route="editor"},onSave={textSaver.launch("recognized-text.txt")},
                onRecognize={pageId?.let(::recognize)},onLanguageRecognize={language->ocrLanguage=language;pageId?.let(::recognize)},
                onKeep={val id=pageId;if(id!=null)vm.perform(R.string.saved) {val page=requireNotNull(store.dao.page(id));store.dao.recognized(id,ocrText,page.recipe);draft=null} })
            "camera" -> bookId?.let { id ->
                CaptureScreen(store, pages, vm.busy, snack = snack, onDone = { vm.perform {
                    val captured = store.dao.snapshot(id)
                    if (captured.isEmpty() && id == draft) { store.discard(id); draft = null; ocrMode = false; bookId = null; route = "library" }
                    else { pageId = captured.firstOrNull()?.id; route = if (captured.isNotEmpty()) "review" else "document" }
                } }, onImport = { importBook = bookId; importer.launch(arrayOf("image/*")) }, onCaptured = { file, detectBounds, batch, complete ->
                    if (vm.busy) { file.delete(); complete(false) }
                    else vm.perform {
                        var ok = false; var recognizeNow: String? = null
                        try {
                            val captured = vm.receive(id, file, detectBounds)
                            if (!batch) { pageId = captured.id; if (ocrMode) { ocrMode = false; ocrText = ""; recognizeNow = captured.id; route = "ocr" } else route = "editor" }
                            ok = true
                        } finally { complete(ok) }
                        // Text extraction from the home screen: the cropped page is recognized as soon as it is captured.
                        recognizeNow?.let { ocrText = OfflineOcr(store).recognize(requireNotNull(store.dao.page(it)), ocrLanguage) }
                    }
                })
            }
            "editor", "review" -> pages.firstOrNull { it.id == pageId }?.let { page ->
                PageEditor(store, page, vm.busy, snack = snack, onBack = { if (draft != null) leaveDraft() else route = "document" }, onSave = { recipe -> vm.perform(R.string.saved) { store.save(page, recipe); draft = null; route = "document" } },
                    onExportImage = { recipe -> imagePage = page.id; imageRecipe = RecipeCodec.encode(recipe); imageSaver.launch("scan-${page.position + 1}.jpg") },
                    onGallery = { recipe -> requestGallery(page, recipe) },
                    onOriginal = { val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(page.mime) ?: "img"; sourceSaver.launch("original-${page.position + 1}.$extension") },
                    title = currentTitle, pageIndex = pages.indexOfFirst { it.id == page.id }, pageCount = pages.size, saveOnBack = route == "review" && draft == null,
                    onNavigate = { index, recipe -> pages.getOrNull(index)?.let { target -> vm.perform { store.save(page, recipe); pageId = target.id } } },
                    onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } },
                    onAddPages = { recipe -> vm.perform { store.save(page, recipe); route = "camera" } },
                    onSplit = { recipe,position,rightFirst -> vm.perform(R.string.book_split_saved) {pageId=store.split(page,recipe,position,rightFirst).id;draft=null;route="document"} },
                    onOcr = { recipe -> vm.perform {
                        store.save(page,recipe);val current=requireNotNull(store.dao.page(page.id))
                        ocrText=if(current.ocrRecipe==current.recipe)current.ocrText.orEmpty() else "";route="ocr"
                        // Opening text recognition recognizes at once; a saved result for this exact edit is reused.
                        if(ocrText.isEmpty())ocrText=OfflineOcr(store).recognize(current,ocrLanguage)
                    } })
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            "document" -> DocumentScreen(store, currentTitle, pages, onBack = { route = "library" }, onEdit = { pageId = it.id; route = "editor" },
                onCamera = { route = "camera" }, onImport = { importBook = bookId; importer.launch(arrayOf("image/*")) },
                onPdf = { pdfSaver.launch("${currentTitle.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.pdf") }, onGallery = { requestGallery() }, onShare = { sharePdf() },
                onMove = { page, direction -> bookId?.let { id -> vm.perform { store.move(id, page.id, direction) } } },
                onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } }, snack = snack)
            else -> LibraryScreen(store, books, trash, onScan = { startScan() }, onExtractText = { startScan(recognize = true) }, onImport = { importBook = null; importer.launch(arrayOf("image/*")) }, onOpen = { bookId = it; route = "document" },
                onShare = { id -> sharePdf(id) }, onPdf = { id ->
                    bookId = id
                    val title = books.firstOrNull { it.book.id == id }?.book?.title ?: context.getString(R.string.document)
                    pdfSaver.launch("${title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.pdf")
                }, onTrash = { ids, restore -> vm.perform(if (restore) R.string.restored else R.string.moved_to_trash) { store.trash(ids, restore) } }, snack = snack, onBackup={backupSaver.launch("DocScan-backup.zip")},onRestoreBackup={backupLoader.launch(arrayOf("application/zip","application/octet-stream"))})
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
}
