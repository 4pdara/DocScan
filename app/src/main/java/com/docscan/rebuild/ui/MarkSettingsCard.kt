package com.docscan.rebuild.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.MarkSettings
import com.docscan.rebuild.imaging.PageMarks
import com.docscan.rebuild.model.*

val markPlaceLabels = listOf(MarkPlace.DIAGONAL to R.string.place_diagonal, MarkPlace.CENTER to R.string.place_center,
    MarkPlace.TOP to R.string.place_top, MarkPlace.BOTTOM to R.string.place_bottom, MarkPlace.SIDE to R.string.place_side)
val stampPlaceLabels = listOf(StampPlace.BOTTOM_RIGHT to R.string.corner_bottom_right, StampPlace.BOTTOM_LEFT to R.string.corner_bottom_left,
    StampPlace.TOP_RIGHT to R.string.corner_top_right, StampPlace.TOP_LEFT to R.string.corner_top_left)
val notePlaceLabels = listOf(StampPlace.BOTTOM_CENTER to R.string.corner_bottom_center) + stampPlaceLabels + listOf(StampPlace.TOP_CENTER to R.string.corner_top_center)
private val fontLabels = listOf(MarkFont.BOLD to R.string.font_bold, MarkFont.PLAIN to R.string.font_plain, MarkFont.CLASSIC to R.string.font_classic,
    MarkFont.DAVID to R.string.font_david, MarkFont.HEADLINE to R.string.font_headline, MarkFont.FESTIVE to R.string.font_festive, MarkFont.HANDWRITING to R.string.font_handwriting)
private val colourLabels = listOf(MarkColor.GREY to R.string.color_grey, MarkColor.BLACK to R.string.color_black, MarkColor.RED to R.string.color_red,
    MarkColor.BLUE to R.string.color_blue, MarkColor.GREEN to R.string.color_green, MarkColor.ORANGE to R.string.color_orange)
private val strengthLabels = listOf(MarkStrength.LIGHT to R.string.strength_light, MarkStrength.MEDIUM to R.string.strength_medium, MarkStrength.STRONG to R.string.strength_strong)
private val sizeLabels = listOf(MarkSize.SMALL to R.string.size_small, MarkSize.MEDIUM to R.string.size_medium, MarkSize.LARGE to R.string.size_large)

@Composable
fun <T> PlaceChips(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true, tag: String? = null) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled, label = { Text(stringResource(label)) },
                modifier = if (tag != null) Modifier.testTag("$tag-${value.toString().lowercase()}") else Modifier)
        }
    }
}

@Composable
fun MarkCaption(label: Int) = Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))

/** A blank page with grey lines of "text", for previews where there is no scanned page yet. */
fun samplePage(): Bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).also { bitmap ->
    val canvas = Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
    val ink = Paint().apply { color = android.graphics.Color.rgb(205, 205, 205) }
    var y = 90f
    while (y < 720f) { canvas.drawRect(70f, y, if (((y / 28).toInt()) % 5 == 4) 330f else 530f, y + 10f, ink); y += 28f }
}

/** The page with the marks drawn on it, exactly as saved pages are drawn. */
@Composable
fun MarkPreview(base: Bitmap?, marks: EditRecipe, modifier: Modifier = Modifier) {
    val page = base ?: remember { samplePage() }
    val shown = remember(page, marks) { page.copy(Bitmap.Config.ARGB_8888, true).also { PageMarks.draw(it, marks) } }
    Box(modifier.fillMaxWidth().height(230.dp).background(Color(0xFFE9ECEC), RoundedCornerShape(8.dp)).padding(8.dp).testTag("mark-preview"), contentAlignment = Alignment.Center) {
        Image(shown.asImageBitmap(), stringResource(R.string.mark_preview), Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
    }
}

@Composable
fun ColourChoice(selected: MarkColor, onSelect: (MarkColor) -> Unit, enabled: Boolean = true) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colourLabels.forEach { (colour, label) ->
            val name = stringResource(label)
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or colour.rgb.toLong()))
                .border(if (colour == selected) 3.dp else 1.dp, if (colour == selected) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape)
                .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(colour) }
                .semantics { contentDescription = name; this.selected = colour == selected }.testTag("colour-${colour.name.lowercase()}"),
                contentAlignment = Alignment.Center) {
                if (colour == selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Font, colour, strength and size of a watermark. Each font chip is written in its own font. */
@Composable
fun WatermarkStyleControls(place: MarkPlace, style: MarkStyle, onPlace: (MarkPlace) -> Unit, onStyle: (MarkStyle) -> Unit, enabled: Boolean = true) {
    MarkCaption(R.string.mark_place)
    PlaceChips(markPlaceLabels, place, onPlace, enabled)
    MarkCaption(R.string.mark_font)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fontLabels.forEach { (font, label) ->
            val family = remember(font) { FontFamily(PageMarks.typeface(font)) }
            FilterChip(selected = font == style.font, onClick = { onStyle(style.copy(font = font)) }, enabled = enabled,
                label = { Text(stringResource(label), fontFamily = family, style = MaterialTheme.typography.titleMedium) }, modifier = Modifier.testTag("font-${font.name.lowercase()}"))
        }
    }
    MarkCaption(R.string.mark_color)
    ColourChoice(style.color, { onStyle(style.copy(color = it)) }, enabled)
    MarkCaption(R.string.mark_strength)
    PlaceChips(strengthLabels, style.strength, { onStyle(style.copy(strength = it)) }, enabled)
    MarkCaption(R.string.mark_size)
    PlaceChips(sizeLabels, style.size, { onStyle(style.copy(size = it)) }, enabled)
}

/** Settings for marks on new scans. All start off; turning them on never changes pages already saved. */
@Composable
fun MarkSettingsCard() {
    val context = LocalContext.current
    val settings = remember(context) { MarkSettings(context.applicationContext) }
    var watermarkOn by remember { mutableStateOf(settings.watermarkOn) }
    var text by remember { mutableStateOf(settings.watermarkText) }
    var place by remember { mutableStateOf(settings.watermarkPlace) }
    var style by remember { mutableStateOf(settings.watermarkStyle) }
    var footerOn by remember { mutableStateOf(settings.footerOn) }
    var footer by remember { mutableStateOf(settings.footerText) }
    var footerPlace by remember { mutableStateOf(settings.footerPlace) }
    var noteColor by remember { mutableStateOf(settings.noteColor) }
    var stampOn by remember { mutableStateOf(settings.stampOn) }
    var corner by remember { mutableStateOf(settings.stampPlace) }
    val sample = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date()) }
    val preview = EditRecipe(watermark = text.trim().takeIf { watermarkOn && it.isNotEmpty() }, watermarkPlace = place, watermarkStyle = style,
        footer = footer.trim().takeIf { footerOn && it.isNotEmpty() }, footerPlace = footerPlace, noteColor = noteColor,
        stamp = sample.takeIf { stampOn }, stampPlace = corner)
    Column(Modifier.fillMaxWidth().testTag("mark-settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mark_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mark_settings_hint), style = MaterialTheme.typography.bodySmall)
        if (watermarkOn || footerOn || stampOn) MarkPreview(null, preview)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.watermark_new_scans), Modifier.weight(1f))
            Switch(watermarkOn, { watermarkOn = it; settings.watermarkOn = it }, modifier = Modifier.testTag("watermark-switch"))
        }
        if (watermarkOn) {
            OutlinedTextField(text, { text = it.take(40); settings.watermarkText = text }, singleLine = true,
                label = { Text(stringResource(R.string.watermark_text)) }, modifier = Modifier.fillMaxWidth())
            WatermarkStyleControls(place, style, { place = it; settings.watermarkPlace = it }, { style = it; settings.watermarkStyle = it })
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.footer_new_scans), Modifier.weight(1f))
            Switch(footerOn, { footerOn = it; settings.footerOn = it }, modifier = Modifier.testTag("footer-switch"))
        }
        if (footerOn) {
            OutlinedTextField(footer, { footer = it.take(60); settings.footerText = footer }, singleLine = true,
                label = { Text(stringResource(R.string.footer)) }, placeholder = { Text(stringResource(R.string.footer_example)) }, modifier = Modifier.fillMaxWidth().testTag("footer-setting-text"))
            MarkCaption(R.string.mark_place)
            PlaceChips(notePlaceLabels, footerPlace, { footerPlace = it; settings.footerPlace = it })
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.timestamp_new_scans), Modifier.weight(1f))
            Switch(stampOn, { stampOn = it; settings.stampOn = it }, modifier = Modifier.testTag("timestamp-switch"))
        }
        if (stampOn) {
            MarkCaption(R.string.timestamp_corner)
            PlaceChips(notePlaceLabels, corner, { corner = it; settings.stampPlace = it })
        }
        if (footerOn || stampOn) {
            MarkCaption(R.string.note_color)
            ColourChoice(noteColor, { noteColor = it; settings.noteColor = it })
        }
    }
}
