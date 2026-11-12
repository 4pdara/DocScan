package com.docscan.rebuild.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.sp
import com.docscan.rebuild.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(text:String,onText:(String)->Unit,language:String,snack:SnackbarHostState,onBack:()->Unit,onSave:()->Unit,onRecognize:()->Unit,onLanguageRecognize:(String)->Unit,onKeep:()->Unit) {
    val context=LocalContext.current
    Scaffold(snackbarHost={SnackbarHost(snack)},topBar={TopAppBar(title={Text(stringResource(R.string.ocr))},navigationIcon={IconButton(onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.back))}},actions={
        IconButton(onClick={val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clipboard.setPrimaryClip(ClipData.newPlainText("DocScan",text))},enabled=text.isNotEmpty()){Icon(Icons.Outlined.ContentCopy,stringResource(R.string.copy_text))}
        IconButton(onSave,enabled=text.isNotEmpty()){Icon(Icons.Outlined.Download,stringResource(R.string.save_text))}
    })},bottomBar={Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Button(onRecognize,modifier=Modifier.weight(1f).testTag("recognize-text")){Text(stringResource(if(text.isEmpty())R.string.recognize_text else R.string.recognize_again))}
        OutlinedButton(onKeep,modifier=Modifier.weight(1f)){Text(stringResource(R.string.save))}
    }}}){padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.ocr_hint),style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("heb" to R.string.ocr_hebrew,"heb+eng" to R.string.ocr_both,"eng" to R.string.ocr_english).forEach { (id,label) -> FilterChip(selected=language==id,onClick={if(language!=id)onLanguageRecognize(id)},label={Text(stringResource(label))}) }
            }
            // One printed line per row with open line spacing, like a reading view; still editable.
            OutlinedTextField(text,onText,modifier=Modifier.fillMaxWidth().weight(1f).testTag("recognized-text"),placeholder={Text(stringResource(R.string.ocr_empty))},
                textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=18.sp,lineHeight=32.sp))
        }
    }
}
