package com.docscan.rebuild.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.docscan.rebuild.BuildConfig
import com.docscan.rebuild.R
import com.docscan.rebuild.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** "Check for updates": asks GitHub only when pressed; downloads, verifies and hands the APK to Android's installer. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("idle") }
    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var mayInstall by remember { mutableStateOf(AppUpdater.mayInstall(context)) }
    // Coming back from the "install unknown apps" settings page.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) mayInstall = AppUpdater.mayInstall(context) }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().testTag("update-card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.update_hint, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        when (state) {
            "checking" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(stringResource(R.string.update_checking)) }
            "current" -> Text(stringResource(R.string.update_current), color = MaterialTheme.colorScheme.primary)
            "downloading" -> { Text(stringResource(R.string.update_downloading)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
            "installing" -> Text(stringResource(R.string.update_installing))
        }
        error?.let { Text(stringResource(R.string.update_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val found = release
        if (found != null && state == "available") {
            Text(stringResource(R.string.update_available, found.version, String.format(Locale.ROOT, "%.1f", found.size / 1_048_576.0)), color = MaterialTheme.colorScheme.primary)
            if (!mayInstall) {
                Text(stringResource(R.string.update_permission_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ context.startActivity(AppUpdater.installPermissionIntent(context)) }) { Text(stringResource(R.string.update_allow)) }
            }
            Button({
                error = null; state = "downloading"; progress = 0f
                scope.launch {
                    try {
                        val apk = AppUpdater.download(context, found) { p -> progress = p }
                        state = "installing"
                        // Copying 100 MB into the installer session stays off the main thread.
                        withContext(Dispatchers.IO) { AppUpdater.install(context, apk) { ok, message -> if (!ok) { state = "available"; error = message ?: "" } } }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { state = "available"; error = e.message ?: e.javaClass.simpleName }
                }
            }, enabled = mayInstall, modifier = Modifier.testTag("update-install")) { Text(stringResource(R.string.update_install, found.version)) }
        }
        if (state == "idle" || state == "current" || (state == "available" && found == null)) OutlinedButton({
            error = null; state = "checking"
            scope.launch {
                try {
                    release = AppUpdater.check(BuildConfig.VERSION_NAME)
                    state = if (release == null) "current" else "available"
                } catch (e: CancellationException) { throw e } catch (e: Exception) { state = "idle"; error = e.message ?: e.javaClass.simpleName }
            }
        }, modifier = Modifier.testTag("update-check")) { Text(stringResource(R.string.update_check)) }
    }
}
