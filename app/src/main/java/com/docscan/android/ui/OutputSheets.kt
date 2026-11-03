package com.docscan.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.docscan.android.R
import com.docscan.android.data.DocumentSummary
import com.docscan.android.export.ShareFormat
import com.docscan.android.processing.ScanFilter
import com.docscan.android.processing.ProDocumentFilters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(onImages: () -> Unit, onPdf: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.import_files), style = MaterialTheme.typography.titleLarge)
            ActionButton(R.drawable.ic_image, stringResource(R.string.import_images), true, Modifier.fillMaxWidth(), onImages)
            ActionButton(R.drawable.ic_pdf, stringResource(R.string.import_pdf), true, Modifier.fillMaxWidth(), onPdf)
            Text(stringResource(R.string.pdf_import_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(onShare: (ShareFormat) -> Unit, onPrint: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.share_options), style = MaterialTheme.typography.titleLarge)
            for ((format, label) in listOf(ShareFormat.PDF to R.string.share_pdf, ShareFormat.IMAGES to R.string.share_images, ShareFormat.BOTH to R.string.share_both)) {
                ActionButton(R.drawable.ic_share, stringResource(label), true, Modifier.fillMaxWidth()) { onShare(format) }
            }
            ActionButton(R.drawable.ic_pdf, stringResource(R.string.print_document), true, Modifier.fillMaxWidth(), onPrint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchFilterSheet(onApply: (ScanFilter, Int) -> Unit, onDismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(ScanFilter.CLEAN_COLOR.name) }
    var strength by rememberSaveable { mutableIntStateOf(70) }
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * .8f).toDp() }
    val description = stringResource(R.string.filters_label)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.batch_filters), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f).semantics { contentDescription = description }) {
                items(ScanFilter.entries) { filter ->
                    Surface(onClick = { chosen = filter.name }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(chosen == filter.name, onClick = { chosen = filter.name })
                            Text(filterLabel(filter))
                        }
                    }
                }
            }
            val filter = ScanFilter.valueOf(chosen)
            if (filter in ProDocumentFilters.supported || filter == ScanFilter.CLEAN_COLOR || filter == ScanFilter.CLEAN_TEXT) {
                AdjustmentControl(stringResource(R.string.filter_strength), strength, 0..100, true) { strength = it }
            }
            Button(onClick = { onApply(ScanFilter.valueOf(chosen), strength) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.apply)) }
        }
    }
}

@Composable
fun MergeDialog(documents: List<DocumentSummary>, onConfirm: (List<String>, String) -> Unit, onDismiss: () -> Unit) {
    var order by remember(documents) { mutableStateOf(documents.map { it.document.id }) }
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.merge_documents)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it.take(120) }, label = { Text(stringResource(R.string.document_name)) }, singleLine = true)
            Text(stringResource(R.string.merge_hint), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(order, key = { it }) { id ->
                    val index = order.indexOf(id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(documents.first { it.document.id == id }.document.title, Modifier.weight(1f), maxLines = 2)
                        IconButton(enabled = index > 0, onClick = { order = order.toMutableList().apply { java.util.Collections.swap(this, index, index - 1) } }) { AppIcon(R.drawable.ic_up, stringResource(R.string.move_previous)) }
                        IconButton(enabled = index < order.lastIndex, onClick = { order = order.toMutableList().apply { java.util.Collections.swap(this, index, index + 1) } }) { AppIcon(R.drawable.ic_down, stringResource(R.string.move_next)) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(order, title) }) { Text(stringResource(R.string.create_merged)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
