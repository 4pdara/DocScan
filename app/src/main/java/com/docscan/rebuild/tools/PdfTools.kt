package com.docscan.rebuild.tools

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.print.*
import com.docscan.rebuild.data.*
import com.docscan.rebuild.imaging.ImagePipeline
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

class PdfTools(private val store:ScanStore) {
    private val context get()=store.context
    private fun target(extension:String)=File(File(context.cacheDir,"exports").apply { mkdirs() },"${UUID.randomUUID()}.$extension")

    /** The full input PDF is retained alongside derived 300 dpi PNG pages, including through backups. */
    suspend fun import(uri:Uri,dpi:Int=300):Notebook=withContext(Dispatchers.IO) {
        require(dpi in 72..600)
        val original=File(File(context.filesDir,"masters").apply { mkdirs() },"${UUID.randomUUID()}.pdf")
        var book:Notebook?=null;var committed=false
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> original.outputStream().use { out ->
                val buf=ByteArray(65536);var size=0L;while(true){val n=input.read(buf);if(n<0)break;size+=n;require(size<=100L*1024*1024);out.write(buf,0,n)}
            } }
            ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount in 1..500);book=store.create(context.getString(com.docscan.rebuild.R.string.imported_pdf_title))
                for(i in 0 until renderer.pageCount) {
                    coroutineContext.ensureActive()
                    renderer.openPage(i).use { page ->
                        val width=(page.width*dpi.toDouble()/72).roundToInt().coerceAtLeast(1);val height=(page.height*dpi.toDouble()/72).roundToInt().coerceAtLeast(1)
                        require(width.toLong()*height<=50_000_000L) { "Page is too large at the selected DPI" }
                        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val temp=target("png")
                        try {
                            bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                            val sheet=store.captured(requireNotNull(book).id,temp);store.dao.attachPdf(sheet.id,original.name)
                        } finally { bitmap.recycle();temp.delete() }
                    }
                }
            } }
            committed=true;requireNotNull(book)
        } finally {
            if(!committed) withContext(NonCancellable) {
                book?.let { b -> val pages=store.dao.snapshot(b.id);store.dao.deleteBook(b.id);pages.forEach { store.source(it).delete() } };original.delete()
            }
        }
    }

    suspend fun images(bookId:String):File=withContext(Dispatchers.IO) {
        val pages=store.dao.snapshot(bookId);require(pages.isNotEmpty());val out=target("zip")
        try { ZipOutputStream(out.outputStream().buffered()).use { zip -> pages.forEachIndexed { i,p ->
            coroutineContext.ensureActive();zip.putNextEntry(ZipEntry("page-${(i+1).toString().padStart(4,'0')}.png"))
            val bmp=ImagePipeline.render(store.source(p),p.edit());try { check(bmp.compress(Bitmap.CompressFormat.PNG,100,zip)) } finally { bmp.recycle() };zip.closeEntry()
        } };out } catch(t:Throwable){out.delete();throw t}
    }

    /** No implicit resampling. Large compositions fail explicitly before allocation. */
    suspend fun longImage(bookId:String):File=withContext(Dispatchers.IO) {
        val pages=store.dao.snapshot(bookId);require(pages.isNotEmpty())
        // Render once into temporary lossless pages so measured and final geometry agree.
        val stages=mutableListOf<File>();val sizes=mutableListOf<Pair<Int,Int>>()
        try {
            pages.forEach { p -> coroutineContext.ensureActive();val bitmap=ImagePipeline.render(store.source(p),p.edit());val stage=target("png");stages+=stage
                try { sizes+=bitmap.width to bitmap.height;stage.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() } }
            val width=sizes.maxOf { it.first };val height=sizes.sumOf { it.second.toLong() }
            require(height<=32766 && width.toLong()*height<=50_000_000) { "Long image exceeds device limits; export individual images" }
            val image=Bitmap.createBitmap(width,height.toInt(),Bitmap.Config.ARGB_8888);val out=target("png")
            try { val canvas=Canvas(image);canvas.drawColor(Color.WHITE);var y=0f
                stages.forEach { stage -> val b=requireNotNull(BitmapFactory.decodeFile(stage.path));try { canvas.drawBitmap(b,(width-b.width)/2f,y,null);y+=b.height } finally { b.recycle() } }
                out.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) };out
            } catch(t:Throwable){out.delete();throw t} finally { image.recycle() }
        } finally { stages.forEach { it.delete() } }
    }

    suspend fun identity(bookId:String):File=withContext(Dispatchers.IO) {
        val pages=store.dao.snapshot(bookId);require(pages.size==2) { "Select a document containing the two sides" }
        val out=target("pdf");val doc=PdfDocument()
        try {
            val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
            pages.forEachIndexed { i,p -> val bmp=ImagePipeline.render(store.source(p),p.edit());try {
                val w=243f;val h=w*bmp.height/bmp.width;require(h<350);val y=80f+i*360f
                page.canvas.drawBitmap(bmp,null,RectF((595-w)/2,y,(595+w)/2,y+h),Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {bmp.recycle()} }
            doc.finishPage(page);out.outputStream().use {doc.writeTo(it)};out
        } catch(t:Throwable){out.delete();throw t} finally {doc.close()}
    }

    suspend fun lock(pdf:File,password:String):File=withContext(Dispatchers.IO) {
        require(password.length in 6..128);PDFBoxResourceLoader.init(context);val out=target("pdf")
        try { PDDocument.load(pdf).use { doc ->
            val policy=StandardProtectionPolicy(UUID.randomUUID().toString(),password,AccessPermission()).apply { encryptionKeyLength=256;isPreferAES=true }
            doc.protect(policy);doc.save(out)
        };out } catch(t:Throwable){out.delete();throw t}
    }
}

/** Android print service receives an immutable generated PDF. Cancelled copies never report success. */
fun printPdf(context:Context,file:File,title:String,pageCount:Int) {
    val manager=context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    manager.print(title,object:PrintDocumentAdapter() {
        override fun onLayout(old:PrintAttributes?,new:PrintAttributes?,signal:CancellationSignal,callback:LayoutResultCallback,extras:Bundle?) {
            if(signal.isCanceled)callback.onLayoutCancelled() else callback.onLayoutFinished(PrintDocumentInfo.Builder(title).setPageCount(pageCount).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),true)
        }
        override fun onWrite(ranges:Array<out PageRange>,destination:ParcelFileDescriptor,signal:CancellationSignal,callback:WriteResultCallback) {
            Thread {
                try {
                    if(signal.isCanceled){callback.onWriteCancelled();return@Thread}
                    val indices=(0 until pageCount).filter {i->ranges.any {i in it.start..it.end} }
                    require(indices.isNotEmpty())
                    PDFBoxResourceLoader.init(context)
                    PDDocument.load(file).use { source -> PDDocument().use { selected ->
                        indices.forEach { i->if(signal.isCanceled){callback.onWriteCancelled();return@Thread};selected.importPage(source.getPage(i)) }
                        FileOutputStream(destination.fileDescriptor).use {selected.save(it)}
                    } }
                    if(signal.isCanceled)callback.onWriteCancelled() else {
                        val written=mutableListOf<PageRange>();var start=indices.first();var end=start
                        indices.drop(1).forEach {i->if(i==end+1)end=i else {written+=PageRange(start,end);start=i;end=i} };written+=PageRange(start,end)
                        callback.onWriteFinished(written.toTypedArray())
                    }
                } catch(_:Exception){callback.onWriteFailed(context.getString(com.docscan.rebuild.R.string.error_generic))}
            }.start()
        }
        override fun onFinish(){file.delete()}
    },null)
}
