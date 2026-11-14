package com.docscan.rebuild.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.DocumentLock
import com.docscan.rebuild.data.DocumentTags
import com.docscan.rebuild.data.PdfSettings
import com.docscan.rebuild.export.PdfLayout

/** Page size, orientation and margins for every PDF this phone exports. */
@Composable
fun PdfSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { PdfSettings(context) }
    var layout by remember { mutableStateOf(settings.layout) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.pdf_settings)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkCaption(R.string.pdf_paper)
            PlaceChips(listOf(PdfLayout.Paper.FIT to R.string.pdf_paper_fit, PdfLayout.Paper.A4 to R.string.pdf_paper_a4, PdfLayout.Paper.LETTER to R.string.pdf_paper_letter,
                PdfLayout.Paper.LEGAL to R.string.pdf_paper_legal, PdfLayout.Paper.A5 to R.string.pdf_paper_a5), layout.paper, { layout = layout.copy(paper = it) }, tag = "pdf-paper")
            MarkCaption(R.string.pdf_orientation)
            PlaceChips(listOf(PdfLayout.Orientation.AUTO to R.string.pdf_orientation_auto, PdfLayout.Orientation.PORTRAIT to R.string.pdf_orientation_portrait,
                PdfLayout.Orientation.LANDSCAPE to R.string.pdf_orientation_landscape), layout.orientation, { layout = layout.copy(orientation = it) }, enabled = layout.paper != PdfLayout.Paper.FIT, tag = "pdf-orientation")
            MarkCaption(R.string.pdf_margin)
            PlaceChips(listOf(PdfLayout.Margin.NONE to R.string.pdf_margin_none, PdfLayout.Margin.SMALL to R.string.pdf_margin_small, PdfLayout.Margin.NORMAL to R.string.pdf_margin_normal),
                layout.margin, { layout = layout.copy(margin = it) }, tag = "pdf-margin")
            Text(stringResource(if (layout.paper == PdfLayout.Paper.FIT) R.string.pdf_paper_fit_hint else R.string.pdf_paper_hint), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ settings.layout = layout; onDismiss() }, modifier = Modifier.testTag("pdf-settings-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** The document's tags: remove one, type a new one, or pick one already used on other documents. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsDialog(current: List<String>, suggestions: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var tags by remember { mutableStateOf(current) }
    var typed by remember { mutableStateOf("") }
    fun add(tag: String) { val t = DocumentTags.clean(tag); if (t.isNotEmpty() && t !in tags) tags = tags + t; typed = "" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tags)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tags.isEmpty()) Text(stringResource(R.string.tags_none), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(selected = true, onClick = { tags = tags - tag }, label = { Text(tag) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.delete), Modifier.size(16.dp)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, { typed = it.take(30) }, Modifier.weight(1f).testTag("tag-text"), singleLine = true, placeholder = { Text(stringResource(R.string.tag_new)) })
                IconButton({ add(typed) }, enabled = typed.isNotBlank(), modifier = Modifier.testTag("tag-add")) { Icon(Icons.Outlined.Add, stringResource(R.string.tag_new)) }
            }
            val others = suggestions.filter { it !in tags }
            if (others.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { tag -> SuggestionChip(onClick = { add(tag) }, label = { Text(tag) }) }
            }
        } },
        confirmButton = { TextButton({ onSave(if (typed.isNotBlank()) tags + DocumentTags.clean(typed) else tags) }, modifier = Modifier.testTag("tags-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/**
 * Asks for the lock code. With [confirm] it asks twice to set a new one (4-8 digits) and warns that it cannot be
 * recovered; otherwise it checks the code with [check] and says when it is wrong.
 */
@Composable
fun PinDialog(title: Int, confirm: Boolean, onDismiss: () -> Unit, check: (String) -> Boolean = { true }, onCode: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val valid = DocumentLock.valid(first) && (!confirm || first == second)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (confirm) Text(stringResource(R.string.lock_code_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(first, { first = it.filter(Char::isDigit).take(8); wrong = false }, singleLine = true, label = { Text(stringResource(R.string.lock_code)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = wrong, modifier = Modifier.fillMaxWidth().testTag("lock-code"))
            if (confirm) OutlinedTextField(second, { second = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text(stringResource(R.string.lock_code_again)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = second.isNotEmpty() && second != first, modifier = Modifier.fillMaxWidth().testTag("lock-code-again"))
            if (wrong) Text(stringResource(R.string.lock_code_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ if (check(first)) onCode(first) else { wrong = true; first = "" } }, enabled = valid, modifier = Modifier.testTag("lock-code-ok")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Collage layouts: pages per sheet as columns x rows. */
val collageLayouts = listOf(Triple(1, 2, R.string.collage_two_stacked), Triple(2, 1, R.string.collage_two_side), Triple(2, 2, R.string.collage_four),
    Triple(2, 3, R.string.collage_six), Triple(3, 3, R.string.collage_nine))

@Composable
fun CollageDialog(count: Int, onDismiss: () -> Unit, onCreate: (Int, Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.collage)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.collage_hint, count), style = MaterialTheme.typography.bodySmall)
            collageLayouts.forEach { (columns, rows, label) ->
                val sheets = (count + columns * rows - 1) / (columns * rows)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onCreate(columns, rows) }.padding(vertical = 10.dp, horizontal = 6.dp)
                    .testTag("collage-${columns}x$rows"), verticalAlignment = Alignment.CenterVertically) {
                    GridGlyph(columns, rows)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), Modifier.weight(1f))
                    Text(stringResource(R.string.collage_sheets, sheets), style = MaterialTheme.typography.labelSmall)
                }
            }
        } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** A small drawing of the grid. */
@Composable
private fun GridGlyph(columns: Int, rows: Int) {
    val colour = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(28.dp, 36.dp)) {
        val gap = 2.dp.toPx(); val w = (size.width - (columns - 1) * gap) / columns; val h = (size.height - (rows - 1) * gap) / rows
        for (r in 0 until rows) for (c in 0 until columns) drawRect(colour, androidx.compose.ui.geometry.Offset(c * (w + gap), r * (h + gap)), androidx.compose.ui.geometry.Size(w, h))
    }
}

/** Settings: change or remove the code of locked documents. */
@Composable
fun LockSettings() {
    val context = LocalContext.current
    val lock = remember { DocumentLock(context) }
    val changes by DocumentLock.changes.collectAsState()
    val hasCode = remember(changes) { lock.hasCode }
    val count = remember(changes) { lock.lockedIds().size }
    var step by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("lock-settings"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
        Text(if (hasCode) stringResource(R.string.lock_status, count) else stringResource(R.string.lock_none), style = MaterialTheme.typography.bodySmall)
        if (hasCode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ step = "change-old" }) { Text(stringResource(R.string.lock_change)) }
            TextButton({ step = "remove" }) { Text(stringResource(R.string.lock_remove_code), color = MaterialTheme.colorScheme.error) }
        }
    }
    when (step) {
        "change-old" -> PinDialog(R.string.lock_enter, confirm = false, onDismiss = { step = null }, check = { lock.check(it) }) { step = "change-new" }
        "change-new" -> PinDialog(R.string.lock_new_code, confirm = true, onDismiss = { step = null }) { lock.setCode(it); step = null }
        "remove" -> PinDialog(R.string.lock_remove_code, confirm = false, onDismiss = { step = null }, check = { lock.check(it) }) { lock.removeCode(); step = null }
    }
}
