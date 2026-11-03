package com.docscan.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.docscan.android.scanning.GoogleScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

data class GoogleScanAction(val preparing: Boolean, val launch: () -> Unit, val cancel: () -> Unit)

@Composable
fun rememberGoogleScanAction(onPages: (List<Uri>) -> Unit, onError: () -> Unit): GoogleScanAction {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var preparing by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val currentPages by rememberUpdatedState(onPages)
    val currentError by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        preparing = false
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val pages = GoogleScanner.pages(result.data)
                if (pages.isEmpty()) currentError() else currentPages(pages)
            } catch (_: Exception) { currentError() }
        }
        // Cancellation imports nothing and leaves every existing draft untouched.
    }
    LifecycleResumeEffect(Unit) {
        preparing = false
        onPauseOrDispose { preparing = false; generation++ }
    }
    return GoogleScanAction(preparing, launch = {
        if (!preparing) {
            if (activity == null || !GoogleScanner.isAvailable(context)) currentError()
            else {
                preparing = true
                val ticket = ++generation
                try {
                    GmsDocumentScanning.getClient(GoogleScanner.options()).getStartScanIntent(activity)
                        .addOnSuccessListener(activity) { sender ->
                            if (ticket == generation) {
                                preparing = false
                                try { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                                catch (_: Exception) { currentError() }
                            }
                        }
                        .addOnFailureListener(activity) { if (ticket == generation) { preparing = false; currentError() } }
                        .addOnCanceledListener(activity) { if (ticket == generation) preparing = false }
                } catch (_: Exception) { preparing = false; currentError() }
            }
        }
    }, cancel = { generation++; preparing = false })
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
