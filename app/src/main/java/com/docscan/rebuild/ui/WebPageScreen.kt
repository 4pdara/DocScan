package com.docscan.rebuild.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.docscan.rebuild.R
import java.io.ByteArrayOutputStream

/**
 * Web page to PDF (1.3.3): a page is opened here, and once it has loaded it is cut into A4-shaped pages of a new
 * document, which exports like any scan. Only what the page shows is kept; nothing is sent anywhere.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebPageScreen(busy: Boolean, onBack: () -> Unit, onSave: (String, List<ByteArray>) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler(onBack = onBack)
    fun open() {
        val text = address.trim(); if (text.isEmpty()) return
        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(text)) text else "https://$text"
        loaded = false; loading = true; web?.loadUrl(url)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.web_to_pdf)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        })
    }, modifier = Modifier.testTag("web-screen")) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(address, { address = it }, Modifier.weight(1f).testTag("web-url"), singleLine = true,
                    placeholder = { Text(stringResource(R.string.web_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { open() }))
                TextButton(::open, enabled = address.isNotBlank(), modifier = Modifier.testTag("web-load")) { Text(stringResource(R.string.web_open)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            AndroidView({ context ->
                // Lets the page be drawn beyond what is on screen, for the pages below the fold.
                WebView.enableSlowWholeDocumentDraw()
                WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    setBackgroundColor(Color.WHITE)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { loading = false; loaded = true }
                    }
                    web = this
                }
            }, Modifier.weight(1f).fillMaxWidth().testTag("web-view"), onRelease = { it.destroy(); web = null })
            Button({
                val view = web ?: return@Button
                onSave(view.title?.takeIf { it.isNotBlank() } ?: address.trim(), slices(view))
            }, enabled = loaded && !busy, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("web-save")) {
                Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.web_save))
            }
        }
    }
}

/** The whole page, top to bottom, in A4-shaped slices (at most 40) as JPEG pictures. */
@Suppress("DEPRECATION")
private fun slices(view: WebView): List<ByteArray> {
    val width = view.width.coerceAtLeast(1)
    val total = (view.contentHeight * view.scale).toInt().coerceAtLeast(view.height).coerceAtLeast(1)
    val slice = (width * 297f / 210f).toInt()
    view.scrollTo(0, 0)
    val out = ArrayList<ByteArray>()
    var top = 0
    while (top < total && out.size < 40) {
        val height = minOf(slice, total - top).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            // With the page scrolled to its top, each slice is the page drawn shifted up by where the slice starts.
            canvas.translate(0f, -top.toFloat())
            view.draw(canvas)
            out += ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally { bitmap.recycle() }
        top += height
    }
    return out
}
