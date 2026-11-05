package com.docscan.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.docscan.android.DocScanViewModel
import com.docscan.android.R
import com.docscan.android.export.ExportQuality

@Composable
fun SettingsScreen(vm: DocScanViewModel, busy: Boolean, hasDocuments: Boolean,
    onBackup: () -> Unit, onRestore: () -> Unit, onGoogleScan: () -> Unit,
    googlePreparing: Boolean, onCancelGoogle: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("scan-preferences", 0) }
    var auto by remember { mutableStateOf(prefs.getBoolean("automatic", true)) }
    var quality by remember { mutableStateOf(prefs.getBoolean("high-quality", false)) }
    var angled by remember { mutableStateOf(prefs.getBoolean("angled-distance", false)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.design_settings), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.settings_capture), style = MaterialTheme.typography.titleMedium)
        SettingSwitch(stringResource(R.string.capture_automatic), auto, !busy) { auto = it; prefs.edit { putBoolean("automatic", it) } }
        SettingSwitch(stringResource(R.string.high_quality_capture), quality, !busy) { quality = it; prefs.edit { putBoolean("high-quality", it) } }
        SettingSwitch(stringResource(R.string.angle_distance_detection), angled, !busy) { angled = it; prefs.edit { putBoolean("angled-distance", it) } }
        Text(stringResource(R.string.capture_quality_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.settings_output), style = MaterialTheme.typography.titleMedium)
        ExportQuality.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.exportQuality == option, onClick = { vm.chooseExportQuality(option) }, enabled = !busy)
                Text(exportQualityLabel(option))
            }
        }
        Text(stringResource(R.string.source_copy_note), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.settings_archive), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onBackup, Modifier.fillMaxWidth(), enabled = !busy && hasDocuments, shape = DesignCorner) { Text(stringResource(R.string.archive_backup)) }
        OutlinedButton(onRestore, Modifier.fillMaxWidth(), enabled = !busy, shape = DesignCorner) { Text(stringResource(R.string.archive_restore)) }
        HorizontalDivider()
        OutlinedButton(onGoogleScan, Modifier.fillMaxWidth(), enabled = !busy && !googlePreparing, shape = DesignCorner) { Text(stringResource(R.string.scan_google)) }
        if (googlePreparing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.google_scan_preparing))
            TextButton(onCancelGoogle) { Text(stringResource(R.string.cancel)) }
        }
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.preview_identity, DesignConfig.number), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled)
    }
}
