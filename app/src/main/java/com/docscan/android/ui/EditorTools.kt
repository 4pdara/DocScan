package com.docscan.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscan.android.R
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.data.adjustments
import com.docscan.android.processing.ImageAdjustments
import com.docscan.android.processing.PageFormat
import com.docscan.android.processing.ProDocumentFilters
import com.docscan.android.processing.ScanFilter
import kotlin.math.roundToInt

@Composable
fun EditorTool(icon: Int, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 72.dp), enabled = enabled,
        contentPadding = PaddingValues(8.dp), shape = RoundedCornerShape(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterPicker(page: PageEntity, repository: DocumentRepository, busy: Boolean,
    onFilter: (ScanFilter) -> Unit, onStrength: (Int) -> Unit, onDismiss: () -> Unit) {
    val filtersLabel = stringResource(R.string.filters_label)
    val thumbnails = rememberLazyListState(initialFirstVisibleItemIndex = ScanFilter.entries.indexOfFirst { it.name == page.filter }.coerceAtLeast(0))
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * .85f).toDp() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.open_filters), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PagePreview(page, repository, Modifier.fillMaxWidth().height(160.dp), edge = 900)
            LazyRow(Modifier.semantics { contentDescription = filtersLabel }, state = thumbnails, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ScanFilter.entries, key = { it.name }) { filter ->
                    val selected = page.filter == filter.name
                    val label = filterLabel(filter)
                    Column(Modifier.width(88.dp).border(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(14.dp)).selectable(selected, enabled = !busy, role = Role.RadioButton,
                        onClick = { onFilter(filter) }).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PagePreview(page.copy(filter = filter.name), repository, Modifier.fillMaxWidth().height(64.dp), edge = 160)
                        Text(label, Modifier.padding(top = 6.dp).heightIn(min = 32.dp), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val filter = ScanFilter.valueOf(page.filter)
            if (filter in ProDocumentFilters.supported || filter == ScanFilter.CLEAN_COLOR || filter == ScanFilter.CLEAN_TEXT) {
                AdjustmentControl(stringResource(R.string.filter_strength), page.filterStrength, 0..100, !busy, onStrength)
            }
            Text(stringResource(when (filter) {
                ScanFilter.ADAPTIVE_MONO -> R.string.adaptive_hint
                ScanFilter.BLACKBOARD -> R.string.blackboard_hint
                else -> R.string.original_preserved
            }), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.done)) }
        }
    }
}

private val adjustmentsSaver = Saver<ImageAdjustments, List<Int>>(
    save = { listOf(it.brightness, it.contrast, it.saturation, it.sharpness) },
    restore = { ImageAdjustments(it[0], it[1], it[2], it[3]) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustmentSheet(page: PageEntity, repository: DocumentRepository, onApply: (ImageAdjustments) -> Unit, onDismiss: () -> Unit) {
    var settings by rememberSaveable(page.id, page.brightness, page.contrast, page.saturation, page.sharpness,
        stateSaver = adjustmentsSaver) { mutableStateOf(page.adjustments()) }
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * .85f).toDp() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.image_adjustments), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PagePreview(page.copy(brightness = settings.brightness, contrast = settings.contrast,
                    saturation = settings.saturation, sharpness = settings.sharpness), repository,
                    Modifier.fillMaxWidth().height(150.dp), edge = 900)
                AdjustmentControl(stringResource(R.string.adjust_brightness), settings.brightness, -100..100) { settings = settings.copy(brightness = it) }
                AdjustmentControl(stringResource(R.string.adjust_contrast), settings.contrast, -100..100) { settings = settings.copy(contrast = it) }
                AdjustmentControl(stringResource(R.string.adjust_saturation), settings.saturation, -100..100) { settings = settings.copy(saturation = it) }
                AdjustmentControl(stringResource(R.string.adjust_sharpness), settings.sharpness, 0..100) { settings = settings.copy(sharpness = it) }
                Text(stringResource(R.string.sharpness_hint), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { settings = ImageAdjustments() }) { Text(stringResource(R.string.reset_adjustments)) }
                OutlinedButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { onApply(settings) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.apply_adjustments)) }
            }
        }
    }
}

@Composable
fun AdjustmentControl(label: String, value: Int, range: IntRange, enabled: Boolean = true, onChanged: (Int) -> Unit) {
    var shown by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(if (range.first == 0) "${shown.roundToInt()}%" else "${shown.roundToInt()}", style = MaterialTheme.typography.labelLarge)
        }
        Slider(shown, onValueChange = { shown = it }, enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(), modifier = Modifier.semantics { contentDescription = label },
            onValueChangeFinished = { onChanged(shown.roundToInt()) })
    }
}

@Composable
fun pageFormatLabel(format: PageFormat): String = stringResource(when (format) {
    PageFormat.NATURAL -> R.string.format_natural
    PageFormat.A4 -> R.string.format_a4
    PageFormat.LETTER -> R.string.format_letter
    PageFormat.ID_CARD -> R.string.format_card
    PageFormat.SQUARE -> R.string.format_square
})
