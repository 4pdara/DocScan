package com.docscan.rebuild.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.BuildConfig
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*
import java.text.DateFormat
import java.util.Date

private val HomeGreen = Color(0xFF00B69D)
private val HomeMuted = Color(0xFF777D7D)
private val HomeTile = Color(0xFFF7F7F7)

@Composable
fun LibraryScreen(
    store: ScanStore, entries: List<LibraryEntry>, trash: List<LibraryEntry>,
    onScan: () -> Unit, onImport: () -> Unit, onOpen: (String) -> Unit, onExtractText: () -> Unit = onScan,
    onShare: (String) -> Unit, onPdf: (String) -> Unit,
    onTrash: (Set<String>, Boolean) -> Unit, snack: SnackbarHostState, onBackup: () -> Unit = {}, onRestoreBackup: () -> Unit = {},
    onDeleteForever: (Set<String>) -> Unit = {}, onTool: (String) -> Unit = {},
    tags: Map<String, List<String>> = emptyMap(), locked: Set<String> = emptySet()
) {
    var search by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("home") }
    var inTrash by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val allTags = entries.flatMap { tags[it.book.id].orEmpty() }.distinct().sorted()
    LaunchedEffect(allTags) { if (tagFilter != null && tagFilter !in allTags) tagFilter = null }
    // Search finds titles and tags; a chosen tag shows only its documents.
    val visible = (if (inTrash) trash else entries).filter { e -> e.book.title.contains(search, true) || tags[e.book.id].orEmpty().any { it.contains(search, true) } }
        .filter { e -> tagFilter == null || tagFilter in tags[e.book.id].orEmpty() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id; selection = true }
    fun clear() { selection = false; selected = emptyList() }
    fun files() { section = "files"; inTrash = false; clear() }
    fun trash() { section = "files"; inTrash = !inTrash; clear() }
    LaunchedEffect(entries, trash, inTrash, search) { selected = selected.filter { id -> visible.any { it.book.id == id } } }
    BackHandler(selection || inTrash || section != "home") {
        when { selection -> clear(); inTrash -> inTrash = false; else -> section = "home" }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(pluralStringResource(R.plurals.delete_forever_title, selected.size, selected.size)) },
        text = { Text(stringResource(R.string.delete_forever_message)) },
        confirmButton = { TextButton({ confirmDelete = false; onDeleteForever(selected.toSet()); clear() }, modifier = Modifier.testTag("confirm-delete-forever")) {
            Text(stringResource(R.string.delete_forever), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    Scaffold(
        containerColor = Color.White,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column(Modifier.statusBarsPadding().background(Color.White)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(search, { search = it; if (section == "tools" || section == "me") section = "home" },
                        modifier = Modifier.weight(1f).testTag("home-search"),
                        placeholder = { Text(stringResource(R.string.search)) }, singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = HomeTile, unfocusedContainerColor = HomeTile,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = HomeMuted) })
                    IconButton(onClick = { selection = !selection; selected = emptyList() }) {
                        Icon(if (selection) Icons.Outlined.Close else Icons.Outlined.Checklist,
                            stringResource(if (selection) R.string.cancel else R.string.select_documents), tint = HomeMuted)
                    }
                    IconButton(onClick = ::trash) { Icon(if (inTrash) Icons.Outlined.Folder else Icons.Outlined.DeleteOutline,
                        stringResource(if (inTrash) R.string.library else R.string.trash), tint = HomeGreen) }
                }
                if (selection) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.selected_count, selected.size), Modifier.weight(1f))
                    IconButton(onClick = { selected = if (visible.all { it.book.id in selected }) emptyList() else visible.map { it.book.id } }) {
                        Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all))
                    }
                    IconButton(onClick = { onTrash(selected.toSet(), inTrash); clear() }, enabled = selected.isNotEmpty()) {
                        Icon(if (inTrash) Icons.Outlined.RestoreFromTrash else Icons.Outlined.DeleteOutline,
                            stringResource(if (inTrash) R.string.restore else R.string.move_to_trash))
                    }
                    if (inTrash) IconButton(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("delete-forever")) {
                        Icon(Icons.Outlined.DeleteForever, stringResource(R.string.delete_forever), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.testTag("home-navigation")) {
                listOf(Triple("home", R.string.home_tab, Icons.Outlined.Home), Triple("files", R.string.files_tab, Icons.Outlined.Description),
                    Triple("tools", R.string.tools_tab, Icons.Outlined.GridView), Triple("me", R.string.settings_tab, Icons.Outlined.Settings)).forEach { (id, label, icon) ->
                    NavigationBarItem(selected = section == id && !inTrash, onClick = { section = id; inTrash = false; clear() },
                        icon = { Icon(icon, null) }, label = { Text(stringResource(label), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = HomeGreen, selectedTextColor = HomeGreen,
                            unselectedIconColor = Color(0xFFB8BABA), unselectedTextColor = HomeMuted, indicatorColor = Color.Transparent),
                        modifier = Modifier.testTag("home-tab-$id"))
                }
            }
        },
        floatingActionButtonPosition = if (rtl) FabPosition.End else FabPosition.Start,
        floatingActionButton = {
            // Scanning starts from the home and files pages; Tools and Settings have their own entries.
            if (!selection && !inTrash && (section == "home" || section == "files")) FloatingActionButton(onScan, shape = CircleShape, containerColor = HomeGreen,
                contentColor = Color.White, modifier = Modifier.size(64.dp).testTag("new-scan")) {
                Icon(Icons.Outlined.CameraAlt, stringResource(R.string.new_scan), Modifier.size(30.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("library")) {
            when {
                section == "me" -> LocalProfile(onFiles = ::files, onTrash = ::trash, onBackup = onBackup, onRestoreBackup = onRestoreBackup)
                section == "tools" -> ToolsSections({ tool -> if (tool == "settings") section = "me" else onTool(tool) }, onFiles = ::files, onTrash = ::trash, onSelect = { selection = true; section = "files" })
                else -> LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (section == "home" && !selection && !inTrash && search.isBlank()) item(key = "shortcuts") {
                        HomeShortcuts(onScan, onExtractText, onImport, ::files, ::trash, { selection = true; section = "files" },
                            onRecent = { entries.firstOrNull()?.let { onOpen(it.book.id) } ?: onScan() },
                            onPdf = { entries.firstOrNull { it.pageCount > 0 }?.let { onPdf(it.book.id) } ?: onScan() },
                            onShare = { entries.firstOrNull { it.pageCount > 0 }?.let { onShare(it.book.id) } ?: onScan() })
                    }
                    item(key = "heading") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (inTrash) R.string.trash else if (section == "home") R.string.recent_documents else R.string.my_documents),
                                Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            if (section == "home" && !selection) TextButton(onClick = ::files) {
                                Text(stringResource(R.string.show_all), color = HomeMuted)
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp), tint = HomeMuted)
                            }
                        }
                        if (inTrash) Text(stringResource(R.string.trash_hint), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        if (section == "files" && !inTrash && allTags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("tag-filter"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(tagFilter == null, { tagFilter = null }, label = { Text(stringResource(R.string.tags_all)) })
                            allTags.forEach { tag -> FilterChip(tagFilter == tag, { tagFilter = if (tagFilter == tag) null else tag }, label = { Text(tag) }, modifier = Modifier.testTag("tag-filter-$tag")) }
                        }
                    }
                    if (visible.isEmpty()) item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(54.dp), tint = HomeGreen)
                            Spacer(Modifier.height(14.dp))
                            Text(stringResource(if (search.isNotBlank()) R.string.search_empty else if (inTrash) R.string.empty_trash else R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                            if (!inTrash && search.isBlank()) TextButton(onImport) { Text(stringResource(R.string.import_images)) }
                        }
                    }
                    itemsIndexed(visible, key = { _, entry -> entry.book.id }) { index, entry ->
                        RecentDocumentRow(store, entry, entry.book.id in selected, tags[entry.book.id].orEmpty(), entry.book.id in locked,
                            onOpen = { if (selection || inTrash) toggle(entry.book.id) else onOpen(entry.book.id) },
                            onSelect = { toggle(entry.book.id) })
                        if (index == 0 && section == "home" && !selection && !inTrash && search.isBlank() && entry.pageCount > 0) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                QuickDocumentAction(R.string.share, Modifier.weight(1f)) { onShare(entry.book.id) }
                                QuickDocumentAction(R.string.export_pdf, Modifier.weight(1f)) { onPdf(entry.book.id) }
                                QuickDocumentAction(R.string.view_document, Modifier.weight(1f)) { onOpen(entry.book.id) }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = HomeTile)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentDocumentRow(store: ScanStore, entry: LibraryEntry, checked: Boolean, tags: List<String>, locked: Boolean, onOpen: () -> Unit, onSelect: () -> Unit) {
    val pages by remember(entry.book.id) { store.dao.pages(entry.book.id) }.collectAsState(emptyList())
    Row(Modifier.fillMaxWidth().background(if (checked) Color(0xFFE9F9F5) else Color.White)
        .combinedClickable(onClick = onOpen, onLongClick = onSelect).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color(0xFFEEEEEE), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            // A locked document shows no preview.
            if (locked) Icon(Icons.Outlined.Lock, stringResource(R.string.locked), Modifier.testTag("locked-${entry.book.id}"), tint = HomeGreen)
            else pages.firstOrNull()?.let { PagePicture(store, it, Modifier.fillMaxSize().padding(3.dp), edge = 240) }
                ?: Icon(Icons.Outlined.Description, null, tint = HomeMuted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(entry.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.book.created)),
                style = MaterialTheme.typography.bodySmall, color = HomeMuted, maxLines = 1)
            Text(pluralStringResource(R.plurals.pages, entry.pageCount, entry.pageCount), style = MaterialTheme.typography.labelSmall, color = HomeMuted)
            if (tags.isNotEmpty()) Text(tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = HomeGreen, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(checked, { onSelect() }, modifier = Modifier.testTag("select-${entry.book.id}"),
            colors = CheckboxDefaults.colors(checkedColor = HomeGreen, uncheckedColor = Color(0xFFD5D8D7)))
    }
}

@Composable
private fun QuickDocumentAction(label: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick, modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(6.dp), color = HomeTile) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeShortcuts(onScan: () -> Unit, onExtractText: () -> Unit, onImport: () -> Unit, onFiles: () -> Unit, onTrash: () -> Unit,
    onSelect: () -> Unit, withSelection: Boolean = false, onRecent: () -> Unit, onPdf: () -> Unit, onShare: () -> Unit) {
    // Document selection is always in the top bar; the home grid gives its place to text extraction.
    val tools = listOf(
        Triple(R.string.smart_scan, Icons.Outlined.DocumentScanner, onScan), Triple(R.string.extract_text, Icons.Outlined.TextFields, onExtractText),
        Triple(R.string.pdf_tools, Icons.Outlined.PictureAsPdf, onPdf), Triple(R.string.import_images, Icons.Outlined.Image, onImport),
        Triple(R.string.recent_document, Icons.Outlined.EditNote, onRecent), Triple(R.string.share, Icons.Outlined.Share, onShare),
        Triple(R.string.files_tab, Icons.Outlined.FolderOpen, onFiles), Triple(R.string.trash, Icons.Outlined.RestoreFromTrash, onTrash)) +
        (if (withSelection) listOf(Triple(R.string.select_documents, Icons.Outlined.Checklist, onSelect)) else emptyList())
    Column(Modifier.fillMaxWidth().background(Color(0xFFFCFCFC)).padding(horizontal = 10.dp, vertical = 10.dp).testTag("home-shortcuts")) {
        tools.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (label, icon, action) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = action).padding(vertical = 8.dp)
                        .testTag(if (label == R.string.extract_text) "shortcut-extract-text" else "shortcut-$label"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).border(1.dp, Color(0xFFEEEEEE), CircleShape).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(26.dp), tint = when (label) { R.string.pdf_tools -> Color(0xFFFF715D); R.string.import_images, R.string.files_tab -> Color(0xFF4480E8); else -> HomeGreen })
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
                    }
                }
                // Keep a short last row aligned to the grid instead of stretching its tiles.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LocalProfile(onFiles: () -> Unit, onTrash: () -> Unit, onBackup: () -> Unit, onRestoreBackup: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("settings-page")) {
        Text(stringResource(R.string.settings_tab), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(44.dp), tint = HomeGreen)
            Spacer(Modifier.width(12.dp))
            Column { Text("DocScan", style = MaterialTheme.typography.titleLarge); Text(BuildConfig.VERSION_NAME, color = HomeMuted) }
        }
        Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.local_profile), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp)); Text(stringResource(R.string.local_profile_hint))
        Spacer(Modifier.height(18.dp)); Text(stringResource(R.string.quality), color = HomeGreen)
        TextButton(onFiles) { Text(stringResource(R.string.my_documents)) }
        TextButton(onTrash) { Text(stringResource(R.string.trash)) }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        Text(stringResource(R.string.backup_hint),style=MaterialTheme.typography.bodySmall)
        TextButton(onBackup,modifier=Modifier.testTag("backup-library")) { Icon(Icons.Outlined.Archive,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.backup_library)) }
        TextButton(onRestoreBackup,modifier=Modifier.testTag("restore-library")) { Icon(Icons.Outlined.Unarchive,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.restore_backup)) }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        UpdateCard()
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        var pdfSettings by remember { mutableStateOf(false) }
        Text(stringResource(R.string.pdf_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pdf_settings_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ pdfSettings = true }, modifier = Modifier.testTag("settings-pdf")) { Text(stringResource(R.string.pdf_settings)) }
        if (pdfSettings) PdfSettingsDialog(onDismiss = { pdfSettings = false })
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        LockSettings()
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        MarkSettingsCard()
    }
}

/** The Tools tab, grouped like a scanner's tool page: scan, import, convert, edit, utilities. */
@Composable
private fun ToolsSections(onTool: (String) -> Unit, onFiles: () -> Unit, onTrash: () -> Unit, onSelect: () -> Unit) {
    data class Tool(val label: Int, val icon: ImageVector, val tint: Color, val action: () -> Unit)
    val blue = Color(0xFF4480E8); val red = Color(0xFFFF715D)
    val groups = listOf(
        R.string.tools_scan to listOf(Tool(R.string.smart_scan, Icons.Outlined.DocumentScanner, HomeGreen) { onTool("scan") }, Tool(R.string.extract_text, Icons.Outlined.TextFields, HomeGreen) { onTool("ocr") },
            Tool(R.string.id_card, Icons.Outlined.Badge, HomeGreen) { onTool("idcard") }, Tool(R.string.idphoto, Icons.Outlined.AccountBox, blue) { onTool("idphoto") }, Tool(R.string.tool_book, Icons.Outlined.AutoStories, blue) { onTool("book") },
            Tool(R.string.tool_whiteboard, Icons.Outlined.Dashboard, HomeGreen) { onTool("board") }, Tool(R.string.tool_slides, Icons.Outlined.Slideshow, red) { onTool("slides") },
            Tool(R.string.timestamp_camera, Icons.Outlined.Schedule, blue) { onTool("stampscan") }),
        R.string.tools_import to listOf(Tool(R.string.import_images, Icons.Outlined.Image, blue) { onTool("import") }, Tool(R.string.import_pdf, Icons.Outlined.FileOpen, blue) { onTool("import") }),
        R.string.tools_convert to listOf(Tool(R.string.to_word, Icons.Outlined.Description, blue) { onTool("word") }, Tool(R.string.to_excel, Icons.Outlined.TableChart, HomeGreen) { onTool("excel") },
            Tool(R.string.to_powerpoint, Icons.Outlined.Slideshow, red) { onTool("powerpoint") }, Tool(R.string.pdf_to_images, Icons.Outlined.PhotoLibrary, HomeGreen) { onTool("images") },
            Tool(R.string.long_image, Icons.Outlined.ViewDay, HomeGreen) { onTool("long") }),
        R.string.tools_edit to listOf(Tool(R.string.signature, Icons.Outlined.Draw, blue) { onTool("signature") }, Tool(R.string.watermark, Icons.Outlined.WaterDrop, blue) { onTool("watermark") },
            Tool(R.string.smart_erase, Icons.Outlined.AutoFixHigh, HomeGreen) { onTool("erase") }, Tool(R.string.colour_marks_short, Icons.Outlined.FormatColorReset, blue) { onTool("erasecolor") },
            Tool(R.string.edit_text, Icons.Outlined.TextFields, HomeGreen) { onTool("text") }, Tool(R.string.header_short, Icons.Outlined.FormatListNumbered, blue) { onTool("footer") },
            Tool(R.string.timestamp, Icons.Outlined.Schedule, HomeGreen) { onTool("timestamp") }, Tool(R.string.merge_documents, Icons.Outlined.MergeType, red) { onTool("merge") },
            Tool(R.string.extract_pages, Icons.Outlined.ContentCut, red) { onTool("extract") }, Tool(R.string.reorder_pages, Icons.Outlined.SwapVert, blue) { onTool("reorder") },
            Tool(R.string.pdf_size, Icons.Outlined.Compress, red) { onTool("compress") }, Tool(R.string.protected_pdf, Icons.Outlined.Lock, HomeGreen) { onTool("protect") },
            Tool(R.string.collage, Icons.Outlined.Dashboard, blue) { onTool("collage") }, Tool(R.string.lock_document, Icons.Outlined.Lock, red) { onTool("lock") },
            Tool(R.string.tags, Icons.Outlined.Sell, HomeGreen) { onTool("tags") }, Tool(R.string.pdf_settings, Icons.Outlined.Settings, HomeMuted) { onTool("pdfsettings") }),
        R.string.tools_utilities to listOf(Tool(R.string.qr_scan, Icons.Outlined.QrCodeScanner, HomeGreen) { onTool("qr") }, Tool(R.string.print, Icons.Outlined.Print, HomeGreen) { onTool("print") },
            Tool(R.string.email, Icons.Outlined.Email, blue) { onTool("email") }, Tool(R.string.files_tab, Icons.Outlined.FolderOpen, blue, onFiles),
            Tool(R.string.select_documents, Icons.Outlined.Checklist, HomeGreen, onSelect), Tool(R.string.trash, Icons.Outlined.RestoreFromTrash, HomeGreen, onTrash),
            Tool(R.string.settings_tab, Icons.Outlined.Settings, HomeMuted) { onTool("settings") }))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp).testTag("tools-sections")) {
        groups.forEach { (title, tools) ->
            Text(stringResource(title), Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
            tools.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { tool ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = tool.action).padding(vertical = 8.dp).testTag("tools-${tool.label}"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(52.dp).background(tool.tint.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Icon(tool.icon, null, Modifier.size(26.dp), tint = tool.tint) }
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(tool.label), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Choose documents to merge; the order of the taps is the order of their pages in the new document. */
@Composable
fun MergeDialog(choices: List<LibraryEntry>, onDismiss: () -> Unit, onMerge: (List<String>) -> Unit) {
    var order by remember { mutableStateOf(listOf<String>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.merge_documents)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (choices.size < 2) R.string.merge_need_two else R.string.merge_hint), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp).testTag("merge-list")) {
                items(choices, key = { it.book.id }) { entry ->
                    val at = order.indexOf(entry.book.id)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { order = if (at >= 0) order - entry.book.id else order + entry.book.id }
                        .padding(vertical = 8.dp, horizontal = 4.dp).testTag("merge-${entry.book.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).background(if (at >= 0) HomeGreen else HomeTile, CircleShape), contentAlignment = Alignment.Center) {
                            if (at >= 0) Text("${at + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(entry.book.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.pages, entry.pageCount, entry.pageCount), style = MaterialTheme.typography.labelSmall, color = HomeMuted)
            if (tags.isNotEmpty()) Text(tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = HomeGreen, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } },
        confirmButton = { TextButton({ onMerge(order) }, enabled = order.size >= 2, modifier = Modifier.testTag("merge-confirm")) { Text(stringResource(R.string.merge)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
