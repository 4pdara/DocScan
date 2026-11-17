package com.docscan.rebuild.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.ImagePipeline
import com.docscan.rebuild.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(store: ScanStore, title: String, pages: List<Sheet>, onBack: () -> Unit, onEdit: (Sheet) -> Unit, onCamera: () -> Unit, onImport: () -> Unit, onPdf: (DocumentOutput.PdfSize) -> Unit, onGallery: () -> Unit, onShare: () -> Unit, onMove: (Sheet, Int) -> Unit, onRename: (String) -> Unit, snack: SnackbarHostState,
    onPrint: () -> Unit = {}, onWatermark: () -> Unit = {}, onStamp: (Boolean) -> Unit = {}, onExtract: (Set<String>) -> Unit = {},
    onHeader: (HeaderChoice) -> Unit = {}, onProtectedPdf: (String) -> Unit = {},
    onSign: (Sheet, SignatureMark?) -> Unit = { _, _ -> }, onExcel: () -> Unit = {}, onPowerPoint: () -> Unit = {},
    onLongImage: () -> Unit = {}, onWord: () -> Unit = {}, startTool: String? = null, onToolStarted: () -> Unit = {},
    onOpen: (Sheet) -> Unit = onEdit, onDeletePages: (Set<String>) -> Unit = {}, onPageTool: (String) -> Unit = {}, onEraseColor: () -> Unit = {}, onEmail: () -> Unit = {},
    tags: List<String> = emptyList(), allTags: List<String> = emptyList(), onTags: (List<String>) -> Unit = {}, locked: Boolean = false, onLock: (Boolean) -> Unit = {},
    onCollage: (List<String>, Int, Int) -> Unit = { _, _, _ -> }, suggestName: (suspend () -> String?)? = null) {
    var reorder by rememberSaveable { mutableStateOf(false) }
    var pdfChoice by rememberSaveable { mutableStateOf(false) }
    var protecting by remember { mutableStateOf(false) }
    // Signature: choosing (or drawing) one, then placing it on a page.
    var choosingSignature by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf<SignatureMark?>(null) }
    // Header, footer line, page numbers and time for every page.
    var header by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingTags by remember { mutableStateOf(false) }
    var pdfSettings by remember { mutableStateOf(false) }
    var collage by remember { mutableStateOf(false) }
    // From the Tools tab: every page chosen for a collage once the pages have loaded.
    var collageAll by remember { mutableStateOf(false) }
    // Page selection: extract the chosen pages to a PDF, delete them, or put them in a collage.
    var extracting by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(collageAll, pages.size) { if (collageAll && pages.isNotEmpty()) { chosen = pages.map { it.id }; extracting = true; collage = true; collageAll = false } }
    val stamped = pages.isNotEmpty() && pages.all { it.edit().stamp != null }
    // A tool picked on the Tools tab opens here on the chosen document.
    LaunchedEffect(startTool) {
        when (startTool) {
            "watermark" -> onWatermark()
            "footer" -> header = true
            "reorder" -> reorder = true
            "extract", "select" -> { extracting = true; chosen = emptyList() }
            "collage" -> collageAll = true
            "tags" -> editingTags = true
            "pdfsettings" -> pdfSettings = true
            "compress" -> pdfChoice = true
            "protect" -> protecting = true
            "signature" -> choosingSignature = true
        }
        if (startTool != null) onToolStarted()
    }
    if (pdfChoice) AlertDialog(onDismissRequest = { pdfChoice = false }, title = { Text(stringResource(R.string.pdf_size)) },
        text = { Column {
            listOf(DocumentOutput.PdfSize.ORIGINAL to R.string.pdf_size_original, DocumentOutput.PdfSize.BALANCED to R.string.pdf_size_balanced, DocumentOutput.PdfSize.SMALL to R.string.pdf_size_small).forEach { (size, label) ->
                TextButton({ pdfChoice = false; onPdf(size) }, Modifier.fillMaxWidth().testTag("pdf-size-${size.name.lowercase()}")) { Text(stringResource(label), Modifier.fillMaxWidth()) }
            }
            HorizontalDivider()
            TextButton({ pdfChoice = false; protecting = true }, Modifier.fillMaxWidth().testTag("pdf-protected")) {
                Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.protected_pdf), Modifier.fillMaxWidth())
            }
            TextButton({ pdfChoice = false; pdfSettings = true }, Modifier.fillMaxWidth().testTag("pdf-page-settings")) {
                Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pdf_settings), Modifier.fillMaxWidth())
            }
        } }, confirmButton = {}, dismissButton = { TextButton({ pdfChoice = false }) { Text(stringResource(R.string.cancel)) } })
    if (choosingSignature) SignatureChooser(onChosen = { choosingSignature = false; placing = it }, onDismiss = { choosingSignature = false })
    placing?.let { signature ->
        SignaturePlacer(store, pages, signature, onApply = { page, mark -> placing = null; onSign(page, mark) }, onRemoveAll = { page -> placing = null; onSign(page, null) }, onDismiss = { placing = null })
    }
    if (protecting) PasswordDialog(onDismiss = { protecting = false }) { password -> protecting = false; onProtectedPdf(password) }
    // The first page without its edge notes, drawn once for the header preview.
    val first = pages.firstOrNull()
    val unmarked by produceState<Bitmap?>(null, first?.id, first?.edit(), header) {
        value = if (!header || first == null) null else withContext(Dispatchers.IO) {
            runCatching { ImagePipeline.render(store.source(first), first.edit().copy(stamp = null, footer = null, numberText = null), 700) }.getOrNull()
        }
    }
    if (editingTags) TagsDialog(tags, allTags, onSave = { editingTags = false; onTags(it) }, onDismiss = { editingTags = false })
    if (pdfSettings) PdfSettingsDialog(onDismiss = { pdfSettings = false })
    if (collage) CollageDialog(chosen.size, onDismiss = { collage = false }) { columns, rows ->
        collage = false; extracting = false; onCollage(pages.map { it.id }.filter { it in chosen }, columns, rows)
    }
    if (header) HeaderDialog(unmarked, first?.edit() ?: EditRecipe(), pages.size, onApply = { header = false; onHeader(it) }, onDismiss = { header = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(pluralStringResource(R.plurals.delete_pages_title, chosen.size, chosen.size)) }, text = { Text(stringResource(R.string.delete_page_message)) },
        confirmButton = { TextButton({ confirmDelete = false; extracting = false; onDeletePages(chosen.toSet()) }, modifier = Modifier.testTag("confirm-delete-pages")) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    var actions by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var newTitle by remember(title) { mutableStateOf(title) }
    var suggesting by remember { mutableStateOf(false) }
    var noSuggestion by remember { mutableStateOf(false) }
    val nameScope = rememberCoroutineScope()
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { Column {
            OutlinedTextField(newTitle, { newTitle = it }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true, modifier = Modifier.testTag("document-name"))
            // A name from the first page's text: the line printed largest near its top.
            if (suggestName != null && pages.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ suggesting = true; noSuggestion = false
                    nameScope.launch { val name = runCatching { suggestName() }.getOrNull(); suggesting = false; if (name != null) newTitle = name else noSuggestion = true }
                }, enabled = !suggesting, modifier = Modifier.testTag("suggest-name")) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.name_from_text)) }
                if (suggesting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (noSuggestion) Text(stringResource(R.string.name_from_text_none), style = MaterialTheme.typography.bodySmall)
        } },
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
                DocumentAction(R.string.export_pdf, Icons.Outlined.PictureAsPdf, Modifier.weight(1f), pages.isNotEmpty()) { actions = false; pdfChoice = true }
                DocumentAction(R.string.share, Icons.Outlined.Share, Modifier.weight(1f), pages.isNotEmpty()) { actions = false; onShare() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.import_images, Icons.Outlined.AddPhotoAlternate, Modifier.weight(1f)) { actions = false; onImport() }
                DocumentAction(R.string.rename, Icons.Outlined.Edit, Modifier.weight(1f)) { actions = false; rename = true }
                DocumentAction(R.string.reorder, Icons.Outlined.SwapVert, Modifier.weight(1f), pages.size > 1) { actions = false; reorder = !reorder }
            }
            Text(stringResource(R.string.tools_tab), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.print, Icons.Outlined.Print, Modifier.weight(1f).testTag("tool-print"), pages.isNotEmpty()) { actions = false; onPrint() }
                DocumentAction(R.string.to_word, Icons.Outlined.Description, Modifier.weight(1f).testTag("tool-word"), pages.isNotEmpty()) { actions = false; onWord() }
                DocumentAction(R.string.long_image, Icons.Outlined.ViewDay, Modifier.weight(1f).testTag("tool-long-image"), pages.isNotEmpty()) { actions = false; onLongImage() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.to_excel, Icons.Outlined.TableChart, Modifier.weight(1f).testTag("tool-excel"), pages.isNotEmpty()) { actions = false; onExcel() }
                DocumentAction(R.string.to_powerpoint, Icons.Outlined.Slideshow, Modifier.weight(1f).testTag("tool-powerpoint"), pages.isNotEmpty()) { actions = false; onPowerPoint() }
                Spacer(Modifier.weight(1f))
            }
            Text(stringResource(R.string.tools_edit), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.smart_erase, Icons.Outlined.AutoFixHigh, Modifier.weight(1f).testTag("tool-erase"), pages.isNotEmpty()) { actions = false; onPageTool("erase") }
                DocumentAction(R.string.edit_text, Icons.Outlined.TextFields, Modifier.weight(1f).testTag("tool-edit-text"), pages.isNotEmpty()) { actions = false; onPageTool("text") }
                DocumentAction(R.string.colour_marks_short, Icons.Outlined.FormatColorReset, Modifier.weight(1f).testTag("tool-erase-colour"), pages.isNotEmpty()) { actions = false; onEraseColor() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.signature, Icons.Outlined.Draw, Modifier.weight(1f).testTag("tool-signature"), pages.isNotEmpty()) { actions = false; choosingSignature = true }
                DocumentAction(R.string.add_text, Icons.Outlined.Title, Modifier.weight(1f).testTag("tool-add-text"), pages.isNotEmpty()) { actions = false; onPageTool("addtext") }
                DocumentAction(R.string.brush, Icons.Outlined.Brush, Modifier.weight(1f).testTag("tool-brush"), pages.isNotEmpty()) { actions = false; onPageTool("brush") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.watermark, Icons.Outlined.WaterDrop, Modifier.weight(1f).testTag("tool-watermark"), pages.isNotEmpty()) { actions = false; onWatermark() }
                DocumentAction(R.string.header_short, Icons.Outlined.FormatListNumbered, Modifier.weight(1f).testTag("tool-footer"), pages.isNotEmpty()) { actions = false; header = true }
                DocumentAction(if (stamped) R.string.remove_timestamp else R.string.timestamp, Icons.Outlined.Schedule, Modifier.weight(1f).testTag("tool-timestamp"), pages.isNotEmpty()) { actions = false; onStamp(!stamped) }
            }
            Text(stringResource(R.string.tab_pages), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.select_pages, Icons.Outlined.Checklist, Modifier.weight(1f).testTag("tool-select"), pages.isNotEmpty()) { actions = false; extracting = true; chosen = emptyList() }
                DocumentAction(R.string.extract_pages, Icons.Outlined.ContentCut, Modifier.weight(1f).testTag("tool-extract"), pages.isNotEmpty()) { actions = false; extracting = true; chosen = emptyList() }
                DocumentAction(R.string.reorder, Icons.Outlined.SwapVert, Modifier.weight(1f).testTag("tool-reorder"), pages.size > 1) { actions = false; reorder = !reorder }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.collage, Icons.Outlined.Dashboard, Modifier.weight(1f).testTag("tool-collage"), pages.isNotEmpty()) { actions = false; extracting = true; chosen = pages.map { it.id } }
                DocumentAction(R.string.tags, Icons.Outlined.Sell, Modifier.weight(1f).testTag("tool-tags")) { actions = false; editingTags = true }
                DocumentAction(if (locked) R.string.unlock_document else R.string.lock_document, if (locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, Modifier.weight(1f).testTag("tool-lock")) { actions = false; onLock(!locked) }
            }
            Text(stringResource(R.string.tools_utilities), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.protected_pdf, Icons.Outlined.Lock, Modifier.weight(1f).testTag("tool-protect"), pages.isNotEmpty()) { actions = false; protecting = true }
                DocumentAction(R.string.pdf_size, Icons.Outlined.Compress, Modifier.weight(1f).testTag("tool-compress"), pages.isNotEmpty()) { actions = false; pdfChoice = true }
                DocumentAction(R.string.email, Icons.Outlined.Email, Modifier.weight(1f).testTag("tool-email"), pages.isNotEmpty()) { actions = false; onEmail() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.pdf_settings, Icons.Outlined.Settings, Modifier.weight(1f).testTag("tool-pdf-settings")) { actions = false; pdfSettings = true }
                Spacer(Modifier.weight(1f)); Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = { actions = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
    BackHandler(extracting) { extracting = false }
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
        if (extracting) Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton({ extracting = false }) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
                Text(stringResource(R.string.selected_count, chosen.size), Modifier.weight(1f), maxLines = 1)
                IconButton({ chosen = if (chosen.size == pages.size) emptyList() else pages.map { it.id } }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) }
                IconButton({ confirmDelete = true }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("delete-selected")) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                IconButton({ collage = true }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("collage-selected")) { Icon(Icons.Outlined.Dashboard, stringResource(R.string.collage)) }
                IconButton({ extracting = false; onExtract(chosen.toSet()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("extract-selected")) { Icon(Icons.Outlined.PictureAsPdf, stringResource(R.string.extract_to_pdf)) }
            }
        } else Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp).testTag("document-actions"), verticalAlignment = Alignment.CenterVertically) {
                BarAction(R.string.add, Icons.Outlined.AddAPhoto, "doc-add", Modifier.weight(1f), true, onCamera)
                BarAction(R.string.edit, Icons.Outlined.Edit, "doc-edit", Modifier.weight(1f), pages.isNotEmpty()) { pages.firstOrNull()?.let(onEdit) }
                BarAction(R.string.share, Icons.Outlined.Share, "doc-share", Modifier.weight(1f), pages.isNotEmpty(), onShare)
                BarAction(R.string.to_word, Icons.Outlined.Description, "doc-word", Modifier.weight(1f), pages.isNotEmpty(), onWord)
                Button({ pdfChoice = true }, enabled = pages.isNotEmpty(), shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1.2f).testTag("save-pdf"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.export_pdf), maxLines = 1) }
            }
        }
    }) { padding ->
        if (pages.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_pages)) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("document-pages"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                Column {

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (extracting) Checkbox(page.id in chosen, { chosen = if (page.id in chosen) chosen - page.id else chosen + page.id }, modifier = Modifier.testTag("choose-${page.id}"))
                        TextButton(onClick = { onEdit(page) }, modifier = Modifier.testTag("edit-page-${page.id}")) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.edit)) }
                        Spacer(Modifier.weight(1f))
                        if (reorder) {
                            IconButton(onClick = { onMove(page, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up)) }
                            IconButton(onClick = { onMove(page, 1) }, enabled = index < pages.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down)) }
                        }
                        Text(stringResource(R.string.page_counter, index + 1, pages.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(Modifier.fillMaxWidth().clickable { if (extracting) chosen = if (page.id in chosen) chosen - page.id else chosen + page.id else onOpen(page) }, shadowElevation = 3.dp) {
                        PagePicture(store, page, Modifier.fillMaxWidth().testTag("page-image-${page.id}"), naturalHeight = true, description = stringResource(R.string.page_number, index + 1))
                    }
                }
            }
        }
    }
}

@Composable
private fun BarAction(label: Int, icon: ImageVector, tag: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
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

/** Asks for the PDF password twice. It is used once, for the file being saved, and never stored. */
@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = first.length >= 4 && first == second
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.protected_pdf)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.protected_pdf_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(first, { first = it.take(64) }, singleLine = true, label = { Text(stringResource(R.string.password)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("pdf-password"))
            OutlinedTextField(second, { second = it.take(64) }, singleLine = true, label = { Text(stringResource(R.string.password_again)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = second.isNotEmpty() && second != first, modifier = Modifier.fillMaxWidth().testTag("pdf-password-again"))
            if (second.isNotEmpty() && second != first) Text(stringResource(R.string.password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ onConfirm(first) }, enabled = valid, modifier = Modifier.testTag("pdf-password-save")) { Text(stringResource(R.string.export_pdf)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
