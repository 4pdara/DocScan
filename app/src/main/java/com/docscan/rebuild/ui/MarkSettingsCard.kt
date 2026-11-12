package com.docscan.rebuild.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.MarkSettings
import com.docscan.rebuild.model.MarkPlace
import com.docscan.rebuild.model.StampPlace

val markPlaceLabels = listOf(MarkPlace.DIAGONAL to R.string.place_diagonal, MarkPlace.CENTER to R.string.place_center,
    MarkPlace.TOP to R.string.place_top, MarkPlace.BOTTOM to R.string.place_bottom, MarkPlace.SIDE to R.string.place_side)
val stampPlaceLabels = listOf(StampPlace.BOTTOM_RIGHT to R.string.corner_bottom_right, StampPlace.BOTTOM_LEFT to R.string.corner_bottom_left,
    StampPlace.TOP_RIGHT to R.string.corner_top_right, StampPlace.TOP_LEFT to R.string.corner_top_left)

@Composable
fun <T> PlaceChips(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled, label = { Text(stringResource(label)) }) }
    }
}

/** Settings for marks on new scans. Both start off; turning them on never changes pages already saved. */
@Composable
fun MarkSettingsCard() {
    val context = LocalContext.current
    val settings = remember(context) { MarkSettings(context.applicationContext) }
    var watermarkOn by remember { mutableStateOf(settings.watermarkOn) }
    var text by remember { mutableStateOf(settings.watermarkText) }
    var place by remember { mutableStateOf(settings.watermarkPlace) }
    var stampOn by remember { mutableStateOf(settings.stampOn) }
    var corner by remember { mutableStateOf(settings.stampPlace) }
    Column(Modifier.fillMaxWidth().testTag("mark-settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mark_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mark_settings_hint), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.watermark_new_scans), Modifier.weight(1f))
            Switch(watermarkOn, { watermarkOn = it; settings.watermarkOn = it }, modifier = Modifier.testTag("watermark-switch"))
        }
        OutlinedTextField(text, { text = it.take(40); settings.watermarkText = text }, enabled = watermarkOn, singleLine = true,
            label = { Text(stringResource(R.string.watermark_text)) }, modifier = Modifier.fillMaxWidth())
        PlaceChips(markPlaceLabels, place, { place = it; settings.watermarkPlace = it }, watermarkOn)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.timestamp_new_scans), Modifier.weight(1f))
            Switch(stampOn, { stampOn = it; settings.stampOn = it }, modifier = Modifier.testTag("timestamp-switch"))
        }
        PlaceChips(stampPlaceLabels, corner, { corner = it; settings.stampPlace = it }, stampOn)
    }
}
