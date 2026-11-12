package com.docscan.rebuild

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.docscan.rebuild.data.*
import com.docscan.rebuild.imaging.*
import com.docscan.rebuild.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlin.math.*

class BookFeaturesTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun fixture(name:String,width:Int=1600,height:Int=1000,draw:(Canvas)->Unit={}):File {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);draw(canvas)
        val file=File(context.cacheDir,"$name-${System.nanoTime()}.png")
        try {file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()};return file
    }
    @Test fun texturedCoversWithGlareAndBrokenCornersKeepTheirOuterBoundary() {
        val expected=Frame(listOf(Vertex(.15f,.12f),Vertex(.83f,.18f),Vertex(.89f,.88f),Vertex(.1f,.81f)))
        for(dark in listOf(true,false)) {
            val file=fixture("textured-cover",1000,1200) { canvas ->
                canvas.drawColor(if(dark)Color.rgb(210,198,179) else Color.rgb(66,68,77))
                val path=Path().apply {expected.points.forEachIndexed {i,p->if(i==0)moveTo(p.x*1000,p.y*1200)else lineTo(p.x*1000,p.y*1200)};close()}
                canvas.drawPath(path,Paint().apply {color=if(dark)Color.rgb(30,38,69) else Color.rgb(185,70,38)})
                canvas.save();canvas.clipPath(path)
                for(y in 230..900 step 45)canvas.drawLine(180f,y.toFloat(),800f,(y+50).toFloat(),Paint().apply {color=if(dark)Color.rgb(86,92,115) else Color.rgb(220,133,79);strokeWidth=4f})
                canvas.drawRect(260f,400f,680f,770f,Paint().apply {color=Color.rgb(238,216,164)})
                canvas.drawText("BOOK 123",280f,500f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=55f})
                canvas.drawOval(RectF(550f,250f,850f,560f),Paint(Paint.ANTI_ALIAS_FLAG).apply {shader=RadialGradient(700f,400f,160f,Color.argb(190,255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)})
                canvas.restore()
                // One corner is occluded by a patch with the table colour.
                canvas.drawCircle(150f,144f,15f,Paint().apply {color=if(dark)Color.rgb(210,198,179) else Color.rgb(66,68,77)})
            }
            try {val frame=requireNotNull(ImagePipeline.detect(file));assertTrue("Outer cover: $frame",frame.motion(expected)<.065f)}finally{file.delete()}
        }
    }
    @Test fun detectsCoversAtTheFrameEdgeAndThePageOfAnOpenBook() {
        // Scenes from owner reports: a textured cover filling the view up to the frame's top edge, and the
        // left page of an open book with its gutter and a second page running off the frame.
        val w=900;val h=1200
        fun Canvas.poly(points:List<Vertex>,colour:Int)=drawPath(Path().apply {points.forEachIndexed {i,p->if(i==0)moveTo(p.x*w,p.y*h)else lineTo(p.x*w,p.y*h)};close()},Paint().apply {color=colour})
        fun Canvas.box(l:Float,t:Float,r:Float,b:Float,colour:Int)=drawRect(l*w,t*h,r*w,b*h,Paint().apply {color=colour})
        fun Canvas.printLines(l:Float,t:Float,r:Float,b:Float,colour:Int){var y=t;while(y<b){box(l,y,r,y+.008f,colour);y+=.025f}}
        val cover=Frame(listOf(Vertex(.08f,0f),Vertex(.92f,0f),Vertex(.95f,.9f),Vertex(.06f,.92f)))
        val page=Frame(listOf(Vertex(.12f,.08f),Vertex(.8f,.06f),Vertex(.79f,.86f),Vertex(.08f,.84f)))
        val scenes=listOf(cover to fixture("cover-edge",w,h) { c ->
            c.drawColor(Color.rgb(215,222,225));c.poly(cover.points,Color.rgb(36,42,62));c.box(.55f,.05f,.85f,.85f,Color.rgb(42,50,72))
            for(x in listOf(.5f,.88f))c.box(x,.03f,x+.006f,.88f,Color.rgb(92,172,205))
            c.box(.12f,.12f,.6f,.3f,Color.rgb(48,60,88));c.printLines(.16f,.16f,.55f,.27f,Color.rgb(100,180,210))
        },page to fixture("open-page",w,h) { c ->
            c.drawColor(Color.rgb(55,60,70))
            c.poly(listOf(Vertex(.82f,.04f),Vertex(1f,.02f),Vertex(1f,.9f),Vertex(.81f,.88f)),Color.rgb(215,210,200));c.poly(page.points,Color.rgb(235,232,225))
            c.poly(listOf(Vertex(.79f,.06f),Vertex(.82f,.04f),Vertex(.81f,.88f),Vertex(.79f,.86f)),Color.rgb(120,115,105))
            c.printLines(.17f,.14f,.74f,.8f,Color.rgb(40,40,40));c.printLines(.85f,.1f,.98f,.8f,Color.rgb(40,40,40))
        })
        for((expected,file) in scenes) try {val frame=requireNotNull(ImagePipeline.detect(file)) {"No boundary in ${file.name}"};assertTrue("${file.name}: $frame",frame.motion(expected)<.05f)} finally {file.delete()}
    }

    @Test fun colourOnlyBoundariesRemainEligibleForSharpAutomaticCapture() {
        ImagePipeline.ensureAvailable()
        val bitmap=Bitmap.createBitmap(500,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        canvas.drawColor(Color.rgb(0,102,0));canvas.drawRect(70f,70f,430f,530f,Paint().apply{color=Color.rgb(200,0,0)})
        val rgba=org.opencv.core.Mat();val gray=org.opencv.core.Mat();val blurred=org.opencv.core.Mat();val blurredGray=org.opencv.core.Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(bitmap,rgba)
            org.opencv.imgproc.Imgproc.cvtColor(rgba,gray,org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            assertTrue(CaptureQuality.sharp(gray,rgba))
            org.opencv.imgproc.Imgproc.GaussianBlur(rgba,blurred,org.opencv.core.Size(0.0,0.0),10.0)
            org.opencv.imgproc.Imgproc.cvtColor(blurred,blurredGray,org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            assertFalse(CaptureQuality.sharp(blurredGray,blurred))
            val original=CaptureQuality.signature(rgba,Frame.FULL)
            canvas.drawColor(Color.rgb(200,0,0));canvas.drawRect(70f,70f,430f,530f,Paint().apply{color=Color.rgb(0,102,0)})
            org.opencv.android.Utils.bitmapToMat(bitmap,rgba)
            assertTrue(CaptureStability.contentChanged(original,CaptureQuality.signature(rgba,Frame.FULL)))
        }finally{bitmap.recycle();rgba.release();gray.release();blurred.release();blurredGray.release()}
    }
    @Test fun splitPreservesOriginalBytesPerspectiveAndHebrewPageOrder():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val store=ScanStore(context,db);val input=fixture("spread")
        try {
            val book=store.create("Book spread");val p=store.captured(book.id,input);val after=store.captured(book.id,input);val digest=hash(input)
            val recipe=EditRecipe(frame=Frame(listOf(Vertex(.1f,.1f),Vertex(.9f,.15f),Vertex(.85f,.9f),Vertex(.12f,.85f))),treatment=Treatment.GRAY)
            store.split(p,recipe,.43f,true);val pages=store.dao.snapshot(book.id)
            assertEquals(3,pages.size);assertEquals(listOf(0,1,2),pages.map {it.position});assertEquals(after.id,pages[2].id)
            val(left,right)=recipe.frame.split(.43f);assertEquals(right,pages[0].edit().frame);assertEquals(left,pages[1].edit().frame)
            assertEquals(pages[0].source,pages[1].source);assertEquals(digest,hash(store.source(pages[1])))
            assertEquals(1600,pages[0].width);assertEquals(1000,pages[0].height)
        } finally {store.dao.allPages().distinctBy {it.source}.forEach {store.source(it).delete()};input.delete();db.close()}
    }
    @Test fun fullBackupRestoresSourcesCurvesOcrTrashAndSharedSplitSources():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val store=ScanStore(context,db)
        val otherDb=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val restored=ScanStore(context,otherDb);val input=fixture("backup")
        try {
            val book=store.create("Hebrew book ספר");val p=store.captured(book.id,input);store.split(p,p.edit(),.5f,true)
            val first=store.dao.snapshot(book.id)[0];store.save(first,first.edit().copy(curvature=BookCurve(.05f,-.02f)))
            val current=store.dao.page(first.id)!!;assertEquals(1,store.dao.recognized(current.id,"שלום עולם 123",current.recipe))
            val trashed=store.create("Trash retained");store.trash(setOf(trashed.id))
            val output=ByteArrayOutputStream();LibraryBackup(store).export(output)
            val existing=restored.create("Existing library");assertEquals(2,LibraryBackup(restored).restore(ByteArrayInputStream(output.toByteArray())))
            assertNotNull(restored.dao.book(existing.id));val copy=restored.dao.allBooks().first {it.title==book.title};assertNotEquals(book.id,copy.id)
            val pages=restored.dao.snapshot(copy.id);assertEquals(2,pages.size);assertEquals(pages[0].source,pages[1].source)
            assertEquals(current.recipe,pages[0].recipe);assertEquals("שלום עולם 123",pages[0].ocrText);assertEquals(current.recipe,pages[0].ocrRecipe)
            pages.forEach {assertEquals(hash(input),hash(restored.source(it)))}
            assertNotNull(restored.dao.allBooks().first {it.title==trashed.title}.trashedAt)
        } finally {for(s in listOf(store,restored))s.dao.allPages().distinctBy {it.source}.forEach {s.source(it).delete()};input.delete();db.close();otherDb.close()}
    }
    @Test fun unsafeAndCorruptArchivesCannotChangeTheExistingLibrary():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build();val store=ScanStore(context,db);val book=store.create("Keep me");val input=fixture("safe");val page=store.captured(book.id,input)
        try {
            for(name in listOf("../escape.source","sources/../../escape.source","manifest.json")) {
                val out=ByteArrayOutputStream();ZipOutputStream(out).use {z->z.putNextEntry(ZipEntry(name));z.write("invalid".toByteArray());z.closeEntry()}
                try {LibraryBackup(store).restore(ByteArrayInputStream(out.toByteArray()));fail("Invalid archive accepted")}catch(_:Exception){}
                assertEquals(1,store.dao.allBooks().size);assertEquals(hash(input),hash(store.source(page)))
            }
            val original=ByteArrayOutputStream();LibraryBackup(store).export(original)
            val modified=ByteArrayOutputStream()
            ZipOutputStream(modified).use {output -> ZipInputStream(ByteArrayInputStream(original.toByteArray())).use {zip ->
                while(true){val entry=zip.nextEntry?:break;val bytes=zip.readBytes();if(entry.name.startsWith("sources/"))bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();output.putNextEntry(ZipEntry(entry.name));output.write(bytes);output.closeEntry()}
            } }
            try {LibraryBackup(store).restore(ByteArrayInputStream(modified.toByteArray()));fail("Changed original accepted")}catch(_:Exception){}
            assertEquals(1,store.dao.allBooks().size);assertEquals(hash(input),hash(store.source(page)))
        } finally {input.delete();store.source(page).delete();db.close()}
    }
    @Test fun dewarpStraightensCurvedLinesWithoutResizingOrChangingSource() {
        val curve=BookCurve(.07f,.035f)
        val bitmap=Bitmap.createBitmap(900,1200,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        for(base in listOf(.2f,.4f,.6f,.8f)) {
            val path=Path();for(x in 0..899){val y=curve.sourceY(x/899f,base)*1199;if(x==0)path.moveTo(0f,y)else path.lineTo(x.toFloat(),y)}
            canvas.drawPath(path,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=5f})
        }
        val before=IntArray(900*1200);bitmap.getPixels(before,0,900,0,0,900,1200)
        val result=ImagePipeline.transform(bitmap,EditRecipe(curvature=curve))
        try {
            assertEquals(900,result.width);assertEquals(1200,result.height)
            for(x in listOf(70,450,830))for(base in listOf(.2f,.4f,.6f,.8f))assertTrue(Color.red(result.getPixel(x,(base*1199).roundToInt()))<80)
            val after=IntArray(before.size);bitmap.getPixels(after,0,900,0,0,900,1200);assertArrayEquals(before,after)
            val encoded=RecipeCodec.encode(EditRecipe(curvature=curve));assertEquals(curve,RecipeCodec.decode(encoded).curvature)
            assertEquals(BookCurve(),RecipeCodec.decode("{}").curvature)
        }finally{bitmap.recycle();result.recycle()}
    }
    @Test fun offlineHebrewAndEnglishRecognitionSurvivesReopenAndEditInvalidatesIt():Unit=runBlocking {
        val name="ocr-persistence-${System.nanoTime()}.db"
        var db=Room.databaseBuilder(context,ScanDatabase::class.java,name).build();var store=ScanStore(context,db);val book=store.create("OCR")
        val files=mutableListOf<File>()
        try {
            for((lang,text,expected) in listOf(Triple("eng","BOOK SCANNING\nDOCUMENT 123456\nCLEAR PRINTED TEXT","DOCUMENT 123456"),Triple("heb","שלום עולם\nספר חדש\nשלום עולם","שלום עולם"))) {
                val file=fixture("ocr-$lang",1800,1000){canvas ->
                    val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=100f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
                    val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,1500).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(if(lang=="heb")TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR).build()
                    canvas.save();canvas.translate(150f,200f);layout.draw(canvas);canvas.restore()
                };files.add(file);val sheet=store.captured(book.id,file);val result=OfflineOcr(store).recognize(sheet,lang)
                assertTrue("OCR $lang: $result",result.contains(expected));assertEquals(result,store.dao.page(sheet.id)!!.ocrText);assertEquals(hash(file),hash(store.source(sheet)))
                db.close();db=Room.databaseBuilder(context,ScanDatabase::class.java,name).build();store=ScanStore(context,db)
                assertEquals(result,store.dao.page(sheet.id)!!.ocrText)
                store.save(sheet,sheet.edit().copy(brightness=2f));assertNull(store.dao.page(sheet.id)!!.ocrText)
            }
        }finally{files.forEach {it.delete()};store.dao.allPages().forEach {store.source(it).delete()};db.close();context.deleteDatabase(name)}
    }
    @Test fun cropHasEightHandlesAndDraggingASideMovesItsTwoCorners() {
        val store=(compose.activity.application as ScanApplication).store;val title="Side handles ${System.nanoTime()}";val input=fixture("handles")
        val page=runBlocking {val b=store.create(title);val p=store.captured(b.id,input);store.save(p,EditRecipe(frame=Frame(listOf(Vertex(.15f,.15f),Vertex(.85f,.15f),Vertex(.85f,.85f),Vertex(.15f,.85f)))));p}
        try {
            compose.onNodeWithTag("home-search").performTextInput(title)
            val document=hasText(title) and !hasSetTextAction()
            compose.waitUntil(15000){compose.onAllNodes(document).fetchSemanticsNodes().isNotEmpty()}
            fun openEditor() {
                val label=compose.activity.getString(R.string.edit)
                compose.waitUntil(15000){compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText(label).performClick()
            }
            compose.onNode(document).performClick();openEditor()
            compose.onNodeWithText(compose.activity.getString(R.string.crop)).performClick()
            for(i in 0..7)compose.onNodeWithTag("crop-handle-$i").assertIsDisplayed()
            compose.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                try { val directory=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()};File(directory,"side-crop-handles.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)} } finally{screenshot.recycle()}
            }
            android.util.Log.i("DocScanCropTest","side="+compose.onNodeWithTag("crop-handle-4").fetchSemanticsNode().boundsInRoot+" overlay="+compose.onNodeWithTag("crop-overlay").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("crop-handle-4").performTouchInput {swipe(center,center+Offset(0f,55f),500)}
            compose.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                try {val directory=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()};File(directory,"side-crop-after-drag.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screenshot.recycle()}
            }
            compose.onNodeWithTag("save-edit").performClick()
            try {compose.waitUntil(15000){runBlocking{store.dao.page(page.id)!!.edit().frame.points[0].y>.16f}}}
            catch(failure:Exception){android.util.Log.e("DocScanCropTest","Saved recipe="+runBlocking{store.dao.page(page.id)!!.recipe});throw failure}
            val points=runBlocking{store.dao.page(page.id)!!.edit().frame.points}
            assertEquals(points[0].y,points[1].y,.001f);assertEquals(.85f,points[2].y,.001f);assertEquals(.85f,points[3].y,.001f)
            assertEquals(hash(input),hash(store.source(page)))
            openEditor()
            compose.onNodeWithTag("page-tools").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.book_curve)).performClick()
            compose.onNodeWithTag("curve-top").assertIsDisplayed()
            compose.onNodeWithTag("curve-bottom").assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.reset_curve)).performClick()
            compose.onNodeWithTag("page-tools").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.split_book)).performClick()
            compose.onNodeWithTag("split-preview").assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.split_apply)).performClick()
            compose.waitUntil(15000){runBlocking{store.dao.snapshot(page.notebookId).size==2}}
            compose.waitUntil(15000){compose.onAllNodesWithTag("document-pages").fetchSemanticsNodes().isNotEmpty()}
            val split=runBlocking{store.dao.snapshot(page.notebookId)}
            assertTrue(split[0].edit().frame.points[0].x>.4f)
            assertEquals(hash(input),hash(store.source(split[1])))
        }finally{input.delete()}
    }
}
