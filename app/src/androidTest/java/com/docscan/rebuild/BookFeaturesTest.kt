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
    @Test fun hidesPersonalDetailsFromThePageAndFromTextRecognitionAndDrawsShapes():Unit=runBlocking {
        // 1.3.2: hiding (black box, mosaic), shapes, and personal details found in the recognised text.
        ImagePipeline.ensureAvailable()
        val name="hide-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(context,ScanDatabase::class.java,name).build();val store=ScanStore(context,db);val book=store.create("Hide")
        val file=fixture("hide",1800,1200) { canvas ->
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=80f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
            canvas.drawText("ID 123456782",150f,250f,paint)
            canvas.drawText("Phone 052-1234567",150f,450f,paint)
            canvas.drawText("Mail dana@example.com",150f,650f,paint)
            canvas.drawText("Total 1250 in 2026",150f,850f,paint)
        }
        fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
        fun lum(p:Int)=(Color.red(p)*299+Color.green(p)*587+Color.blue(p)*114)/1000
        try {
            val sheet=store.captured(book.id,file)
            // The details are found in the page's own recognised text; the amount and the year are not details.
            val text=OfflineOcr(store).layout(sheet,sheet.edit(),"eng")
            val found=SensitiveText.find(text.words.map{TextBox(it.text,it.left.toFloat()/text.width,it.top.toFloat()/text.height,it.right.toFloat()/text.width,it.bottom.toFloat()/text.height)})
            android.util.Log.i("DocScanHideTest","found ${found.map{it.kind to it.text}}")
            assertEquals(found.map{it.kind}.toString(),setOf(SensitiveText.Kind.ID,SensitiveText.Kind.PHONE,SensitiveText.Kind.EMAIL),found.map{it.kind}.toSet())
            assertTrue(found.none{it.top>.6f})
            // Hidden with black boxes, even under Magic Color, and never read again.
            val hidden=sheet.edit().copy(treatment=Treatment.NATIVE_MAGIC_COLOR,hides=found.map{it.hide()})
            store.save(sheet,hidden)
            val page=ImagePipeline.render(store.source(sheet),store.dao.page(sheet.id)!!.edit(),1200)
            try {
                val px=pixels(page)
                for(f in found) {
                    val cx=((f.left+f.right)/2*page.width).toInt();val cy=((f.top+f.bottom)/2*page.height).toInt()
                    assertEquals("black over ${f.text}",0,lum(px[cy*page.width+cx]))
                }
            } finally { page.recycle() }
            val read=OfflineOcr(store).recognize(store.dao.page(sheet.id)!!,"eng")
            android.util.Log.i("DocScanHideTest","read after hiding: $read")
            assertTrue(read,read.contains("Total"))
            listOf("123456782","1234567","dana","example").forEach{assertFalse("$it still readable in: $read",read.contains(it))}
            // Kept with the page, also in what text recognition reads (stored rounded to 4 decimals).
            val kept=store.dao.page(sheet.id)!!.edit().withoutMarks().hides
            assertEquals(hidden.hides.size,kept.size)
            hidden.hides.zip(kept).forEach{(x,y)->assertTrue(abs(x.a.x-y.a.x)<1e-3f&&abs(x.b.y-y.b.y)<1e-3f&&x.kind==y.kind)}
        } finally { store.dao.allPages().forEach{store.source(it).delete()};file.delete();db.close();context.deleteDatabase(name) }
        // Mosaic: square tiles of one colour each, with the print's fine detail gone; shapes are drawn over the page.
        val input=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
        Canvas(input).apply{drawColor(Color.WHITE);drawText("Secret 123456782",40f,200f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=48f})}
        try {
            val plain=ImagePipeline.transform(input,EditRecipe())
            val mosaic=ImagePipeline.transform(input,EditRecipe(hides=listOf(HideMark(HideKind.MOSAIC,Vertex(0f,.3f),Vertex(1f,.6f)))))
            val drawn=ImagePipeline.transform(input,EditRecipe(shapes=listOf(ShapeMark(ShapeKind.RECT,Vertex(.1f,.1f),Vertex(.9f,.9f),0xD32F2F,.01f),ShapeMark(ShapeKind.ARROW,Vertex(.2f,.8f),Vertex(.5f,.5f),0x1E88E5,.01f))))
            try {
                val a=pixels(plain);val m=pixels(mosaic);val d=pixels(drawn)
                fun detail(p:IntArray):Long { var s=0L; for(y in 130 until 230) for(x in 40 until 560) s+=abs(lum(p[y*600+x])-lum(p[y*600+x+1])); return s }
                assertTrue("print under the mosaic: ${detail(a)} -> ${detail(m)}",detail(m)<detail(a)/5)
                // 10-pixel tiles (2.5% of the short side, 400): neighbours inside a tile are equal.
                assertEquals(m[150*600+16],m[150*600+17])
                assertTrue("outside the mosaic nothing changes",(0 until 600*110).all{a[it]==m[it]})
                val border=d[40*600+300];val arrow=d[(400*.65f).toInt()*600+(600*.35f).toInt()]
                assertTrue("red rectangle edge: ${Integer.toHexString(border)}",Color.red(border)>180&&Color.green(border)<90)
                assertTrue("blue arrow: ${Integer.toHexString(arrow)}",Color.blue(arrow)>180&&Color.red(arrow)<90)
                assertEquals("away from the shapes the page stays",a[200*600+550],d[200*600+550])
                val shaped=EditRecipe(shapes=listOf(ShapeMark(ShapeKind.OVAL,Vertex(.2f,.2f),Vertex(.4f,.5f))),hides=listOf(HideMark(HideKind.BLACK,Vertex(.1f,.1f),Vertex(.2f,.2f))))
                assertEquals(shaped,RecipeCodec.decode(RecipeCodec.encode(shaped)))
                assertTrue(shaped.withoutMarks().shapes.isEmpty()&&shaped.withoutMarks().hides.size==1&&shaped.marked)
            } finally { plain.recycle();mosaic.recycle();drawn.recycle() }
        } finally { input.recycle() }
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
        val spread=Frame(listOf(Vertex(.1f,.04f),Vertex(.8f,.03f),Vertex(.88f,.84f),Vertex(.02f,.85f)))
        val scenes=listOf(cover to fixture("cover-edge",w,h) { c ->
            c.drawColor(Color.rgb(215,222,225));c.poly(cover.points,Color.rgb(36,42,62));c.box(.55f,.05f,.85f,.85f,Color.rgb(42,50,72))
            for(x in listOf(.5f,.88f))c.box(x,.03f,x+.006f,.88f,Color.rgb(92,172,205))
            c.box(.12f,.12f,.6f,.3f,Color.rgb(48,60,88));c.printLines(.16f,.16f,.55f,.27f,Color.rgb(100,180,210))
        },page to fixture("open-page",w,h) { c ->
            c.drawColor(Color.rgb(55,60,70))
            c.poly(listOf(Vertex(.82f,.04f),Vertex(1f,.02f),Vertex(1f,.9f),Vertex(.81f,.88f)),Color.rgb(215,210,200));c.poly(page.points,Color.rgb(235,232,225))
            c.poly(listOf(Vertex(.79f,.06f),Vertex(.82f,.04f),Vertex(.81f,.88f),Vertex(.79f,.86f)),Color.rgb(120,115,105))
            c.printLines(.17f,.14f,.74f,.8f,Color.rgb(40,40,40));c.printLines(.85f,.1f,.98f,.8f,Color.rgb(40,40,40))
        },spread to fixture("facing-page",w,h) { c ->
            // Owner's 0.3.0 photo: the facing page runs off the frame, so the bright area reaches the frame edge.
            c.drawColor(Color.rgb(60,100,150))
            c.poly(listOf(Vertex(.08f,.03f),Vertex(1f,.01f),Vertex(1f,.87f),Vertex(0f,.87f)),Color.rgb(30,30,120))
            c.poly(listOf(Vertex(.8f,.03f),Vertex(1f,.02f),Vertex(1f,.85f),Vertex(.88f,.84f)),Color.rgb(220,228,230));c.poly(spread.points,Color.rgb(228,234,236))
            c.drawLine(.8f*w,.03f*h,.88f*w,.84f*h,Paint().apply {color=Color.rgb(160,170,175);strokeWidth=5f})
            var y=.1f;while(y<.78f){c.box(.16f,y,.74f,y+.008f,Color.rgb(40,40,40));y+=.07f}
        })
        for((expected,file) in scenes) try {val frame=requireNotNull(ImagePipeline.detect(file)) {"No boundary in ${file.name}"};assertTrue("${file.name}: $frame",frame.motion(expected)<.05f)} finally {file.delete()}
    }

    @Test fun detectsAngledCoversOpenRightPagesAndPagesCutByTheFrame() {
        // Owner's 1.2.0 report: a cover seen from an angle on a table of nearly its colour (with a cloth spine), the
        // right page of an open book whose facing page runs off the frame (page edges on its other side), and a
        // page whose corner lies outside the frame. Mirrors the desktop measurement in docs/DETECTION_1.2.1_HE.md.
        val w=900;val h=1200
        fun Canvas.poly(points:List<Pair<Float,Float>>,colour:Int)=drawPath(Path().apply {points.forEachIndexed {i,(x,y)->if(i==0)moveTo(Math.round(x*w).toFloat(),Math.round(y*h).toFloat())else lineTo(Math.round(x*w).toFloat(),Math.round(y*h).toFloat())};close()},Paint().apply {color=colour})
        fun Canvas.box(l:Float,t:Float,r:Float,b:Float,colour:Int)=drawRect(Math.round(l*w).toFloat(),Math.round(t*h).toFloat(),Math.round(r*w).toFloat(),Math.round(b*h).toFloat(),Paint().apply {color=colour})
        fun Canvas.rows(l:Float,t:Float,r:Float,b:Float,colour:Int,gap:Float=.025f,thick:Float=.006f){var y=t;while(y<b){box(l,y,r,y+thick,colour);y+=gap}}
        fun frame(vararg p:Pair<Float,Float>)=Frame(p.map {Vertex(it.first.coerceIn(0f,1f),it.second.coerceIn(0f,1f))})
        val board=listOf(.28f to .2f,.7f to .21f,.82f to .84f,.1f to .82f)
        val wholeBook=frame(.28f to .2f,.77f to .215f,.9f to .845f,.1f to .82f)
        val page=listOf(.2f to .05f,.8f to .08f,.82f to .9f,.18f to .93f)
        val cut=listOf(-.08f to .12f,.86f to .05f,.92f to .9f,.04f to .95f)
        val cover=fixture("angled-cover",w,h) { c ->
            c.drawColor(Color.rgb(196,198,192));c.poly(listOf(0f to .3f,1f to .26f,1f to 1f,0f to 1f),Color.rgb(92,64,46))
            c.poly(listOf(.1f to .82f,.9f to .845f,.9f to .875f,.1f to .85f),Color.rgb(222,216,200))
            c.poly(board,Color.rgb(58,60,56));c.poly(listOf(.7f to .21f,.77f to .215f,.9f to .845f,.82f to .84f),Color.rgb(104,108,114))
            c.poly(listOf(.38f to .3f,.62f to .3f,.66f to .42f,.36f to .42f),Color.rgb(176,146,88));c.rows(.3f,.5f,.68f,.7f,Color.rgb(84,86,80),.04f,.01f)
        }
        val open=fixture("open-right-page",w,h) { c ->
            c.drawColor(Color.rgb(52,50,54));c.poly(listOf(0f to .04f,.2f to .05f,.18f to .93f,0f to .92f),Color.rgb(226,222,214))
            c.poly(page,Color.rgb(236,232,224));c.poly(listOf(.8f to .08f,.88f to .1f,.9f to .88f,.82f to .9f),Color.rgb(232,205,205))
            for(k in 0 until 8){val x=.8f+k*.01f;c.poly(listOf(x to .08f+k*.0025f,x+.004f to .081f+k*.0025f,x+.024f to .9f-k*.0025f,x+.02f to .9f-k*.0025f),Color.rgb(200,150,160))}
            c.poly(listOf(.185f to .05f,.215f to .05f,.195f to .93f,.165f to .93f),Color.rgb(150,146,140))
            c.rows(.26f,.14f,.76f,.85f,Color.rgb(40,40,40));c.rows(.02f,.12f,.15f,.85f,Color.rgb(40,40,40))
        }
        val corner=fixture("corner-outside",w,h) { c -> c.drawColor(Color.rgb(150,110,70));c.poly(cut,Color.rgb(238,236,230));c.rows(.12f,.2f,.8f,.82f,Color.rgb(50,50,50)) }
        try {
            val found=requireNotNull(ImagePipeline.detect(cover)) {"Cover not found"}
            // The leather board, as CamScanner shows it; the board with its cloth spine is also a correct outline.
            assertTrue("Cover: $found",min(found.motion(frame(*board.toTypedArray())),found.motion(wholeBook))<.05f)
            val right=requireNotNull(ImagePipeline.detect(open)) {"Page not found"}
            assertTrue("Right page: $right",right.motion(frame(*page.toTypedArray()))<.05f)
            val clipped=requireNotNull(ImagePipeline.detect(corner)) {"Cut page not found"}
            assertTrue("Cut page: $clipped",clipped.motion(frame(*cut.toTypedArray()))<.06f)
        } finally { cover.delete();open.delete();corner.delete() }
    }
    @Test fun findsTheLeftPageOfAnOpenBookInsideItsPageEdgesOnALightChair() {
        // Owner's 1.2.2 report (with a CamScanner screen recording): the left page of an open book on a light chair.
        // Its top bows down, the chair's back rest draws a sharp line above it, the book block shows layered page
        // edges and a dark cover line, and the page shades into the spine. CamScanner outlines the page alone.
        // Mirrors the desktop measurement in docs/DETECTION_1.2.3_HE.md.
        val w=900;val h=1200
        fun path(points:List<Pair<Float,Float>>)=Path().apply {points.forEachIndexed {i,(x,y)->if(i==0)moveTo(Math.round(x*w).toFloat(),Math.round(y*h).toFloat())else lineTo(Math.round(x*w).toFloat(),Math.round(y*h).toFloat())};close()}
        fun Canvas.poly(points:List<Pair<Float,Float>>,colour:Int)=drawPath(path(points),Paint().apply {color=colour})
        fun Canvas.shade(points:List<Pair<Float,Float>>,x0:Float,x1:Float,from:Int,to:Int)=drawPath(path(points),Paint().apply {shader=LinearGradient(x0*w,0f,x1*w,0f,from,to,Shader.TileMode.CLAMP)})
        fun Canvas.rows(l:Float,t:Float,r:Float,b:Float,colour:Int){var y=t;while(y<b){drawRect(Math.round(l*w).toFloat(),Math.round(y*h).toFloat(),Math.round(r*w).toFloat(),Math.round((y+.006f)*h).toFloat(),Paint().apply {color=colour});y+=.025f}}
        val page=listOf(.14f to .16f,.68f to .11f,.68f to .885f,.09f to .895f)
        val orange=Color.rgb(226,160,96)
        val file=fixture("open-book-chair",w,h) { c ->
            c.drawColor(Color.rgb(204,200,190));c.poly(listOf(0f to 0f,1f to 0f,1f to .06f,0f to .1f),Color.rgb(214,212,206))
            c.poly(listOf(.05f to .175f,.69f to .12f,.72f to .91f,.01f to .93f),Color.rgb(44,42,46))
            c.poly(listOf(.07f to .17f,.67f to .11f,.7f to .895f,.03f to .915f),Color.rgb(222,212,192))
            for(k in 0 until 4){val x=.078f+k*.016f;c.poly(listOf(x to .169f-k*.0012f,x+.007f to .169f-k*.0012f,x-.033f to .91f-k*.003f,x-.04f to .91f-k*.003f),orange)}
            for(k in 0 until 3){val y=.899f+k*.006f;c.poly(listOf(.06f to y,.69f to y-.012f,.69f to y-.009f,.06f to y+.003f),orange)}
            // The page's top bows down between its corner and the spine.
            val top=(0..12).map {val t=it/12f;.14f+.54f*t to .16f-.05f*t+.03f*(1-(2*t-1)*(2*t-1))}
            c.poly(top+listOf(.68f to .885f,.09f to .895f),Color.rgb(240,234,218))
            c.shade(listOf(.56f to .1215f,.68f to .11f,.68f to .885f,.56f to .8867f),.6f,.68f,Color.rgb(240,234,218),Color.rgb(150,144,134))
            c.poly(listOf(.68f to .11f,1f to .125f,1f to .9f,.68f to .885f),Color.rgb(232,226,210))
            c.shade(listOf(.68f to .11f,.78f to .115f,.78f to .89f,.68f to .885f),.68f,.71f,Color.rgb(150,144,134),Color.rgb(232,226,210))
            c.rows(.18f,.22f,.56f,.85f,Color.rgb(40,40,40));c.rows(.78f,.2f,.97f,.85f,Color.rgb(40,40,40))
        }
        try {
            val found=requireNotNull(ImagePipeline.detect(file)) {"Page not found"}
            assertTrue("Left page: $found",found.motion(Frame(page.map {Vertex(it.first,it.second)}))<.05f)
        } finally { file.delete() }
    }
    @Test fun findsAPictureCoverPrintedToItsEdgesOnAShadedCloth() {
        // Owner's 1.2.6 report (with CamScanner screen recordings): a magazine cover, a picture printed to its edges
        // with no margin, on a grey cloth lit unevenly with a shadow at one corner. DocScan outlined a house inside the
        // picture; CamScanner the whole cover. Mirrors docs/DETECTION_1.2.8_HE.md.
        val w=900;val h=1200
        val quad=listOf(.27f to .25f,.71f to .26f,.75f to .77f,.24f to .78f)
        fun at(u:Float,v:Float):Pair<Float,Float> {
            val tx=quad[0].first+(quad[1].first-quad[0].first)*u;val ty=quad[0].second+(quad[1].second-quad[0].second)*u
            val bx=quad[3].first+(quad[2].first-quad[3].first)*u;val by=quad[3].second+(quad[2].second-quad[3].second)*u
            return (tx+(bx-tx)*v)*w to (ty+(by-ty)*v)*h
        }
        // (u0, v0, u1, v1, colour) on the cover: a sky, towers, a house running to each side edge, a stone house, a street.
        val shapes=listOf(
            listOf(0f,0f,1f,.32f) to Color.rgb(196,218,232),listOf(.2f,.05f,.8f,.1f) to Color.rgb(34,30,48),
            listOf(0f,.32f,1f,1f) to Color.rgb(176,132,82),listOf(.02f,.14f,.2f,.5f) to Color.rgb(226,222,210),
            listOf(.75f,.1f,.97f,.48f) to Color.rgb(208,202,196),listOf(.3f,.2f,.62f,.4f) to Color.rgb(150,160,170),
            listOf(0f,.45f,.32f,.85f) to Color.rgb(236,226,196),listOf(.34f,.42f,.74f,.9f) to Color.rgb(196,140,70),
            listOf(.4f,.5f,.68f,.62f) to Color.rgb(70,52,40),listOf(.44f,.7f,.64f,.9f) to Color.rgb(60,40,30),
            listOf(.76f,.55f,1f,.95f) to Color.rgb(150,70,40),listOf(0f,.9f,1f,1f) to Color.rgb(214,178,110))+
            (0 until 4).flatMap { k -> listOf(.05f to .17f,.78f to .94f,.33f to .59f).map { (u0,u1) -> listOf(u0,.18f+k*.07f,u1,.205f+k*.07f) to Color.rgb(40,50,60) } }
        val file=fixture("picture-cover-cloth",w,h) { c ->
            // Grey cloth with a fine weave, lit from the top right, a soft shadow at the bottom right.
            val pixels=IntArray(w*h)
            for(y in 0 until h) for(x in 0 until w) {
                val shadow=45*exp(-(((x-.82*w)/(.2*w)).pow(2)+((y-.95*h)/(.15*h)).pow(2)))
                val g=(150-40.0*y/h+18.0*x/w+10*sin((x+y)*.9)*sin((x-y)*.23)-shadow).coerceIn(0.0,255.0)
                pixels[y*w+x]=Color.rgb((g*1.02).coerceAtMost(255.0).toInt(),g.toInt(),g.toInt())
            }
            val cloth=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);c.drawBitmap(cloth,0f,0f,null);cloth.recycle()
            for((box,colour) in shapes) {
                val (u0,v0,u1,v1)=box
                val corners=listOf(at(u0,v0),at(u1,v0),at(u1,v1),at(u0,v1))
                c.drawPath(Path().apply {corners.forEachIndexed {i,(x,y)->if(i==0)moveTo(x,y)else lineTo(x,y)};close()},Paint().apply {color=colour;isAntiAlias=true})
            }
        }
        try {
            val found=requireNotNull(ImagePipeline.detect(file)) {"Cover not found"}
            assertTrue("Whole cover: $found",found.motion(Frame(quad.map {Vertex(it.first,it.second)}))<.04f)
        } finally { file.delete() }
    }
    @Test fun cropsABookPageAlongItsCurvedTopEdgeAndLeavesStraightPagesAlone() {
        // 1.3.0: a book page bows; cropped with straight sides, a wedge of the table shows above the page's middle.
        // Mirrors docs/RELEASE_1.3.0_HE.md and the desktop measurement on the owner's open-book photo.
        val w=1000;val h=1300
        val frame=Frame(listOf(Vertex(.15f,.12f),Vertex(.85f,.12f),Vertex(.85f,.9f),Vertex(.15f,.9f)))
        fun page(bow:Float)=fixture("bowed-page",w,h) { c ->
            c.drawColor(Color.rgb(116,112,110))
            val path=Path()
            for(i in 0..40){val u=i/40f;val x=(.15f+.7f*u)*w;val y=(.12f+bow*.78f*4*u*(1-u))*h;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
            path.lineTo(.85f*w,.9f*h);path.lineTo(.15f*w,.9f*h);path.close()
            c.drawPath(path,Paint().apply {color=Color.rgb(235,232,222);isAntiAlias=true})
            for(r in 0 until 16){val y=(.2f+r*.042f)*h;c.drawRect(.22f*w,y,.78f*w,y+9,Paint().apply {color=Color.rgb(36,38,40)})}
        }
        fun lum(c:Int)=(Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000
        fun topMiddle(b:Bitmap):Int {var sum=0;var n=0;for(y in 0 until (b.height*.015f).toInt().coerceAtLeast(1))for(x in b.width/3 until 2*b.width/3){sum+=lum(b.getPixel(x,y));n++};return sum/n}
        val bowed=page(.03f)
        try {
            val curves=requireNotNull(ImagePipeline.pageEdges(bowed,frame)) {"Curved top edge not found"}
            assertEquals(.03f,PageEdgeCurves.peak(curves.top),.007f)
            assertTrue("Bottom edge is straight: ${PageEdgeCurves.peak(curves.bottom)}",PageEdgeCurves.peak(curves.bottom)<.004f)
            val straight=ImagePipeline.render(bowed,EditRecipe(frame=frame));val curved=ImagePipeline.render(bowed,EditRecipe(frame=frame,edges=curves.values))
            try {
                assertTrue("Straight crop should show the table above the bow: ${topMiddle(straight)}",topMiddle(straight)<160)
                assertTrue("Curved crop should start at the paper: ${topMiddle(curved)}",topMiddle(curved)>200)
                assertEquals(straight.width,curved.width);assertEquals(straight.height,curved.height)
            } finally {straight.recycle();curved.recycle()}
            val saved=RecipeCodec.decode(RecipeCodec.encode(EditRecipe(frame=frame,edges=curves.values)))
            assertEquals(curves.values.size,saved.edges.size);assertNotNull(saved.edgeCurves)
        } finally {bowed.delete()}
        val flat=page(0f)
        try {assertNull("A straight page needs no curved crop",ImagePipeline.pageEdges(flat,frame))} finally {flat.delete()}
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
    @Test fun automaticStraighteningFollowsASharpCurlAtTheSpine() {
        // Owner's 1.2.3 photograph: next to the spine the last words of every line rise steeply. Text runs up to the
        // spine, which curls the last fifth of the page, more at the top. Mirrors docs/FLATTEN_1.2.4_HE.md.
        ImagePipeline.ensureAvailable()
        val w=1200;val h=1600
        val text="שורה של טקסט לבדיקת יישור הדף ליד השדרה 0123456789 abc def ghi jk"
        val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=36f}
        ink.textSize=min(38f,36f*(w-70)/ink.measureText(text));val step=ink.textSize*1.17f
        val flat=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {drawColor(Color.WHITE);var y=120f;while(y<h-80){drawText(text,30f,y,ink);y+=step}}
        fun gray(bitmap:Bitmap)=org.opencv.core.Mat().also {m->val rgba=org.opencv.core.Mat();org.opencv.android.Utils.bitmapToMat(bitmap,rgba);org.opencv.imgproc.Imgproc.cvtColor(rgba,m,org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY);rgba.release()}
        /** Mean over lines of the largest distance from the line's own straight fit, in pixels. */
        fun bend(bitmap:Bitmap):Double {
            val g=gray(bitmap);val lines=try{PageFlattener.lines(g)}finally{g.release()}
            return lines.map {line->
                val n=line.size;val mx=line.sumOf {it[0]}/n;val my=line.sumOf {it[1]}/n
                val slope=line.sumOf {(it[0]-mx)*(it[1]-my)}/line.sumOf {(it[0]-mx)*(it[0]-mx)}.coerceAtLeast(1e-9)
                line.map {(it[1]-my-slope*(it[0]-mx))*h}.let {r->r.max()-r.min()}
            }.average()
        }
        val bent=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val src=org.opencv.core.Mat();val dst=org.opencv.core.Mat();val mapX=org.opencv.core.Mat(h,w,org.opencv.core.CvType.CV_32FC1);val mapY=org.opencv.core.Mat(h,w,org.opencv.core.CvType.CV_32FC1)
        try {
            org.opencv.android.Utils.bitmapToMat(flat,src)
            val xs=FloatArray(w*h);val ys=FloatArray(w*h)
            for(y in 0 until h)for(x in 0 until w){
                val u=x/(w-1.0);val v=y/(h-1.0);val t=((u-.8)/.2).coerceIn(0.0,1.0);val d=.035*t*t*(1.2-v)
                xs[y*w+x]=x.toFloat();ys[y*w+x]=((v-d)*(h-1)).toFloat()
            }
            mapX.put(0,0,xs);mapY.put(0,0,ys)
            org.opencv.imgproc.Imgproc.remap(src,dst,mapX,mapY,org.opencv.imgproc.Imgproc.INTER_LINEAR,org.opencv.core.Core.BORDER_REPLICATE)
            org.opencv.android.Utils.matToBitmap(dst,bent)
        }finally{src.release();dst.release();mapX.release();mapY.release()}
        try {
            val bentGray=gray(bent)
            val estimate=try{PageFlattener.estimate(bentGray)}finally{bentGray.release()}
            val coefficients=requireNotNull(estimate.coefficients){"Curled page: $estimate"}.toList()
            assertEquals(2+PageFlattener.ACROSS*PageFlattener.DOWN,coefficients.size);assertTrue(Flattening.valid(coefficients))
            val straightened=ImagePipeline.transform(bent,EditRecipe(flatten=coefficients))
            try {
                assertEquals(w,straightened.width);assertEquals(h,straightened.height)
                val before=bend(bent);val after=bend(straightened)
                assertTrue("Curl at the spine straightened: $before px -> $after px",after<before*.5&&before>3)
                assertEquals(EditRecipe(flatten=coefficients),RecipeCodec.decode(RecipeCodec.encode(EditRecipe(flatten=coefficients))))
            }finally{straightened.recycle()}
            // A page straightened by an earlier version keeps its nine numbers and renders with the first model.
            val legacy=listOf(.001f,.0098f,.0012f,.0074f,-.0124f,-.0023f,.0157f,-.018f,-.0026f)
            assertEquals(EditRecipe(flatten=legacy),RecipeCodec.decode(RecipeCodec.encode(EditRecipe(flatten=legacy))))
            ImagePipeline.transform(bent,EditRecipe(flatten=legacy)).let {assertEquals(w,it.width);it.recycle()}
        }finally{flat.recycle();bent.recycle()}
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
                compose.waitUntil(15000){compose.onAllNodesWithTag("edit-page-${page.id}").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("edit-page-${page.id}").performClick()
                compose.waitUntil(15000){compose.onAllNodesWithTag("editor-toolbar").fetchSemanticsNodes().isNotEmpty()}
            }
            compose.onNode(document).performClick();openEditor()
            compose.onNodeWithTag("edit-crop").performClick()
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
            compose.onNodeWithTag("editor-toolbar").performScrollToNode(hasTestTag("edit-curve"))
            compose.onNodeWithTag("edit-curve").performClick()
            compose.onNodeWithTag("curve-top").assertIsDisplayed()
            compose.onNodeWithTag("curve-bottom").assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.reset_curve)).performClick()
            compose.onNodeWithTag("editor-tab-pages").performClick()
            compose.onNodeWithTag("edit-split").performClick()
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
