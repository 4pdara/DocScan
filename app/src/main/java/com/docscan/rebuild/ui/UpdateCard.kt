package com.docscan.rebuild.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.docscan.rebuild.BuildConfig
import com.docscan.rebuild.R
import com.docscan.rebuild.update.AppUpdater
import com.docscan.rebuild.update.UpdateController
import java.util.Locale

/** Whether Android lets this app install updates, refreshed when the user comes back from the settings page. */
@Composable
fun rememberMayInstall(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AppUpdater.mayInstall(context)) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = AppUpdater.mayInstall(context) }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    return allowed
}

@Composable
private fun InstallPermission() {
    val context = LocalContext.current
    Text(stringResource(R.string.update_permission_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedButton({ context.startActivity(AppUpdater.installPermissionIntent(context)) }, modifier = Modifier.testTag("update-allow")) { Text(stringResource(R.string.update_allow)) }
}

/**
 * "Check for updates": asks GitHub only when pressed. The download is done by Android and goes on when the user
 * leaves this page or the app; the verified file is then offered for installation.
 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val state by UpdateController.state.collectAsState()
    val mayInstall = rememberMayInstall()
    LaunchedEffect(Unit) { UpdateController.resume(context) }
    Column(Modifier.fillMaxWidth().testTag("update-card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.update_hint, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        when (val s = state) {
            UpdateController.State.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(stringResource(R.string.update_checking))
            }
            UpdateController.State.Current -> Text(stringResource(R.string.update_current), color = MaterialTheme.colorScheme.primary)
            is UpdateController.State.Available -> {
                Text(stringResource(R.string.update_available, s.release.version, String.format(Locale.ROOT, "%.1f", s.release.size / 1_048_576.0)), color = MaterialTheme.colorScheme.primary)
                Button({ UpdateController.download(context, s.release) }, modifier = Modifier.testTag("update-install")) { Text(stringResource(R.string.update_install, s.release.version)) }
            }
            is UpdateController.State.Downloading -> {
                Text(stringResource(R.string.update_downloading, s.version))
                val fraction = s.fraction
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_background_hint), style = MaterialTheme.typography.bodySmall)
                TextButton({ UpdateController.cancel(context) }, modifier = Modifier.testTag("update-cancel")) { Text(stringResource(R.string.update_cancel)) }
            }
            is UpdateController.State.Verifying -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(stringResource(R.string.update_verifying))
            }
            is UpdateController.State.Ready -> {
                Text(stringResource(R.string.update_ready, s.version), color = MaterialTheme.colorScheme.primary)
                s.error?.let { Text(stringResource(R.string.update_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!mayInstall) InstallPermission()
                Button({ UpdateController.install(context) }, enabled = mayInstall, modifier = Modifier.testTag("update-install-now")) { Text(stringResource(R.string.update_install_now)) }
            }
            is UpdateController.State.Installing -> Text(stringResource(R.string.update_installing))
            is UpdateController.State.Failed -> Text(stringResource(R.string.update_failed, s.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            UpdateController.State.Idle -> {}
        }
        if (state == UpdateController.State.Idle || state == UpdateController.State.Current || state is UpdateController.State.Failed)
            OutlinedButton({ UpdateController.check(context) }, modifier = Modifier.testTag("update-check")) { Text(stringResource(R.string.update_check)) }
    }
}

/**
 * Shown over any screen once a downloaded update has been verified, so the user does not have to go back to the
 * settings page. "Later" hides it until the app is opened again.
 */
@Composable
fun UpdateReadyDialog() {
    val context = LocalContext.current
    val state by UpdateController.state.collectAsState()
    val mayInstall = rememberMayInstall()
    var later by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        // A download started earlier may have finished while the app was closed.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) UpdateController.resume(context) }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val ready = state as? UpdateController.State.Ready ?: return
    if (later == ready.version || ready.error != null) return
    AlertDialog(onDismissRequest = { later = ready.version }, modifier = Modifier.testTag("update-ready-dialog"),
        title = { Text(stringResource(R.string.update_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_ready, ready.version))
            if (!mayInstall) Text(stringResource(R.string.update_permission_hint), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = {
            // Asked once: after "install now" the question does not come back while the app stays open (a declined
            // install can be retried from Settings, "Update the app").
            if (mayInstall) TextButton({ later = ready.version; UpdateController.install(context) }) { Text(stringResource(R.string.update_install_now)) }
            else TextButton({ context.startActivity(AppUpdater.installPermissionIntent(context)) }) { Text(stringResource(R.string.update_allow)) }
        },
        dismissButton = { TextButton({ later = ready.version }) { Text(stringResource(R.string.update_later)) } })
}
