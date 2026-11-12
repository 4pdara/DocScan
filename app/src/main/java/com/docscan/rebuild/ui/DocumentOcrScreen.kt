package com.docscan.rebuild.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.Sheet
import com.docscan.rebuild.imaging.OcrBatchResult
import com.docscan.rebuild.model.OcrLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentOcrScreen(pages: List<Sheet>, language: String, onLanguage: (String) -> Unit,
    layout: OcrLayout, onLayout: (OcrLayout) -> Unit, report: OcrBatchResult?, snack: SnackbarHostState,
    onBack: () -> Unit, onRecognize: (Boolean) -> Unit, onExport: () -> Unit, onPage: (Sheet) -> Unit) {
    var replaceExisting by rememberSaveable { mutableStateOf(false) }
    val ready = pages.count { it.currentText() != null }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ocr_document)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        })
    }, bottomBar = {
        Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRecognize(replaceExisting) }, enabled = pages.isNotEmpty(), modifier = Modifier.weight(1f).testTag("ocr-document-start")) {
                    Text(stringResource(R.string.ocr_document_start))
                }
                OutlinedButton(onExport, enabled = ready == pages.size && ready > 0, modifier = Modifier.weight(1f).testTag("ocr-document-export")) {
                    Text(stringResource(R.string.save_text))
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("document-ocr"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.ocr_batch_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OcrSettings(language, onLanguage, layout, onLayout)
                Row(Modifier.fillMaxWidth().clickable { replaceExisting = !replaceExisting }) {
                    Checkbox(replaceExisting, { replaceExisting = it }, modifier = Modifier.testTag("ocr-replace-existing"))
                    Text(stringResource(R.string.ocr_replace_existing), Modifier.padding(top = 12.dp))
                }
                Text(stringResource(R.string.ocr_pages_ready, ready, pages.size), modifier = Modifier.testTag("ocr-pages-ready"))
                report?.let {
                    Text(stringResource(R.string.ocr_batch_result, it.recognized, it.retained, it.failedPages.size), modifier = Modifier.testTag("ocr-batch-result"))
                    if (it.failedPages.isNotEmpty()) Text(stringResource(R.string.ocr_failed_pages, it.failedPages.joinToString(", ")))
                }
            }
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                OutlinedCard(onClick = { onPage(page) }, modifier = Modifier.fillMaxWidth().testTag("ocr-page-${page.id}")) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.page_number, index + 1), style = MaterialTheme.typography.titleSmall)
                        Text(page.currentText()?.take(180)?.ifBlank { stringResource(R.string.ocr_no_text) }
                            ?: stringResource(R.string.ocr_page_missing), maxLines = 4)
                    }
                }
            }
        }
    }
}
