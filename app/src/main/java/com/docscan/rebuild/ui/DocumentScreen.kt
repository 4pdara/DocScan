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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onPrint: () -> Unit = {}, onWatermark: (String?, MarkPlace, MarkStyle) -> Unit = { _, _, _ -> }, onStamp: (Boolean) -> Unit = {}, onExtract: (Set<String>) -> Unit = {},
    onFooter: (String?, StampPlace, MarkColor) -> Unit = { _, _, _ -> }, onProtectedPdf: (String) -> Unit = {},
    onSign: (Sheet, SignatureMark?) -> Unit = { _, _ -> }, onExcel: () -> Unit = {}, onPowerPoint: () -> Unit = {},
    onLongImage: () -> Unit = {}, onWord: () -> Unit = {}, startTool: String? = null, onToolStarted: () -> Unit = {}) {
    var reorder by rememberSaveable { mutableStateOf(false) }
    var pdfChoice by rememberSaveable { mutableStateOf(false) }
    var protecting by remember { mutableStateOf(false) }
    // Signature: choosing (or drawing) one, then placing it on a page.
    var choosingSignature by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf<SignatureMark?>(null) }
    // "watermark" or "footer" while its dialog is open; the draft holds the marks shown in the live preview.
    var marking by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(EditRecipe()) }
    fun openMarks(kind: String) { draft = pages.firstOrNull()?.edit() ?: EditRecipe(); marking = kind }
    var extracting by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    val stamped = pages.isNotEmpty() && pages.all { it.edit().stamp != null }
    // A tool picked on the Tools tab opens here on the chosen document.
    LaunchedEffect(startTool) {
        when (startTool) {
            "watermark", "footer" -> openMarks(if (startTool == "footer") "footer" else "watermark")
            "extract" -> { extracting = true; chosen = emptyList() }
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
        } }, confirmButton = {}, dismissButton = { TextButton({ pdfChoice = false }) { Text(stringResource(R.string.cancel)) } })
    if (choosingSignature) SignatureChooser(onChosen = { choosingSignature = false; placing = it }, onDismiss = { choosingSignature = false })
    placing?.let { signature ->
        SignaturePlacer(store, pages, signature, onApply = { page, mark -> placing = null; onSign(page, mark) }, onRemoveAll = { page -> placing = null; onSign(page, null) }, onDismiss = { placing = null })
    }
    if (protecting) PasswordDialog(onDismiss = { protecting = false }) { password -> protecting = false; onProtectedPdf(password) }
    // The first page without any marks, drawn once; the preview draws the draft's marks on a copy of it.
    val first = pages.firstOrNull()
    val unmarked by produceState<Bitmap?>(null, first?.id, first?.edit(), marking != null) {
        value = if (marking == null || first == null) null else withContext(Dispatchers.IO) {
            runCatching { ImagePipeline.render(store.source(first), first.edit().copy(watermark = null, stamp = null, footer = null), 700) }.getOrNull()
        }
    }
    marking?.let { kind ->
        val footer = kind == "footer"
        val empty = (if (footer) draft.footer else draft.watermark).isNullOrBlank()
        AlertDialog(onDismissRequest = { marking = null }, title = { Text(stringResource(if (footer) R.string.footer else R.string.watermark)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkPreview(unmarked, draft)
                Text(stringResource(if (footer) R.string.footer_hint else R.string.watermark_hint), style = MaterialTheme.typography.bodySmall)
                if (footer) {
                    OutlinedTextField(draft.footer.orEmpty(), { draft = draft.copy(footer = it.take(60)) }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.footer_example)) }, modifier = Modifier.fillMaxWidth().testTag("footer-text"))
                    MarkCaption(R.string.mark_place)
                    PlaceChips(notePlaceLabels, draft.footerPlace, { draft = draft.copy(footerPlace = it) }, tag = "footer-place")
                    MarkCaption(R.string.note_color)
                    ColourChoice(draft.noteColor, { draft = draft.copy(noteColor = it) })
                } else {
                    OutlinedTextField(draft.watermark.orEmpty(), { draft = draft.copy(watermark = it.take(40)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("watermark-text"))
                    WatermarkStyleControls(draft.watermarkPlace, draft.watermarkStyle, { draft = draft.copy(watermarkPlace = it) }, { draft = draft.copy(watermarkStyle = it) })
                }
            } },
            confirmButton = { TextButton({
                if (footer) onFooter(draft.footer?.trim()?.ifBlank { null }, draft.footerPlace, draft.noteColor)
                else onWatermark(draft.watermark?.trim()?.ifBlank { null }, draft.watermarkPlace, draft.watermarkStyle)
                marking = null
            }, modifier = Modifier.testTag(if (footer) "apply-footer" else "apply-watermark")) {
                Text(stringResource(if (!empty) R.string.apply_all_pages else if (footer) R.string.remove_footer else R.string.remove_watermark))
            } },
            dismissButton = { TextButton({ marking = null }) { Text(stringResource(R.string.cancel)) } })
    }
    var actions by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var newTitle by remember(title) { mutableStateOf(title) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(newTitle, { newTitle = it }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.watermark, Icons.Outlined.WaterDrop, Modifier.weight(1f).testTag("tool-watermark"), pages.isNotEmpty()) { actions = false; openMarks("watermark") }
                DocumentAction(R.string.footer, Icons.Outlined.Title, Modifier.weight(1f).testTag("tool-footer"), pages.isNotEmpty()) { actions = false; openMarks("footer") }
                DocumentAction(if (stamped) R.string.remove_timestamp else R.string.timestamp, Icons.Outlined.Schedule, Modifier.weight(1f).testTag("tool-timestamp"), pages.isNotEmpty()) { actions = false; onStamp(!stamped) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentAction(R.string.extract_pages, Icons.Outlined.ContentCut, Modifier.weight(1f).testTag("tool-extract"), pages.isNotEmpty()) { actions = false; extracting = true; chosen = emptyList() }
                DocumentAction(R.string.signature, Icons.Outlined.Draw, Modifier.weight(1f).testTag("tool-signature"), pages.isNotEmpty()) { actions = false; choosingSignature = true }
                DocumentAction(R.string.protected_pdf, Icons.Outlined.Lock, Modifier.weight(1f).testTag("tool-protect"), pages.isNotEmpty()) { actions = false; protecting = true }
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
                TextButton({ extracting = false }) { Text(stringResource(R.string.cancel)) }
                Text(stringResource(R.string.selected_count, chosen.size), Modifier.weight(1f))
                Button({ extracting = false; onExtract(chosen.toSet()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("extract-selected")) { Text(stringResource(R.string.extract_to_pdf)) }
            }
        } else Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).testTag("document-actions"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onCamera, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.add_pages), maxLines = 1) }
                IconButton(onShare, enabled = pages.isNotEmpty()) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                Button({ pdfChoice = true }, enabled = pages.isNotEmpty(), shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f).testTag("save-pdf"), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.export_pdf), maxLines = 1) }
            }
        }
    }) { padding ->
        if (pages.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_pages)) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("document-pages"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                Column {

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (extracting) Checkbox(page.id in chosen, { chosen = if (page.id in chosen) chosen - page.id else chosen + page.id }, modifier = Modifier.testTag("choose-${page.id}"))
                        TextButton(onClick = { onEdit(page) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.edit)) }
                        Spacer(Modifier.weight(1f))
                        if (reorder) {
                            IconButton(onClick = { onMove(page, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_up)) }
                            IconButton(onClick = { onMove(page, 1) }, enabled = index < pages.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_down)) }
                        }
                        Text(stringResource(R.string.page_counter, index + 1, pages.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(Modifier.fillMaxWidth().clickable { if (extracting) chosen = if (page.id in chosen) chosen - page.id else chosen + page.id else onEdit(page) }, shadowElevation = 3.dp) {
                        PagePicture(store, page, Modifier.fillMaxWidth().testTag("page-image-${page.id}"), naturalHeight = true, description = stringResource(R.string.page_number, index + 1))
                    }
                }
            }
        }
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
