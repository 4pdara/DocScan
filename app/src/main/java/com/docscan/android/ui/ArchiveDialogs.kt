package com.docscan.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.docscan.android.R

@Composable
fun ArchiveConfirmDialog(backup: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (backup) R.string.archive_backup else R.string.archive_restore)) },
        text = { Text(stringResource(if (backup) R.string.archive_backup_hint else R.string.archive_restore_hint)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(if (backup) R.string.archive_choose_destination else R.string.archive_choose_file)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ArchiveProgressDialog(backup: Boolean, finishing: Boolean, cancelling: Boolean, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(if (backup) R.string.archive_backup else R.string.archive_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(when {
                    finishing -> R.string.archive_finishing
                    cancelling -> R.string.archive_cancelling
                    backup -> R.string.archive_creating
                    else -> R.string.archive_checking
                }))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { if (!finishing) TextButton(enabled = !cancelling, onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}
