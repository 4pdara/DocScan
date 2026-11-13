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
import com.docscan.rebuild.imaging.OcrRequest
import com.docscan.rebuild.imaging.OcrProgress
import com.docscan.rebuild.imaging.OcrBatchResult
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
    val drafts = store.dao.drafts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trash = store.dao.library(true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var busy by mutableStateOf(false); private set
    val notices = Channel<Int>(Channel.BUFFERED)
    val ocrProgress = MutableStateFlow<OcrProgress?>(null)
    var ocrResult by mutableStateOf<OcrBatchResult?>(null); private set
    private var ocrRequest: OcrRequest? = null
    fun cancelOcr() { ocrRequest?.cancel() }
    fun recognize(action: suspend (OcrRequest, (OcrProgress) -> Unit) -> OcrBatchResult?) {
        perform {
            val request = OcrRequest(); ocrRequest = request; ocrResult = null
            ocrProgress.value = OcrProgress(1, 1, 0)
            try { ocrResult = action(request) { ocrProgress.value = it } }
            catch (cancelled: CancellationException) {
                if (!request.isCancelled) throw cancelled
                notices.send(R.string.ocr_cancelled)
            } finally { ocrRequest = null; ocrProgress.value = null }
        }
    }
    fun perform(success: Int? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
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
    var captureSession by rememberSaveable { mutableStateOf(false) }
    var capturePageIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var discardDialog by remember { mutableStateOf(false) }
    var scanMode by rememberSaveable { mutableStateOf<String?>(null) }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrText by rememberSaveable { mutableStateOf("") }
    var ocrLanguage by rememberSaveable { mutableStateOf("heb+eng") }
    var ocrLayoutName by rememberSaveable { mutableStateOf(OcrLayout.AUTO.name) }
    val ocrLayout = OcrLayout.valueOf(ocrLayoutName)
    var ocrReturn by rememberSaveable { mutableStateOf("editor") }
    var ocrRecipe by rememberSaveable { mutableStateOf("{}") }
    var exportTextBook by rememberSaveable { mutableStateOf<String?>(null) }
    val ocrProgress by vm.ocrProgress.collectAsState()
    var importBook by rememberSaveable { mutableStateOf<String?>(null) }
    val books by vm.library.collectAsState()
    val trash by vm.trash.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val pages by remember(bookId) { bookId?.let { store.dao.pages(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val currentTitle = (books+drafts).firstOrNull { it.book.id == bookId }?.book?.title ?: stringResource(R.string.document)
    val snack = remember { SnackbarHostState() }
    val defaultTitle = { context.getString(R.string.title_scan, androidx.core.text.BidiFormatter.getInstance().unicodeWrap(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()))) }
    LaunchedEffect(vm) { for (message in vm.notices) snack.showSnackbar(context.getString(message)) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.perform(R.string.imported) {
            val target = importBook ?: store.create(defaultTitle(),draft=true).id
            bookId = target
            try {
                val imported=mutableListOf<Sheet>()
                for(uri in uris){val sheet=store.import(target,uri);imported+=sheet;if(captureSession)capturePageIds=capturePageIds+sheet.id}
                if(captureSession){pageId=imported.first().id;route="review"}
                else {store.dao.finalize(target);route="document"}
            } catch(failure:Throwable) {
                if(importBook==null)withContext(NonCancellable){if(store.dao.snapshot(target).isEmpty())store.discardCapture(target,emptyList())}
                throw failure
            }
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
        val target = exportTextBook; exportTextBook = null
        if(uri!=null)vm.perform(R.string.text_saved) {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use {
                    if (target != null) DocumentOutput(context, store).text(target, it) else it.write(ocrText)
                }
            }
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
    fun startScan(mode: String?=null) { if(vm.busy)return;bookId=null;captureSession=true;capturePageIds=emptyList();scanMode=mode;route="camera" }
    fun leave() {
        if(captureSession && route in listOf("camera","editor","review"))discardDialog=true
        else route=if(route=="ocr")ocrReturn else if(route=="documentOcr")"document" else if(route in listOf("editor","review","camera"))"document" else "library"
    }
    BackHandler(route != "library" || vm.busy) { if(!vm.busy)leave() }
    if(discardDialog)AlertDialog(onDismissRequest={discardDialog=false},title={Text(stringResource(R.string.discard_scan_title))},text={Text(stringResource(R.string.discard_scan_hint))},
        confirmButton={TextButton(onClick={discardDialog=false;vm.perform { val id=bookId;val old=id?.let {store.dao.book(it)}
            if(id!=null)store.discardCapture(id,capturePageIds)
            captureSession=false;capturePageIds=emptyList();route=if(old!=null && !old.draft)"document" else "library"
        }},modifier=Modifier.testTag("discard-scan-confirm")){Text(stringResource(R.string.discard_scan))}},
        dismissButton={TextButton(onClick={discardDialog=false}){Text(stringResource(R.string.continue_scan))}})

    ScannerScreenTheme(dark = route == "camera" || route == "editor" || route == "review") {
    Box(Modifier.fillMaxSize()) {
        when (route) {
            "ocr" -> OcrScreen(ocrText,{ocrText=it},ocrLanguage,{ocrLanguage=it},snack,
                onBack={route=ocrReturn},onSave={exportTextBook=null;textSaver.launch("recognized-text.txt")},
                onRecognize={val id=pageId;if(id!=null)vm.recognize { request, progress ->
                    val page=requireNotNull(store.dao.page(id))
                    ocrText=OfflineOcr(store).recognize(page,options=OcrOptions(ocrLanguage,ocrLayout),request=request,progress=progress)
                    ocrRecipe=page.recipe
                    null
                }},onKeep={val id=pageId;if(id!=null)vm.perform(R.string.saved) {
                    check(store.dao.recognized(id,ocrText,ocrRecipe)==1) { "Page changed during text editing" }
                }},layout=ocrLayout,onLayout={ocrLayoutName=it.name})
            "documentOcr" -> DocumentOcrScreen(pages,ocrLanguage,{ocrLanguage=it},ocrLayout,{ocrLayoutName=it.name},vm.ocrResult,snack,
                onBack={route="document"},onRecognize={replace -> val id=bookId;if(id!=null)vm.recognize { request,progress ->
                    OfflineOcr(store).recognizeBook(id,OcrOptions(ocrLanguage,ocrLayout),replace,request,progress)
                }},onExport={exportTextBook=bookId;textSaver.launch("recognized-document.txt")},
                onPage={page -> pageId=page.id;ocrText=page.currentText().orEmpty();ocrRecipe=page.recipe;ocrReturn="documentOcr";route="ocr"})
            "camera" -> CaptureScreen(store,pages,vm.busy,snack=snack,onBack=::leave,onDone={vm.perform {
                val id=bookId;val captured=id?.let { store.dao.snapshot(it) }.orEmpty()
                if(captured.isEmpty())vm.notices.send(R.string.no_pages_short)
                else {pageId=captured.firstOrNull { it.id in capturePageIds }?.id?:captured.first().id;route="review"}
            }},onImport={ importBook=bookId;importer.launch(arrayOf("image/*")) },onCaptured={file,detectBounds,batch,complete ->
                if(vm.busy){file.delete();complete(false)} else vm.perform {var ok=false;try {
                    val id=bookId?:store.create(defaultTitle(),draft=true).id.also {bookId=it}
                    val captured=vm.receive(id,file,detectBounds);capturePageIds=capturePageIds+captured.id
                    val recipe=when(scanMode) {
                        "ID_PHOTO"->captured.edit().centerCrop(35f/45f,captured.width,captured.height)
                        "SLIDES"->captured.edit().copy(outputAspect=16f/9f)
                        "WHITEBOARD"->captured.edit().copy(treatment=Treatment.PAPER)
                        "TIMESTAMP"->captured.edit().copy(decorations=captured.edit().decorations.copy(timestamp=DateFormat.getDateTimeInstance().format(Date())))
                        else->captured.edit()
                    };store.save(captured,recipe)
                    if(!batch){pageId=captured.id;route="editor"};ok=true
                }finally{complete(ok)}}
            })
            "editor", "review" -> pages.firstOrNull { it.id == pageId }?.let { page ->
                PageEditor(store, page, vm.busy, snack = snack, onBack = ::leave, onSave = { recipe -> vm.perform(R.string.saved) { store.save(page, recipe);store.dao.finalize(page.notebookId);captureSession=false;capturePageIds=emptyList(); route = "document" } },
                    onExportImage = { recipe -> imagePage = page.id; imageRecipe = RecipeCodec.encode(recipe); imageSaver.launch("scan-${page.position + 1}.jpg") },
                    onGallery = { recipe -> requestGallery(page, recipe) },
                    onOriginal = { val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(page.mime) ?: "img"; sourceSaver.launch("original-${page.position + 1}.$extension") },
                    title = currentTitle, pageIndex = pages.indexOfFirst { it.id == page.id }, pageCount = pages.size, saveOnBack = false,
                    onNavigate = { index, recipe -> pages.getOrNull(index)?.let { target -> vm.perform { store.save(page, recipe); pageId = target.id } } },
                    onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } },
                    onAddPages = { recipe -> vm.perform { store.save(page, recipe);if(!captureSession){captureSession=true;capturePageIds=emptyList()}; route = "camera" } },
                    onSplit = { recipe,position,rightFirst -> vm.perform(R.string.book_split_saved) {pageId=store.split(page,recipe,position,rightFirst).id;store.dao.finalize(page.notebookId);captureSession=false;capturePageIds=emptyList();route="document"} },
                    onOcr = { recipe -> vm.perform {
                        store.save(page,recipe);val current=requireNotNull(store.dao.page(page.id))
                        ocrText=current.currentText().orEmpty();ocrRecipe=current.recipe;ocrReturn="editor";route="ocr"
                    } })
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            "document" -> DocumentScreen(store, currentTitle, pages, onBack = { route = "library" }, onEdit = { pageId = it.id; route = "editor" },
                onCamera = { captureSession=true;capturePageIds=emptyList();scanMode=null;route = "camera" }, onImport = { importBook = bookId; importer.launch(arrayOf("image/*")) },
                onPdf = { pdfSaver.launch("${currentTitle.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.pdf") }, onGallery = { requestGallery() }, onShare = { sharePdf() },
                onMove = { page, direction -> bookId?.let { id -> vm.perform { store.move(id, page.id, direction) } } },
                onRename = { title -> bookId?.let { id -> vm.perform { store.dao.rename(id, title.trim().take(100)) } } }, snack = snack, onOcr={route="documentOcr"})
            else -> LibraryScreen(store, books, trash, onScan = {startScan()}, onImport = { importBook = null; importer.launch(arrayOf("image/*")) }, onOpen = { bookId = it; route = "document" },
                onShare = { id -> sharePdf(id) }, onPdf = { id ->
                    bookId = id
                    val title = books.firstOrNull { it.book.id == id }?.book?.title ?: context.getString(R.string.document)
                    pdfSaver.launch("${title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.pdf")
                }, onTrash = { ids, restore -> vm.perform(if (restore) R.string.restored else R.string.moved_to_trash) { store.trash(ids, restore) } }, snack = snack, onBackup={backupSaver.launch("DocScan-backup.zip")},onRestoreBackup={backupLoader.launch(arrayOf("application/zip","application/octet-stream"))},
                onToolScan={tool->startScan(tool.name)},onToolOcr={id->bookId=id;route="documentOcr"},onDeleteForever={ids->vm.perform(R.string.deleted_forever){store.deleteForever(ids)}},
                drafts=drafts,onResumeDraft={id->vm.perform {bookId=id;capturePageIds=store.dao.snapshot(id).map {it.id};captureSession=true;pageId=capturePageIds.firstOrNull();route=if(pageId==null)"camera" else "review"}})
        }
        if (vm.busy) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .12f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp) {
                    Column(Modifier.padding(24.dp).widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(Modifier.size(26.dp))
                            Text(ocrProgress?.let { stringResource(R.string.ocr_progress, it.page, it.total, it.percent) } ?: stringResource(R.string.busy))
                        }
                        if (ocrProgress != null) {
                            Text(stringResource(R.string.ocr_cancel_hint), style = MaterialTheme.typography.bodySmall)
                            TextButton(vm::cancelOcr, modifier = Modifier.testTag("ocr-cancel")) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
        }
    }
}
}
