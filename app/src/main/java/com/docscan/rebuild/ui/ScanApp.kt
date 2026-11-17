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
import com.docscan.rebuild.imaging.PageFlattener
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
            catch (e: UserMessage) { notices.send(e.text) }
            catch (e: LinkageError) { android.util.Log.e("DocScanRebuild", "Native feature unavailable", e); notices.send(R.string.processing_error) }
            catch (e: Exception) { android.util.Log.e("DocScanRebuild", "Operation failed", e); notices.send(R.string.error_generic) }
            finally { busy = false }
        }
    }
    suspend fun receive(bookId: String, file: File, detectBounds: Boolean = true, seen: Frame? = null): Sheet {
        try {
            val page = store.captured(bookId, file)
            // The durable master already exists before proposing the crop.
            try {
                val detected = if (detectBounds) withContext(Dispatchers.Default) { ImagePipeline.detect(store.source(page)) } else null
                // The page keeps the outline the user saw in the camera when the shot was taken. Detection on the full
                // photo only refines it when both agree; otherwise it could crop a different shape than was shown.
                val frame = Frame.choose(detected, seen?.takeIf { detectBounds })
                // A detected page whose text lines bend (a book page curving to the spine) is straightened automatically;
                // a flat page or one without enough text keeps no correction. The editor can turn it off.
                // A book page whose top or bottom edge bows is cropped along its curved edges.
                val curved = if (frame == null) null else withContext(Dispatchers.Default) { runCatching { ImagePipeline.pageEdges(store.source(page), frame) }.getOrNull() }
                val cropped = if (frame != null) page.edit().copy(frame = frame, edges = curved?.values.orEmpty()) else page.edit()
                val flatten = if (frame == null) null else withContext(Dispatchers.Default) { runCatching { PageFlattener.estimate(store.source(page), cropped) }.getOrNull() }
                // Marks chosen in Settings (off by default) go on new pages only, and so does the filter for new scans
                // (Magic Color unless changed); the photo itself is kept as taken.
                val recipe = MarkSettings(store.context).applyTo(if (flatten != null) cropped.copy(flatten = flatten.toList()) else cropped)
                    .copy(treatment = ScanSettings(store.context).treatment)
                if (recipe != page.edit()) store.save(page, recipe)
            } catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { notices.send(R.string.error_memory) }
            catch (e: Exception) { notices.send(R.string.processing_error) }
            return store.dao.page(page.id) ?: page
        } finally { file.delete() }
    }
}

internal object PreviewCache {
    val slots = Semaphore(2)
    // An eighth of the app's memory (at least 32 MB) for rendered pages, so sharp previews stay cached.
    val memory = object : LruCache<String, Bitmap>(maxOf(32 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024 }
}

@Composable
fun PagePicture(store: ScanStore, page: Sheet, modifier: Modifier = Modifier, recipe: EditRecipe = page.edit(), edge: Int = 1800, description: String? = null, naturalHeight: Boolean = false) {
    val key = "${page.id}:$recipe:$edge"
    var failure by remember(key) { mutableIntStateOf(0) }
    var shownPage by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, key) {
        // While a new edit or a sharper version is drawn, the same page keeps showing; another page starts empty.
        val cached = PreviewCache.memory.get(key)
        if (cached != null) { value = cached; shownPage = page.id; return@produceState }
        if (shownPage != page.id) value = null
        run {
            try {
                value = PreviewCache.slots.withPermit { withContext(Dispatchers.IO) { ImagePipeline.render(store.source(page), recipe, edge).also { PreviewCache.memory.put(key, it) } } }
                shownPage = page.id
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
    // ID card tool: the camera asks for the front, then the back, and both become one A4 page.
    var idCard by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var importBook by rememberSaveable { mutableStateOf<String?>(null) }
    // A Tools-tab action waiting for the user to pick its document, and the tool the document screen should open.
    var pickFor by rememberSaveable { mutableStateOf<String?>(null) }
    var documentTool by rememberSaveable { mutableStateOf<String?>(null) }
    // The tool the editor opens with: the filters for a new scan, nothing for a saved page, or a Tools-tab tool.
    var editorStart by rememberSaveable { mutableStateOf<String?>(null) }
    // Retake: the page the next capture replaces.
    var retakeId by rememberSaveable { mutableStateOf<String?>(null) }
    // Scan modes from the Tools tab: book (split into two pages), whiteboard, slides, timestamp camera.
    var scanMode by rememberSaveable { mutableStateOf<String?>(null) }
    // Counting (1.3.5): the photo being counted (kept to be saved unchanged) and an upright copy to count on.
    var countPicture by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var countSource by rememberSaveable { mutableStateOf<String?>(null) }
    // Business card and receipt (1.3.4): read once the captured page is saved, then shown to check.
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    var contact by remember { mutableStateOf<BusinessCard.Contact?>(null) }
    var receipt by remember { mutableStateOf<Receipt.Summary?>(null) }
    var ocrBack by rememberSaveable { mutableStateOf("editor") }
    var merging by rememberSaveable { mutableStateOf(false) }
    // Signing one page from the page view.
    var signPage by rememberSaveable { mutableStateOf<String?>(null) }
    var signMark by remember { mutableStateOf<SignatureMark?>(null) }
    // Tags and locks are kept outside the database; their change counters refresh the screens.
    val tagStore = remember { DocumentTags(context) }
    val tagChanges by DocumentTags.changes.collectAsState()
    val tagMap = remember(tagChanges) { tagStore.all() }
    val lock = remember { DocumentLock(context) }
    val lockChanges by DocumentLock.changes.collectAsState()
    val lockedIds = remember(lockChanges) { lock.lockedIds() }
    // Folders (1.3.3): where each document is, and the folder the library shows; new documents go into it.
    val folderStore = remember { DocumentFolders(context) }
    val folderChanges by DocumentFolders.changes.collectAsState()
    val folderList = remember(folderChanges) { folderStore.folders() }
    val placement = remember(folderChanges) { folderStore.placement() }
    val lockedFolders = remember(lockChanges, folderChanges) { lock.lockedFolders() }
    var libraryFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var unlockingFolder by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var settingFolderCode by rememberSaveable { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var settingCode by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfSettingsOpen by rememberSaveable { mutableStateOf(false) }
    /** Runs [action] for a locked document only after its code is entered (once per use of the app). */
    fun guarded(id: String, action: () -> Unit) { if (lock.isOpen(id)) action() else unlocking = id to action }
    // Locked documents close again when the app has been in the background for five minutes.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stoppedAt = 0L
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> stoppedAt = System.currentTimeMillis()
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 5 * 60_000L) DocumentLock.closeAll()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer); onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val books by vm.library.collectAsState()
    val trash by vm.trash.collectAsState()
    val pages by remember(bookId) { bookId?.let { store.dao.pages(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val currentTitle = books.firstOrNull { it.book.id == bookId }?.book?.title ?: stringResource(R.string.document)
    val snack = remember { SnackbarHostState() }
    // Book mode puts the right-hand page first in a right-to-left language.
    val rightToLeft = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val defaultTitle = { context.getString(R.string.title_scan, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())) }
    LaunchedEffect(vm) { for (message in vm.notices) snack.showSnackbar(context.getString(message)) }
    LaunchedEffect(Unit) {
        runCatching { store.removeEmpty(setOfNotNull(draft, bookId)) }.onFailure { android.util.Log.w("DocScanRebuild", "Empty document cleanup failed", it) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.perform(R.string.imported) {
            val target = importBook ?: store.create(defaultTitle()).id.also { folderStore.move(listOf(it), libraryFolder) }
            if (target == draft) draft = null
            bookId = target; route = "document"
            val marks = MarkSettings(context)
            for (uri in uris) {
                val added = if (context.contentResolver.getType(uri) == "application/pdf") store.importPdf(target, uri) else listOf(store.import(target, uri))
                if (marks.active) added.forEach { store.save(it, marks.applyTo(it.edit())) }
            }
        }
    }
    var pdfSize by rememberSaveable { mutableStateOf(DocumentOutput.PdfSize.ORIGINAL) }
    var pdfPages by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // Held only in memory until the file is written; never saved.
    var pdfPassword by remember { mutableStateOf<String?>(null) }
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = bookId; val size = pdfSize; val only = pdfPages?.toSet(); val password = pdfPassword; pdfSize = DocumentOutput.PdfSize.ORIGINAL; pdfPages = null; pdfPassword = null
        if (uri != null && id != null) vm.perform(if (password != null) R.string.protected_pdf_saved else R.string.pdf_saved) {
            val output = DocumentOutput(context, store)
            val file = if (password != null) output.protectedPdf(id, password, size, only) else output.pdf(id, size, only)
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> file.inputStream().use { it.copyTo(out) } } } }
            finally { file.delete() }
        }
    }
    fun fileName(title: String, extension: String) = "${title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.$extension"
    fun copyTo(uri: android.net.Uri, file: File) = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> file.inputStream().use { it.copyTo(out) } }
    val excelSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val id = bookId
        if (uri != null && id != null) vm.perform(R.string.excel_saved) {
            // Tables need word positions, so every page is recognized now, on the device.
            val words = store.dao.snapshot(id).map { page -> OfflineOcr(store).words(page, ocrLanguage) }
            val file = withContext(Dispatchers.IO) { DocumentOutput(context, store).excel(words) { context.getString(R.string.sheet_page, it + 1) } }
            try { withContext(Dispatchers.IO) { copyTo(uri, file) } } finally { file.delete() }
        }
    }
    val powerPointSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.presentationml.presentation")) { uri ->
        val id = bookId
        if (uri != null && id != null) vm.perform(R.string.powerpoint_saved) {
            val file = DocumentOutput(context, store).powerPoint(id)
            try { withContext(Dispatchers.IO) { copyTo(uri, file) } } finally { file.delete() }
        }
    }
    val wordSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { uri ->
        val id = bookId
        if (uri != null && id != null) vm.perform(R.string.word_saved) {
            // Pages recognized for their current edit reuse that text; the others are recognized now, on the device.
            val texts = store.dao.snapshot(id).map { page -> if (page.ocrRecipe == page.recipe && page.ocrText != null) page.ocrText else OfflineOcr(store).recognize(page, ocrLanguage) }
            val file = DocumentOutput(context, store).word(texts)
            try { withContext(Dispatchers.IO) { copyTo(uri, file) } } finally { file.delete() }
        }
    }
    val longImageSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val id = bookId
        if (uri != null && id != null) vm.perform(R.string.image_saved) {
            val file = DocumentOutput(context, store).longImage(id)
            try { withContext(Dispatchers.IO) { copyTo(uri, file) } } finally { file.delete() }
        }
    }
    fun printDocument(id: String) {
        val title = books.firstOrNull { it.book.id == id }?.book?.title ?: context.getString(R.string.document)
        vm.perform { val file = DocumentOutput(context, store).pdf(id); com.docscan.rebuild.export.PdfPrint.print(context, title, file) }
    }
    fun markPages(change: (EditRecipe) -> EditRecipe) { val id = bookId ?: return; vm.perform(R.string.saved) { store.dao.snapshot(id).forEach { store.save(it, change(it.edit())) } } }
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
    // TXT to PDF (1.3.3): a text file becomes a document of A4 pages, each keeping its own text.
    val textImporter=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)vm.perform(R.string.imported) {
            val bytes=withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                // Up to 4 MB of text (about 2,000 pages).
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536);var total=0
                while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=4*1024*1024) { "Text file too large" }; out.write(buffer,0,n) }
                out.toByteArray() } }
            val name=runCatching { context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: defaultTitle()
            val book=store.create(name.take(100)).id; folderStore.move(listOf(book),libraryFolder)
            store.textPages(book,com.docscan.rebuild.export.TextPages.decode(bytes)); bookId=book; route="document"
        }
    }
    val countPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null)vm.perform {
            // An upright copy, reduced (by powers of two) towards 2000 pixels on its long side, to count on.
            val file=File(context.cacheDir,"count-${System.nanoTime()}")
            try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { input.copyTo(it) } } }
                countPicture=withContext(Dispatchers.Default) { ImagePipeline.decode(file,2000) }; countSource=uri.toString(); route="count"
            } finally { file.delete() }
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
    /** Sends the document as a PDF to an email app, with its title as the subject. */
    fun emailPdf(targetId: String? = bookId) {
        val id = targetId ?: return
        val title = books.firstOrNull { it.book.id == id }?.book?.title ?: context.getString(R.string.document)
        vm.perform {
            val file = DocumentOutput(context, store).pdf(id)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title)
                .apply { clipData = ClipData.newRawUri("PDF", uri); selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { context.startActivity(Intent.createChooser(intent, context.getString(R.string.email))) }
            catch (e: android.content.ActivityNotFoundException) { intent.selector = null; context.startActivity(Intent.createChooser(intent, context.getString(R.string.email))) }
        }
    }
    /** Shares one page as a JPEG image, as it looks with its edits. */
    fun sharePage(page: Sheet) {
        vm.perform {
            val file = DocumentOutput(context, store).image(page, page.edit())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newRawUri("JPEG", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        }
    }
    fun openEditor(page: Sheet, start: String? = null) { pageId = page.id; editorStart = start; route = "editor" }
    fun deletePage(page: Sheet, then: () -> Unit = {}) { vm.perform(R.string.page_deleted) { store.deletePage(page); then() } }
    fun rotatePage(page: Sheet) {
        vm.perform { val r = page.edit(); store.save(page, r.turnedBy(3, CropGeometry.dimensions(r.frame, page.width, page.height, r.outputAspect).rotated(r.turns).aspect)) }
    }
    fun applyHeader(choice: HeaderChoice) {
        val id = bookId ?: return
        val now = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        vm.perform(R.string.saved) {
            store.dao.snapshot(id).forEach { page ->
                val r = page.edit()
                store.save(page, r.copy(footer = choice.footer, footerPlace = choice.footerPlace, stamp = if (choice.stamp) r.stamp ?: now else null, stampPlace = choice.stampPlace, noteColor = choice.colour))
            }
            store.numberPages(id, choice.numbers, choice.numberPlace, choice.colour)
        }
    }
    fun watermarkAll(r: EditRecipe) = markPages { it.copy(watermark = r.watermark, watermarkPlace = r.watermarkPlace, watermarkStyle = r.watermarkStyle, watermarkSpot = r.watermarkSpot) }
    /** Colour marks off on every page when all have them erased; otherwise erased on every page. */
    fun toggleColourMarks(id: String) {
        vm.perform(R.string.saved) {
            val all = store.dao.snapshot(id); val on = !all.all { it.edit().eraseColor }
            all.forEach { store.save(it, it.edit().copy(eraseColor = on)) }
        }
    }
    /** After a spread is split, each page measures its own curved edges and text-line straightening on its own crop. */
    suspend fun straightenHalves(page: Sheet) {
        store.dao.snapshot(page.notebookId).filter { it.source == page.source }.forEach { half ->
            val curved = withContext(Dispatchers.Default) { runCatching { ImagePipeline.pageEdges(store.source(half), half.edit().frame) }.getOrNull() }
            val edged = half.edit().copy(edges = curved?.values.orEmpty())
            val coef = withContext(Dispatchers.Default) { runCatching { PageFlattener.estimate(store.source(half), edged) }.getOrNull() }
            val next = if (coef != null) edged.copy(flatten = coef.toList()) else edged
            if (next != half.edit()) store.save(half, next)
        }
    }

    /** What a scan mode does to each captured page. Book mode cuts a detected spread into its two pages at the spine. */
    suspend fun applyMode(page: Sheet, mode: String?): Sheet {
        val recipe = page.edit()
        return when (mode) {
            "board" -> { store.save(page, recipe.copy(treatment = Treatment.NATIVE_BOARD)); requireNotNull(store.dao.page(page.id)) }
            "slides" -> { store.save(page, recipe.copy(treatment = Treatment.COLOR)); requireNotNull(store.dao.page(page.id)) }
            "stamp" -> { store.save(page, recipe.copy(stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()), stampPlace = MarkSettings(context).stampPlace)); requireNotNull(store.dao.page(page.id)) }
            "book" -> {
                if (recipe.frame == Frame.FULL) page else {
                    val gutter = withContext(Dispatchers.Default) { runCatching { com.docscan.rebuild.imaging.BookGutter.find(store.source(page), recipe) }.getOrDefault(.5f) }
                    val rightFirst = rightToLeft
                    val first = runCatching { store.split(page, recipe.copy(flatten = emptyList()), gutter, rightFirst) }.getOrNull() ?: return page
                    straightenHalves(page)
                    requireNotNull(store.dao.page(first.id))
                }
            }
            else -> page
        }
    }
    fun startScan(recognize: Boolean = false, card: Boolean = false, mode: String? = null) { vm.perform { val created = store.create(if (card) context.getString(R.string.id_card) else defaultTitle()).id; folderStore.move(listOf(created), libraryFolder); bookId = created; draft = created; ocrMode = recognize; idCard = card; scanMode = mode; editorStart = "filters"; route = "camera" } }
    fun discardDraft() { val id = draft ?: return; confirmDiscard = false; vm.perform { store.discard(id); draft = null; ocrMode = false; idCard = false; scanMode = null; bookId = null; pageId = null; route = "library" } }
    /** Leaving an unsaved scan: nothing captured is removed silently; captured pages need confirmation. */
    fun leaveDraft() { if (pages.isEmpty()) discardDraft() else confirmDiscard = true }
    fun recognize(id: String) { vm.perform { val page = requireNotNull(store.dao.page(id)); ocrText = OfflineOcr(store).recognize(page, ocrLanguage) } }
    BackHandler(route != "library" || vm.busy) {
        if (vm.busy) return@BackHandler
        if (retakeId != null && route == "camera") { retakeId = null; route = "editor" }
        else if (draft != null && (route == "editor" || route == "review" || route == "camera" || route == "document")) leaveDraft()
        else route = if (route == "ocr") ocrBack else if (route == "editor" || route == "review" || route == "camera" || route == "page" || route == "watermark") "document" else "library"
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.unsaved_title)) }, text = { Text(stringResource(R.string.unsaved_message)) },
        confirmButton = { TextButton(::discardDraft, modifier = Modifier.testTag("discard-scan")) { Text(stringResource(R.string.leave_without_saving), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } })
    pickFor?.let { tool ->
        // Document tools from the Tools tab work on a document the user picks here.
        val choices = books.filter { it.pageCount > 0 }
        AlertDialog(onDismissRequest = { pickFor = null }, title = { Text(stringResource(R.string.choose_document)) },
            text = {
                if (choices.isEmpty()) Text(stringResource(R.string.no_documents_for_tool))
                else LazyColumn(Modifier.heightIn(max = 360.dp).testTag("document-picker")) {
                    items(choices, key = { it.book.id }) { entry ->
                        TextButton({
                            pickFor = null; val id = entry.book.id
                            val title = entry.book.title
                            guarded(id) { bookId = id; when (tool) {
                                "print" -> printDocument(id)
                                "word" -> wordSaver.launch(fileName(title, "docx"))
                                "excel" -> excelSaver.launch(fileName(title, "xlsx"))
                                "powerpoint" -> powerPointSaver.launch(fileName(title, "pptx"))
                                "long" -> longImageSaver.launch(fileName(title, "jpg"))
                                "images" -> requestGallery()
                                "timestamp" -> { route = "document"; val now = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()); val marks = MarkSettings(context); markPages { it.copy(stamp = now, stampPlace = marks.stampPlace, noteColor = if (it.footer != null) it.noteColor else marks.noteColor) } }
                                "erase", "text" -> vm.perform { store.dao.snapshot(id).firstOrNull()?.let { openEditor(it, tool) } }
                                "erasecolor" -> { route = "document"; toggleColourMarks(id) }
                                "email" -> emailPdf(id)
                                "lock" -> { route = "document"; if (lock.locked(id)) lock.setLocked(id, false) else if (lock.hasCode) { lock.setLocked(id, true); lock.opened(id) } else settingCode = id }
                                "collage" -> { documentTool = "collage"; route = "document" }
                                else -> { documentTool = tool; route = "document" }
                            } }
                        }, Modifier.fillMaxWidth()) {
                            Text(entry.book.title, Modifier.fillMaxWidth(), maxLines = 2)
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ pickFor = null }) { Text(stringResource(R.string.cancel)) } })
    }
    unlocking?.let { (id, action) ->
        PinDialog(R.string.lock_enter, confirm = false, onDismiss = { unlocking = null }, check = { lock.check(it) }) { lock.opened(id); unlocking = null; action() }
    }
    contact?.let { found -> ContactDialog(found, onDismiss = { contact = null }) }
    receipt?.let { found ->
        ReceiptDialog(found, onDismiss = { receipt = null }) { name ->
            receipt = null
            bookId?.let { id -> vm.perform(R.string.saved) {
                if (name.isNotBlank()) store.dao.rename(id, name.take(100))
                tagStore.set(id, (tagStore.of(id) + context.getString(R.string.receipts_tag)).distinct())
            } }
        }
    }
    unlockingFolder?.let { (id, action) ->
        PinDialog(R.string.lock_enter, confirm = false, onDismiss = { unlockingFolder = null }, check = { lock.check(it) }) { lock.openedFolder(id); unlockingFolder = null; action() }
    }
    settingFolderCode?.let { id ->
        PinDialog(R.string.lock_new_code, confirm = true, onDismiss = { settingFolderCode = null }) { code -> lock.setCode(code); lock.setFolderLocked(id, true); lock.openedFolder(id); settingFolderCode = null }
    }
    settingCode?.let { id ->
        PinDialog(R.string.lock_new_code, confirm = true, onDismiss = { settingCode = null }) { code -> lock.setCode(code); lock.setLocked(id, true); lock.opened(id); settingCode = null }
    }
    if (pdfSettingsOpen) PdfSettingsDialog(onDismiss = { pdfSettingsOpen = false })
    // A locked document reached without its code (the app was restored or left for a while) asks for it first.
    val gate = bookId?.takeIf { lockChanges >= 0 && route in setOf("document", "page", "editor", "review", "watermark") && draft == null && !lock.isOpen(it) }
    if (gate != null && unlocking == null) PinDialog(R.string.lock_enter, confirm = false, onDismiss = { route = "library" }, check = { lock.check(it) }) { lock.opened(gate) }
    if (merging) MergeDialog(books.filter { it.pageCount > 0 && lock.isOpen(it.book.id) }, onDismiss = { merging = false }) { ids ->
        merging = false
        val name = books.firstOrNull { it.book.id == ids.first() }?.book?.title ?: context.getString(R.string.document)
        vm.perform(R.string.merged) { val merged = store.merge(ids, context.getString(R.string.merged_title, name)); bookId = merged.id; route = "document" }
    }
    signPage?.let { id ->
        val mark = signMark
        if (mark == null) SignatureChooser(onChosen = { signMark = it }, onDismiss = { signPage = null })
        else SignaturePlacer(store, pages, mark, onApply = { page, placed -> signPage = null; signMark = null
                vm.perform(R.string.saved) { val current = requireNotNull(store.dao.page(page.id)); store.save(current, current.edit().let { it.copy(signatures = it.signatures + placed) }) } },
            onRemoveAll = { page -> signPage = null; signMark = null; vm.perform(R.string.saved) { val current = requireNotNull(store.dao.page(page.id)); store.save(current, current.edit().copy(signatures = emptyList())) } },
            onDismiss = { signPage = null; signMark = null }, startIndex = pages.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }
    ScannerScreenTheme(dark = route == "camera" || route == "editor" || route == "review" || route == "watermark") {
    Box(Modifier.fillMaxSize()) {
        when (route) {
            "qr" -> QrScreen(onBack = { route = "library" })
            "radar" -> DocRadarScreen(vm.busy, onBack = { route = "library" }) { uris ->
                vm.perform(R.string.imported) {
                    val book = store.create(defaultTitle()).id; folderStore.move(listOf(book), libraryFolder)
                    // Each photo is kept unchanged and goes through what a new scan does: page crop, curved edges,
                    // straightening and the filter for new scans.
                    for (uri in uris) {
                        val file = File(context.cacheDir, "radar-${System.nanoTime()}")
                        withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { input.copyTo(it) } } }
                        vm.receive(book, file)
                    }
                    bookId = book; route = "document"
                }
            }
            "count" -> {
                val picture = countPicture; val source = countSource
                if (picture == null || source == null) LaunchedEffect(Unit) { route = "library" }
                else CountScreen(picture, vm.busy, onBack = { route = "library"; countPicture = null }) { found, size ->
                    vm.perform(R.string.saved) {
                        // The photo itself, unchanged, with the count drawn over it as marks that can be edited.
                        val title = context.getString(R.string.count_title, found.size)
                        val book = store.create(title).id; folderStore.move(listOf(book), libraryFolder)
                        val page = store.import(book, android.net.Uri.parse(source))
                        val (rings, numbers) = countMarks(found, size, picture.width, picture.height)
                        store.save(page, page.edit().copy(shapes = rings, texts = numbers, footer = title))
                        bookId = book; route = "document"; countPicture = null
                    }
                }
            }
            "web" -> WebPageScreen(vm.busy, onBack = { route = "library" }) { title, slices ->
                vm.perform(R.string.imported) {
                    val book = store.create(title.trim().take(100).ifEmpty { defaultTitle() }).id; folderStore.move(listOf(book), libraryFolder)
                    slices.forEach { store.picture(book, it) }; bookId = book; route = "document"
                }
            }
            "idphoto" -> IdPhotoScreen(vm.busy, onBack = { route = "library" }) { file, crop, size, backdrop ->
                vm.perform(R.string.idphoto_created) {
                    val book = store.create(context.getString(R.string.idphoto_title, DateFormat.getDateInstance(DateFormat.SHORT).format(Date())))
                    store.idPhotos(book.id, file, crop, size, backdrop); bookId = book.id; route = "document"
                }
            }
            "ocr" -> OcrScreen(ocrText,{ocrText=it},ocrLanguage,snack,
                onBack={route=ocrBack},onSave={textSaver.launch("recognized-text.txt")},
                onRecognize={pageId?.let(::recognize)},onLanguageRecognize={language->ocrLanguage=language;pageId?.let(::recognize)},
                onKeep={val id=pageId;if(id!=null)vm.perform(R.string.saved) {val page=requireNotNull(store.dao.page(id));store.dao.recognized(id,ocrText,page.recipe);draft=null} })
            "camera" -> bookId?.let { id ->
                CaptureScreen(store, pages, vm.busy, snack = snack, onDone = { vm.perform {
                    val captured = store.dao.snapshot(id)
                    if (retakeId != null) { retakeId = null; route = "editor" }
                    else if (captured.isEmpty() && id == draft) { store.discard(id); draft = null; ocrMode = false; idCard = false; bookId = null; route = "library" }
                    else if (scanMode == "long" && captured.size >= 2) {
                        val joined = store.longScan(id, captured, context.getString(R.string.longscan_parts, currentTitle))
                        scanMode = null; editorStart = "filters"; pageId = joined.id; route = "review"
                    }
                    else if (idCard && captured.size == 1) {
                        // Finished after the front only: a one-sided card page.
                        val card = store.idCard(id, captured[0], null); val marks = MarkSettings(context)
                        if (marks.active) store.save(card, marks.applyTo(card.edit()))
                        idCard = false; draft = null; route = "document"
                    }
                    else { idCard = false; scanMode = null; editorStart = "filters"; pageId = captured.firstOrNull()?.id; route = if (captured.isNotEmpty()) "review" else "document" }
                } }, onImport = { importBook = bookId; importer.launch(arrayOf("image/*", "application/pdf")) },
                prompt = if (retakeId != null) R.string.retake_hint else if (idCard) (if (pages.isEmpty()) R.string.id_card_front else R.string.id_card_back)
                    else when (scanMode) { "book" -> R.string.book_hint; "board" -> R.string.tool_whiteboard; "slides" -> R.string.tool_slides; "stamp" -> R.string.timestamp_camera
                        "bizcard" -> R.string.bizcard_hint; "receipt" -> R.string.receipt_hint; "long" -> R.string.longscan_hint; else -> null },
                onCaptured = { file, detectBounds, batch, seen, complete ->
                    if (vm.busy) { file.delete(); complete(false) }
                    else vm.perform {
                        var ok = false; var recognizeNow: String? = null
                        val replacing = retakeId
                        if (replacing != null) {
                            try {
                                val old = requireNotNull(store.dao.page(replacing))
                                val fresh = store.replace(old, vm.receive(id, file, detectBounds, seen))
                                retakeId = null; ok = true; openEditor(fresh)
                            } finally { complete(ok) }
                            return@perform
                        }
                        try {
                            val captured = applyMode(vm.receive(id, file, detectBounds, seen), scanMode)
                            if (scanMode == "bizcard" || scanMode == "receipt") reading = scanMode
                            val sides = if (idCard) store.dao.snapshot(id) else emptyList()
                            if (idCard && sides.size >= 2) {
                                val card = store.idCard(id, sides[0], sides[1]); val marks = MarkSettings(context)
                                if (marks.active) store.save(card, marks.applyTo(card.edit()))
                                idCard = false; draft = null; route = "document"
                            } else if (idCard) { /* the back is next */ } else if (!batch && scanMode != "long") { pageId = captured.id; scanMode = null; if (ocrMode) { ocrMode = false; ocrText = ""; ocrBack = "editor"; recognizeNow = captured.id; route = "ocr" } else { editorStart = "filters"; route = "editor" } }
                            ok = true
                        } finally { complete(ok) }
                        // Text extraction from the home screen: the cropped page is recognized as soon as it is captured.
                        recognizeNow?.let { ocrText = OfflineOcr(store).recognize(requireNotNull(store.dao.page(it)), ocrLanguage) }
                    }
                })
            }
            "editor", "review" -> pages.firstOrNull { it.id == pageId }?.let { page ->
                PageEditor(store, page, vm.busy, snack = snack, onBack = { if (draft != null) leaveDraft() else route = "document" }, onSave = { recipe -> editorStart = null; vm.perform(R.string.saved) {
                    // Curved edges and straightening are measured on the crop; a new crop measures them again.
                    val moved = recipe.frame != page.edit().frame
                    val edged = if (moved && recipe.edges.isNotEmpty())
                        recipe.copy(edges = withContext(Dispatchers.Default) { runCatching { ImagePipeline.pageEdges(store.source(page), recipe.frame) }.getOrNull() }?.values.orEmpty()) else recipe
                    val fresh = if (edged.flatten.isNotEmpty() && moved)
                        edged.copy(flatten = withContext(Dispatchers.Default) { runCatching { PageFlattener.estimate(store.source(page), edged) }.getOrNull() }?.toList().orEmpty()) else edged
                    store.save(page, fresh); draft = null; route = "document"
                    reading?.let { kind ->
                        reading = null
                        val text = OfflineOcr(store).recognize(requireNotNull(store.dao.page(page.id)), "heb+eng")
                        if (kind == "bizcard") contact = BusinessCard.parse(text) else receipt = Receipt.parse(text)
                    } } },
                    onExportImage = { recipe -> imagePage = page.id; imageRecipe = RecipeCodec.encode(recipe); imageSaver.launch("scan-${page.position + 1}.jpg") },
                    onGallery = { recipe -> requestGallery(page, recipe) },
                    onOriginal = { val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(page.mime) ?: "img"; sourceSaver.launch("original-${page.position + 1}.$extension") },
                    title = currentTitle, pageIndex = pages.indexOfFirst { it.id == page.id }, pageCount = pages.size, saveOnBack = route == "review" && draft == null,
                    onNavigate = { index, recipe -> pages.getOrNull(index)?.let { target -> vm.perform { store.save(page, recipe); pageId = target.id } } },
                    onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } },
                    onAddPages = { recipe -> vm.perform { store.save(page, recipe); editorStart = "filters"; route = "camera" } },
                    startTool = editorStart ?: if (route == "review") "filters" else null, language = ocrLanguage,
                    onDelete = { deletePage(page) { pageId = null; route = "document" } },
                    onRetake = { retakeId = page.id; route = "camera" },
                    onWatermarkAll = { recipe -> watermarkAll(recipe) }, onHeader = { choice -> applyHeader(choice) },
                    onSplit = { recipe,position,rightFirst -> vm.perform(R.string.book_split_saved) {pageId=store.split(page,recipe,position,rightFirst).id;straightenHalves(page);draft=null;route="document"} },
                    onOcr = { recipe -> vm.perform {
                        store.save(page,recipe);val current=requireNotNull(store.dao.page(page.id))
                        ocrText=if(current.ocrRecipe==current.recipe)current.ocrText.orEmpty() else "";ocrBack="editor";route="ocr"
                        // Opening text recognition recognizes at once; a saved result for this exact edit is reused.
                        if(ocrText.isEmpty())ocrText=OfflineOcr(store).recognize(current,ocrLanguage)
                    } })
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            "page" -> PageViewer(store, pages, pageId, currentTitle, vm.busy, snack, onBack = { route = "document" }, onPage = { pageId = it.id },
                onEdit = { openEditor(it) },
                onOcr = { p -> pageId = p.id; vm.perform { val current = requireNotNull(store.dao.page(p.id)); ocrText = if (current.ocrRecipe == current.recipe) current.ocrText.orEmpty() else ""; ocrBack = "page"; route = "ocr"
                    if (ocrText.isEmpty()) ocrText = OfflineOcr(store).recognize(current, ocrLanguage) } },
                onSign = { p -> signMark = null; signPage = p.id }, onShare = { p -> sharePage(p) },
                onDelete = { p -> deletePage(p) { if (pages.size <= 1) route = "document" } },
                onRotate = { p -> rotatePage(p) }, onGallery = { p -> requestGallery(p, p.edit()) },
                onSaveImage = { p -> imagePage = p.id; imageRecipe = p.recipe; imageSaver.launch("scan-${p.position + 1}.jpg") },
                onRetake = { p -> retakeId = p.id; pageId = p.id; route = "camera" },
                onOriginal = { p -> pageId = p.id; val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(p.mime) ?: "img"; sourceSaver.launch("original-${p.position + 1}.$extension") })
            "watermark" -> pages.firstOrNull()?.let { first ->
                WatermarkTool(store, first, first.edit(), offerAllPages = false, onDone = { recipe, _ -> watermarkAll(recipe); route = "document" }, onCancel = { route = "document" })
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            "document" -> DocumentScreen(store, currentTitle, pages, onBack = { route = "library" }, onEdit = { openEditor(it) },
                onOpen = { pageId = it.id; route = "page" }, onPageTool = { tool -> pages.firstOrNull()?.let { openEditor(it, tool) } },
                onDeletePages = { ids -> vm.perform(R.string.page_deleted) { ids.forEach { id -> store.dao.page(id)?.let { store.deletePage(it) } } } },
                onEraseColor = { bookId?.let(::toggleColourMarks) }, onEmail = { emailPdf() },
                tags = bookId?.let { tagMap[it] }.orEmpty(), allTags = tagMap.values.flatten().distinct().sorted(), onTags = { list -> bookId?.let { tagStore.set(it, list) } },
                locked = bookId?.let { it in lockedIds } == true,
                onLock = { lockIt -> bookId?.let { id -> if (!lockIt) lock.setLocked(id, false) else if (lock.hasCode) { lock.setLocked(id, true); lock.opened(id) } else settingCode = id } },
                onCollage = { ids, columns, rows -> bookId?.let { id -> vm.perform(R.string.collage_created) { store.collage(id, ids.mapNotNull { store.dao.page(it) }, columns, rows, rightToLeft) } } },
                suggestName = { pages.firstOrNull()?.let { first ->
                    val text = OfflineOcr(store).layout(first, first.edit(), ocrLanguage + "+eng".takeIf { ocrLanguage == "heb" }.orEmpty())
                    if (text.width <= 0 || text.height <= 0) null else DocumentName.suggest(text.words.map { TextBox(it.text, it.left.toFloat() / text.width, it.top.toFloat() / text.height, it.right.toFloat() / text.width, it.bottom.toFloat() / text.height) })
                } },
                onPrint = { bookId?.let(::printDocument) }, onWord = { wordSaver.launch(fileName(currentTitle, "docx")) },
                onExcel = { excelSaver.launch(fileName(currentTitle, "xlsx")) }, onPowerPoint = { powerPointSaver.launch(fileName(currentTitle, "pptx")) }, onLongImage = { longImageSaver.launch(fileName(currentTitle, "jpg")) },
                onWatermark = { route = "watermark" }, onHeader = { choice -> applyHeader(choice) },
                onStamp = { add -> val now = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()); val marks = MarkSettings(context)
                    markPages { it.copy(stamp = if (add) now else null, stampPlace = marks.stampPlace, noteColor = if (it.footer != null) it.noteColor else marks.noteColor) } },
                onExtract = { ids -> pdfPages = ids.toList(); pdfSaver.launch(fileName(currentTitle, "pdf")) },
                startTool = documentTool, onToolStarted = { documentTool = null },
                onCamera = { editorStart = "filters"; route = "camera" }, onImport = { importBook = bookId; importer.launch(arrayOf("image/*", "application/pdf")) },
                onPdf = { size -> pdfSize = size; pdfSaver.launch(fileName(currentTitle, "pdf")) },
                onProtectedPdf = { password -> pdfPassword = password; pdfSaver.launch(fileName(currentTitle, "pdf")) },
                onSign = { page, mark -> vm.perform(R.string.saved) {
                    val current = requireNotNull(store.dao.page(page.id)); val recipe = current.edit()
                    store.save(current, recipe.copy(signatures = if (mark == null) emptyList() else recipe.signatures + mark))
                } }, onGallery = { requestGallery() }, onShare = { sharePdf() },
                onMove = { page, direction -> bookId?.let { id -> vm.perform { store.move(id, page.id, direction) } } },
                onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } }, snack = snack)
            else -> LibraryScreen(store, books, trash, onScan = { startScan() }, onExtractText = { startScan(recognize = true) },
                onTool = { tool -> when (tool) { "scan" -> startScan(); "ocr" -> startScan(recognize = true); "idcard" -> startScan(card = true); "qr" -> route = "qr"
                    "book", "board", "slides" -> startScan(mode = tool); "stampscan" -> startScan(mode = "stamp"); "merge" -> merging = true
                    "idphoto" -> route = "idphoto"; "pdfsettings" -> pdfSettingsOpen = true
                    "txt2pdf" -> textImporter.launch(arrayOf("text/plain")); "web2pdf" -> route = "web"
                    "radar" -> route = "radar"; "count" -> countPicker.launch("image/*")
                    "bizcard", "receipt" -> startScan(mode = tool); "longscan" -> startScan(mode = "long")
                    "import" -> { importBook = null; importer.launch(arrayOf("image/*", "application/pdf")) }; else -> pickFor = tool } }, onImport = { importBook = null; importer.launch(arrayOf("image/*", "application/pdf")) },
                onOpen = { id -> guarded(id) { bookId = id; route = "document" } }, tags = tagMap, locked = remember(lockChanges, folderChanges, books) { lock.lockedDocuments(books.map { it.book.id }) },
                folders = folderList, placement = placement, lockedFolders = lockedFolders,
                folderActions = FolderActions(create = { name, parent -> folderStore.create(name, parent) }, rename = { id, name -> folderStore.rename(id, name) },
                    delete = { id -> lock.setFolderLocked(id, false); folderStore.delete(id) }, move = { ids, target -> folderStore.move(ids, target) },
                    // Unlocking a folder needs its code unless it is open; locking needs a code to be set first.
                    toggleLock = { id -> when { id in lock.lockedFolders() -> if (lock.folderOpen(id)) lock.setFolderLocked(id, false) else unlockingFolder = id to { lock.setFolderLocked(id, false) }
                        lock.hasCode -> { lock.setFolderLocked(id, true); lock.openedFolder(id) }; else -> settingFolderCode = id } },
                    open = { id, then -> if (lock.folderOpen(id)) then() else unlockingFolder = id to then }, shown = { libraryFolder = it }),
                onShare = { id -> guarded(id) { sharePdf(id) } }, onPdf = { id -> guarded(id) {
                    bookId = id
                    val title = books.firstOrNull { it.book.id == id }?.book?.title ?: context.getString(R.string.document)
                    pdfSaver.launch("${title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.pdf")
                } }, onTrash = { ids, restore -> vm.perform(if (restore) R.string.restored else R.string.moved_to_trash) { store.trash(ids, restore) } }, snack = snack, onDeleteForever = { ids -> vm.perform(R.string.deleted_forever) { store.deleteForever(ids) } }, onBackup={backupSaver.launch("DocScan-backup.zip")},onRestoreBackup={backupLoader.launch(arrayOf("application/zip","application/octet-stream"))})
        }
        // A downloaded update asks to be installed on whatever screen is open.
        UpdateReadyDialog()
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
