package com.docscan.rebuild.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscan.rebuild.R
import com.docscan.rebuild.data.*
import com.docscan.rebuild.export.DocumentOutput
import com.docscan.rebuild.imaging.*
import com.docscan.rebuild.model.*
import com.docscan.rebuild.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.*

private fun ScanTool.icon():ImageVector=when(this) {
    ScanTool.ID_CARD->Icons.Outlined.Badge;ScanTool.OCR->Icons.Outlined.TextFields;ScanTool.ID_PHOTO->Icons.Outlined.AccountBox;ScanTool.FORMULA->Icons.Outlined.Functions
    ScanTool.TRANSLATE->Icons.Outlined.Translate;ScanTool.BOOK->Icons.Outlined.MenuBook;ScanTool.SLIDES,ScanTool.POWERPOINT->Icons.Outlined.Slideshow
    ScanTool.WHITEBOARD->Icons.Outlined.Draw;ScanTool.TIMESTAMP->Icons.Outlined.Schedule;ScanTool.IMPORT_IMAGES->Icons.Outlined.Image;ScanTool.IMPORT_FILES->Icons.Outlined.FileOpen
    ScanTool.WORD->Icons.Outlined.Description;ScanTool.EXCEL->Icons.Outlined.TableChart;ScanTool.PDF_IMAGES->Icons.Outlined.Collections;ScanTool.LONG_IMAGE->Icons.Outlined.PanoramaVertical
    ScanTool.SIGNATURE->Icons.Outlined.Gesture;ScanTool.WATERMARK->Icons.Outlined.BrandingWatermark;ScanTool.SMART_ERASE,ScanTool.REMOVE_MARKS->Icons.Outlined.AutoFixHigh
    ScanTool.RESTORE_PHOTO->Icons.Outlined.AutoAwesome;ScanTool.MERGE->Icons.Outlined.Merge;ScanTool.EXTRACT->Icons.Outlined.ContentCopy;ScanTool.REORDER->Icons.Outlined.Reorder;ScanTool.LOCK->Icons.Outlined.Lock
    ScanTool.SOLVER->Icons.Outlined.Psychology;ScanTool.COUNT->Icons.Outlined.Pin;ScanTool.PRINT->Icons.Outlined.Print;ScanTool.BARCODE->Icons.Outlined.QrCodeScanner
}

@Composable
fun ToolsScreen(store:ScanStore,books:List<LibraryEntry>,onScan:(ScanTool)->Unit,onImport:()->Unit,onOcr:(String)->Unit,onOpen:(String)->Unit,snack:SnackbarHostState) {
    var activeName by rememberSaveable { mutableStateOf<String?>(null) }
    val active=activeName?.let { ScanTool.valueOf(it) }
    if(active!=null)key(active) { ToolWorkspace(active,store,books,onBack={activeName=null},onScan={onScan(active)},onImport,onOcr,onOpen,snack) }
    else BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns=if(maxWidth<340.dp)3 else 4
        LazyColumn(Modifier.fillMaxSize().testTag("categorized-tools"),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=100.dp)) {
            ToolGroup.entries.forEach { group ->
                item(key=group.name) { Text(stringResource(group.title),Modifier.fillMaxWidth().padding(top=22.dp,bottom=12.dp),style=MaterialTheme.typography.titleLarge) }
                items(ScanTool.entries.filter {it.group==group}.chunked(columns),key={it.first().name}) { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        row.forEach { tool ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { if(tool==ScanTool.IMPORT_IMAGES)onImport() else activeName=tool.name }
                                .padding(vertical=12.dp).testTag("tool-${tool.name}"),horizontalAlignment=Alignment.CenterHorizontally) {
                                val color=when(group){ToolGroup.SCAN,ToolGroup.IMPORT->Color(0xFF00A995);ToolGroup.CONVERT->Color(0xFF4587E6);ToolGroup.EDIT->Color(0xFF179DB6);ToolGroup.HELP->Color(0xFF00AA84)}
                                Box(Modifier.size(56.dp).background(color.copy(alpha=.09f),CircleShape),contentAlignment=Alignment.Center){Icon(tool.icon(),null,Modifier.size(29.dp),tint=color)}
                                Spacer(Modifier.height(8.dp));Text(stringResource(tool.label),style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center,maxLines=3,minLines=2,overflow=TextOverflow.Ellipsis)
                            }
                        };repeat(columns-row.size){Spacer(Modifier.weight(1f))}
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolWorkspace(tool:ScanTool,store:ScanStore,books:List<LibraryEntry>,onBack:()->Unit,onScan:()->Unit,onImport:()->Unit,onOcr:(String)->Unit,onOpen:(String)->Unit,snack:SnackbarHostState) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var bookId by rememberSaveable {mutableStateOf(books.firstOrNull {it.pageCount>0}?.book?.id)}
    var pageId by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedBooks by rememberSaveable {mutableStateOf(listOf<String>())}
    val pages by remember(bookId){bookId?.let {store.dao.pages(it)}?:flowOf(emptyList())}.collectAsState(emptyList())
    val page=pages.firstOrNull {it.id==pageId}?:pages.firstOrNull()
    var encoded by rememberSaveable(page?.id){mutableStateOf(page?.recipe?:"{}")};val recipe=RecipeCodec.decode(encoded)
    var field by rememberSaveable {mutableStateOf("")};var password by remember {mutableStateOf("")};var repeatedPassword by remember {mutableStateOf("")}
    var text by remember {mutableStateOf("")};var textEdited by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf("")};var targetLanguage by rememberSaveable {mutableStateOf("עברית / Hebrew")}
    var aiSettings by remember {mutableStateOf(false)};val ai=remember {AiService(context)}
    var dpi by rememberSaveable {mutableIntStateOf(300)};var darkObjects by rememberSaveable {mutableStateOf(true)};var minimum by rememberSaveable {mutableFloatStateOf(.002f)}
    var markers by remember {mutableStateOf(listOf<Vertex>())}
    var chooser by remember {mutableStateOf(false)}
    var exportPath by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(pages,tool) {if(!textEdited)text=pages.joinToString("\n\n"){it.currentText().orEmpty()}}
    fun change(r:EditRecipe){if(r.decorations.valid())encoded=RecipeCodec.encode(r)}
    fun work(success:Int?=null,block:suspend ()->Unit){if(busy)return;busy=true;scope.launch {
        try{block();if(success!=null)snack.showSnackbar(context.getString(success))}
        catch(e:CancellationException){throw e}
        catch(_:OutOfMemoryError){snack.showSnackbar(context.getString(R.string.error_memory))}
        catch(_:Exception){snack.showSnackbar(context.getString(R.string.tool_error))}
        finally{busy=false}
    }}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri ->
        val path=exportPath;exportPath=null
        if(path!=null) {val file=File(path);if(uri==null)file.delete() else work(R.string.saved){try {withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{out->file.inputStream().use{it.copyTo(out)}}}}finally{file.delete()}}}
    }
    fun deliver(file:File){exportPath=file.path;saver.launch("DocScan-${tool.name.lowercase()}.${file.extension}")}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->if(uri!=null)work(R.string.imported){val imported=PdfTools(store).import(uri,dpi);bookId=imported.id;onOpen(imported.id)}}
    BackHandler {if(!busy)onBack()}
    if(aiSettings)AiSettingsDialog(ai,{aiSettings=false},snack)
    val isAi=tool in listOf(ScanTool.TRANSLATE,ScanTool.FORMULA,ScanTool.SOLVER)
    val isDrawing=tool==ScanTool.SIGNATURE || tool==ScanTool.SMART_ERASE
    fun saveRecipe() {val p=page?:return;work(R.string.tool_saved){store.save(p,recipe)}}
    fun runTool(){val id=bookId;val p=page
        if(tool==ScanTool.IMPORT_FILES){importer.launch(arrayOf("application/pdf"));return}
        if(id==null || p==null){work{snack.showSnackbar(context.getString(R.string.tool_empty))};return}
        when(tool) {
            ScanTool.OCR->onOcr(id)
            ScanTool.BOOK->onOpen(id)
            ScanTool.WORD,ScanTool.EXCEL->work {
                if(pages.any {it.currentText()==null}){snack.showSnackbar(context.getString(R.string.ocr_first));return@work}
                deliver(if(tool==ScanTool.WORD)OfficeExport(store).word(id,if(textEdited)text else null) else OfficeExport(store).excel(id,if(textEdited)text else null))
            }
            ScanTool.POWERPOINT->work {deliver(OfficeExport(store).powerpoint(id))}
            ScanTool.PDF_IMAGES->work {deliver(PdfTools(store).images(id))}
            ScanTool.LONG_IMAGE->work {deliver(PdfTools(store).longImage(id))}
            ScanTool.ID_CARD->work{deliver(PdfTools(store).identity(id))}
            ScanTool.ID_PHOTO->work {change(p.edit().centerCrop(35f/45f,p.width,p.height))}
            ScanTool.SLIDES->{change(recipe.copy(outputAspect=16f/9f))}
            ScanTool.WHITEBOARD->{change(recipe.copy(treatment=Treatment.PAPER))}
            ScanTool.TIMESTAMP->{change(recipe.copy(decorations=recipe.decorations.copy(timestamp=if(field.isBlank())DateFormat.getDateTimeInstance().format(Date()) else field.take(100))))}
            ScanTool.WATERMARK->{change(recipe.copy(decorations=recipe.decorations.copy(watermark=field.take(160))))}
            ScanTool.REMOVE_MARKS->{change(recipe.copy(decorations=recipe.decorations.copy(removeColorMarks=true)))}
            ScanTool.RESTORE_PHOTO->{change(recipe.copy(decorations=recipe.decorations.copy(restorePhoto=true)))}
            ScanTool.SIGNATURE,ScanTool.SMART_ERASE->saveRecipe()
            ScanTool.EXTRACT->work(R.string.tool_saved){val ids=PageSelection.parse(field,pages.size).map{pages[it].id};val copy=store.copyPages(context.getString(tool.label),ids);onOpen(copy.id)}
            ScanTool.REORDER->work(R.string.tool_saved){val order=PageSelection.parse(field,pages.size);require(order.size==pages.size);store.reorder(id,order.map{pages[it].id})}
            ScanTool.MERGE->work(R.string.tool_saved){require(selectedBooks.size>=2);val ids=selectedBooks.flatMap{store.dao.snapshot(it).map{p->p.id}};val merged=store.copyPages(context.getString(tool.label),ids);onOpen(merged.id)}
            ScanTool.LOCK->work {require(password==repeatedPassword);val pdf=DocumentOutput(context,store).pdf(id);try {deliver(PdfTools(store).lock(pdf,password))}finally{pdf.delete()}}
            ScanTool.PRINT->work {val pdf=DocumentOutput(context,store).pdf(id);try{printPdf(context,pdf,books.firstOrNull{it.book.id==id}?.book?.title?:"DocScan",pages.size)}catch(t:Throwable){pdf.delete();throw t}}
            ScanTool.BARCODE->work {result=withContext(Dispatchers.Default){val bitmap=ImagePipeline.render(store.source(p),recipe,1800);try {CodeReader.read(bitmap)?:context.getString(R.string.barcode_missing)}finally{bitmap.recycle()}}}
            ScanTool.COUNT->work {markers=withContext(Dispatchers.Default){val bitmap=ImagePipeline.render(store.source(p),recipe,1200);try{PageEffects.countCandidates(bitmap,darkObjects,minimum.toDouble())}finally{bitmap.recycle()}}}
            else->Unit
        }
    }
    Column(Modifier.fillMaxSize().testTag("tool-workspace")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={if(!busy)onBack()},modifier=Modifier.testTag("tool-back")){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.back))}
            Text(stringResource(tool.label),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            if(isAi)IconButton(onClick={aiSettings=true}){Icon(Icons.Outlined.Settings,stringResource(R.string.ai_settings))}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("tool-scroll"),contentPadding=PaddingValues(16.dp,8.dp,16.dp,100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(tool.hint),style=MaterialTheme.typography.bodySmall) }
            if(tool==ScanTool.IMPORT_FILES)item {
                Text(stringResource(R.string.pdf_dpi));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(150,300,600).forEach { value->FilterChip(dpi==value,{dpi=value},label={Text("$value DPI")}) }}
                Button(onClick=::runTool,enabled=!busy){Text(stringResource(R.string.tool_import_files))}
            }
            else {
                item {
                    Box {OutlinedButton(onClick={chooser=true},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("tool-document")){Text(books.firstOrNull{it.book.id==bookId}?.book?.title?:stringResource(R.string.choose_document),maxLines=1,overflow=TextOverflow.Ellipsis)}
                        DropdownMenu(chooser,{chooser=false},modifier=Modifier.heightIn(max=320.dp)){books.filter{it.pageCount>0}.forEach { b->DropdownMenuItem(text={Text(b.book.title)},onClick={bookId=b.book.id;pageId=null;textEdited=false;result="";markers=emptyList();chooser=false}) }} }
                }
                if(tool==ScanTool.MERGE) {
                    item {Text(stringResource(R.string.merge_hint))}
                    items(books.filter{it.pageCount>0},key={it.book.id}) {b->Row(Modifier.fillMaxWidth().clickable(enabled=!busy){selectedBooks=if(b.book.id in selectedBooks)selectedBooks-b.book.id else selectedBooks+b.book.id},verticalAlignment=Alignment.CenterVertically){Checkbox(b.book.id in selectedBooks,null);Text((selectedBooks.indexOf(b.book.id).takeIf{it>=0}?.let{"${it+1}. "}?:"")+b.book.title)} }
                } else {
                    item {LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(pages,key={it.id}){p->FilterChip(page?.id==p.id,{pageId=p.id;markers=emptyList()},label={Text(stringResource(R.string.choose_page,p.position+1))})}}}
                    if(page!=null)item {
                        ToolPagePreview(store,page,recipe,isDrawing,tool==ScanTool.SMART_ERASE,markers,tool==ScanTool.COUNT,onStroke={stroke ->change(recipe.copy(decorations=recipe.decorations.copy(signature=recipe.decorations.signature+stroke)))},onErase={r ->change(recipe.copy(decorations=recipe.decorations.copy(erasures=recipe.decorations.erasures+r)))},onMarker={point ->
                            val near=markers.indexOfFirst{it.distance(point)<.04f};markers=if(near>=0)markers.filterIndexed{i,_->i!=near} else (markers+point).take(1000)
                        })
                    }
                }
                if(isDrawing)item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={change(recipe.copy(decorations=if(tool==ScanTool.SIGNATURE)recipe.decorations.copy(signature=recipe.decorations.signature.dropLast(1)) else recipe.decorations.copy(erasures=recipe.decorations.erasures.dropLast(1))))},enabled=!busy){Text(stringResource(R.string.undo_stroke))}
                    TextButton(onClick={change(recipe.copy(decorations=if(tool==ScanTool.SIGNATURE)recipe.decorations.copy(signature=emptyList()) else recipe.decorations.copy(erasures=emptyList())))},enabled=!busy){Text(stringResource(R.string.reset_tool))}
                }}
                if(tool in listOf(ScanTool.WATERMARK,ScanTool.TIMESTAMP,ScanTool.EXTRACT,ScanTool.REORDER))item {
                    OutlinedTextField(field,{field=it.take(4000)},label={Text(stringResource(if(tool==ScanTool.WATERMARK)R.string.watermark_text else if(tool==ScanTool.TIMESTAMP)R.string.tool_timestamp else R.string.page_range))},modifier=Modifier.fillMaxWidth().testTag("tool-input"))
                    if(tool==ScanTool.REORDER)Text(stringResource(R.string.reorder_hint),style=MaterialTheme.typography.bodySmall)
                }
                if(tool==ScanTool.LOCK)item {Column {
                    OutlinedTextField(password,{password=it.take(128)},visualTransformation=PasswordVisualTransformation(),label={Text(stringResource(R.string.password))},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(repeatedPassword,{repeatedPassword=it.take(128)},visualTransformation=PasswordVisualTransformation(),label={Text(stringResource(R.string.password_confirm))},modifier=Modifier.fillMaxWidth())
                }}
                if(tool in listOf(ScanTool.WORD,ScanTool.EXCEL)||isAi)item {
                    OutlinedTextField(text,{text=it.take(100000);textEdited=true},label={Text(stringResource(R.string.edit_text))},minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth().testTag("tool-text"))
                    TextButton(onClick={bookId?.let(onOcr)},enabled=!busy&&bookId!=null){Text(stringResource(R.string.ocr))}
                }
                if(tool in listOf(ScanTool.REMOVE_MARKS,ScanTool.RESTORE_PHOTO))item {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(if(tool==ScanTool.REMOVE_MARKS)recipe.decorations.removeColorMarks else recipe.decorations.restorePhoto,{checked->
                            change(recipe.copy(decorations=if(tool==ScanTool.REMOVE_MARKS)recipe.decorations.copy(removeColorMarks=checked) else recipe.decorations.copy(restorePhoto=checked)))
                        });Text(stringResource(tool.label))
                    }
                }
                if(tool==ScanTool.COUNT)item {
                    Text(stringResource(R.string.count_result,markers.size),modifier=Modifier.testTag("count-result"))
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(darkObjects,{darkObjects=it});Text(stringResource(R.string.count_dark))}
                    Text(stringResource(R.string.count_sensitivity),style=MaterialTheme.typography.bodySmall);Slider(minimum,{minimum=it},valueRange=.0002f.. .03f)
                }
                if(isAi)item {
                    if(tool==ScanTool.TRANSLATE)OutlinedTextField(targetLanguage,{targetLanguage=it.take(100)},label={Text(stringResource(R.string.ai_target))},modifier=Modifier.fillMaxWidth())
                    else OutlinedTextField(field,{field=it.take(4000)},label={Text(stringResource(R.string.ai_question))},modifier=Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ai_review),Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={aiSettings=true}){Text(stringResource(R.string.ai_settings))}
                    Button(onClick={work {
                        val image=if(tool==ScanTool.FORMULA)withContext(Dispatchers.IO){val p=requireNotNull(page);val bitmap=ImagePipeline.render(store.source(p),recipe,1800);try{val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}finally{bitmap.recycle()}} else null
                        val prompt=when(tool){ScanTool.TRANSLATE->"Translate the supplied OCR text to $targetLanguage. Preserve numbers and structure. Return only the translation; mark uncertain source text.";ScanTool.FORMULA->"Transcribe the formula in the supplied image into LaTeX. Explain uncertain symbols. User instructions: $field";else->"Help solve the user's problem. Show reasoning steps suitable for study, check arithmetic, and identify uncertain OCR. User instructions: $field"}
                        result=ai.send(prompt,text,image)
                    }},enabled=!busy&&ai.configured,modifier=Modifier.testTag("ai-send")){Text(stringResource(R.string.ai_send))}
                }
                if(!isAi)item {Button(onClick=::runTool,enabled=!busy&&(page!=null||tool==ScanTool.MERGE),modifier=Modifier.fillMaxWidth().testTag("run-tool")){Text(stringResource(R.string.run_tool))}}
                if(tool in listOf(ScanTool.ID_PHOTO,ScanTool.SLIDES,ScanTool.WHITEBOARD,ScanTool.TIMESTAMP,ScanTool.WATERMARK,ScanTool.REMOVE_MARKS,ScanTool.RESTORE_PHOTO))item {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick=::saveRecipe,enabled=!busy&&page!=null,modifier=Modifier.testTag("save-tool-edit")){Text(stringResource(R.string.save))}
                        OutlinedButton(onClick={work{val p=requireNotNull(page);deliver(DocumentOutput(context,store).image(p,recipe))}},enabled=!busy&&page!=null){Text(stringResource(R.string.save_jpeg))} }
                    TextButton(onClick={change(page?.edit()?:EditRecipe())},enabled=!busy){Text(stringResource(R.string.reset_tool))}
                }
                if(result.isNotEmpty())item {SelectionContainerText(result);TextButton(onClick={val clip=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clip.setPrimaryClip(ClipData.newPlainText("DocScan",result))}){Text(stringResource(R.string.copy_result))}}
                if(page?.pdfSource!=null)item {TextButton(onClick={work {val original=requireNotNull(store.originalPdf(requireNotNull(page)));val copy=File(context.cacheDir,"original-${System.nanoTime()}.pdf");withContext(Dispatchers.IO){original.copyTo(copy)};deliver(copy)}}){Text(stringResource(R.string.pdf_original))}}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {TextButton(onScan,enabled=!busy){Text(stringResource(R.string.capture_new))};TextButton(onImport,enabled=!busy){Text(stringResource(R.string.import_images))}}}
            }
        }
    }
}

@Composable private fun SelectionContainerText(text:String){androidx.compose.foundation.text.selection.SelectionContainer{Text(text)}}

@Composable
private fun ToolPagePreview(store:ScanStore,page:Sheet,recipe:EditRecipe,drawing:Boolean,erase:Boolean,markers:List<Vertex>,counting:Boolean,onStroke:(InkStroke)->Unit,onErase:(EraseRegion)->Unit,onMarker:(Vertex)->Unit) {
    var points by remember(page.id){mutableStateOf(listOf<Vertex>())}
    val latestStroke by rememberUpdatedState(onStroke);val latestErase by rememberUpdatedState(onErase);val latestMarker by rememberUpdatedState(onMarker)
    val dimensions=CropGeometry.dimensions(recipe.frame,page.width,page.height,recipe.outputAspect).rotated(recipe.turns)
    Box(Modifier.fillMaxWidth().height(300.dp).background(Color(0xFFEEEEEE)).testTag("tool-preview")) {
        PagePicture(store,page,Modifier.fillMaxSize(),recipe,edge=1200)
        Canvas(Modifier.fillMaxSize().testTag("tool-drawing").pointerInput(page.id,drawing,erase,counting,dimensions.aspect) {
            fun point(offset:Offset):Vertex? {val rect=fitImageRect(Size(size.width.toFloat(),size.height.toFloat()),dimensions.aspect);if(!rect.contains(offset))return null;return Vertex(((offset.x-rect.left)/rect.width).coerceIn(0f,1f),((offset.y-rect.top)/rect.height).coerceIn(0f,1f))}
            if(drawing)detectDragGestures(onDragStart={points=listOfNotNull(point(it))},onDragCancel={points=emptyList()},onDragEnd={
                val p=points;if(p.size>1){if(erase){val a=p.first();val b=p.last();if(abs(a.x-b.x)>.002f&&abs(a.y-b.y)>.002f)latestErase(EraseRegion(min(a.x,b.x),min(a.y,b.y),max(a.x,b.x),max(a.y,b.y)))}else latestStroke(InkStroke(p))};points=emptyList()
            }){event,_->event.consume();point(event.position)?.let {if(points.size<500)points=points+it}}
            else if(counting)detectTapGestures {point(it)?.let(latestMarker)}
        }) {
            val rect=fitImageRect(size,dimensions.aspect);fun at(p:Vertex)=Offset(rect.left+p.x*rect.width,rect.top+p.y*rect.height)
            if(points.size>1){if(erase){val a=at(points.first());val b=at(points.last());drawRect(Color.Red.copy(alpha=.3f),Offset(min(a.x,b.x),min(a.y,b.y)),Size(abs(a.x-b.x),abs(a.y-b.y)))}else {val path=Path();points.forEachIndexed {i,p->val v=at(p);if(i==0)path.moveTo(v.x,v.y)else path.lineTo(v.x,v.y)};drawPath(path,Color.Blue,style=Stroke(2.dp.toPx()))}}
            markers.forEach {drawCircle(Color.Red,6.dp.toPx(),at(it));drawCircle(Color.White,7.dp.toPx(),at(it),style=Stroke(1.dp.toPx()))}
        }
    }
}

@Composable
private fun AiSettingsDialog(ai:AiService,onDismiss:()->Unit,snack:SnackbarHostState) {
    var endpoint by remember {mutableStateOf(ai.endpoint)};var model by remember {mutableStateOf(ai.model)};var key by remember {mutableStateOf("")};var error by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.ai_settings))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(stringResource(R.string.hint_ai),style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(endpoint,{endpoint=it},label={Text(stringResource(R.string.ai_endpoint))},singleLine=true)
        OutlinedTextField(model,{model=it},label={Text(stringResource(R.string.ai_model))},singleLine=true)
        OutlinedTextField(key,{key=it},label={Text(stringResource(R.string.ai_key))},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        if(error)Text(stringResource(R.string.tool_error),color=MaterialTheme.colorScheme.error)
        TextButton(onClick={runCatching{ai.forget()}.onSuccess{onDismiss()}.onFailure{error=true}}){Text(stringResource(R.string.ai_forget))}
    }},confirmButton={TextButton(onClick={runCatching{ai.save(endpoint,model,key)}.onSuccess{onDismiss()}.onFailure{error=true}}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}
