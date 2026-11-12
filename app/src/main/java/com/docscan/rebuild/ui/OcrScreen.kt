package com.docscan.rebuild.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.model.OcrLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(text:String,onText:(String)->Unit,language:String,onLanguage:(String)->Unit,snack:SnackbarHostState,onBack:()->Unit,onSave:()->Unit,onRecognize:()->Unit,onKeep:()->Unit,layout:OcrLayout,onLayout:(OcrLayout)->Unit) {
    val context=LocalContext.current
    Scaffold(snackbarHost={SnackbarHost(snack)},topBar={TopAppBar(title={Text(stringResource(R.string.ocr))},navigationIcon={IconButton(onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.back))}},actions={
        IconButton(onClick={val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clipboard.setPrimaryClip(ClipData.newPlainText("DocScan",text))},enabled=text.isNotEmpty()){Icon(Icons.Outlined.ContentCopy,stringResource(R.string.copy_text))}
        IconButton(onSave,enabled=text.isNotEmpty()){Icon(Icons.Outlined.Download,stringResource(R.string.save_text))}
    })},bottomBar={Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Button(onRecognize,modifier=Modifier.weight(1f).testTag("recognize-text")){Text(stringResource(R.string.recognize_text))}
        OutlinedButton(onKeep,modifier=Modifier.weight(1f)){Text(stringResource(R.string.save))}
    }}}){padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.ocr_hint),style=MaterialTheme.typography.bodySmall)
            OcrSettings(language,onLanguage,layout,onLayout)
            OutlinedTextField(text,onText,modifier=Modifier.fillMaxWidth().weight(1f).testTag("recognized-text"),placeholder={Text(stringResource(R.string.ocr_empty))})
        }
    }
}

@Composable
fun OcrSettings(language: String, onLanguage: (String) -> Unit, layout: OcrLayout, onLayout: (OcrLayout) -> Unit) {
    Text(stringResource(R.string.ocr_language), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("heb+eng" to R.string.ocr_both, "heb" to R.string.ocr_hebrew, "eng" to R.string.ocr_english).forEach { (id, label) ->
            FilterChip(selected = language == id, onClick = { onLanguage(id) }, label = { Text(stringResource(label)) })
        }
    }
    Text(stringResource(R.string.ocr_layout), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(OcrLayout.AUTO to R.string.ocr_layout_auto, OcrLayout.COLUMN to R.string.ocr_layout_column,
            OcrLayout.BLOCK to R.string.ocr_layout_block, OcrLayout.SPARSE to R.string.ocr_layout_sparse).forEach { (id, label) ->
            FilterChip(selected = layout == id, onClick = { onLayout(id) }, modifier = Modifier.testTag("ocr-layout-${id.name}"), label = { Text(stringResource(label)) })
        }
    }
}
