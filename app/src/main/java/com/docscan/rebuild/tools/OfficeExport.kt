package com.docscan.rebuild.tools

import android.graphics.Bitmap
import com.docscan.rebuild.data.ScanStore
import com.docscan.rebuild.imaging.ImagePipeline
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small original OOXML writers. Text exports are explicitly distinct from layout reconstruction. */
class OfficeExport(private val store:ScanStore) {
    companion object {
        fun xml(text:String)=text.filter { it=='\n'||it=='\r'||it=='\t'||it.code>=32 }.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        fun column(index:Int):String { require(index in 0..16383);var n=index+1;var s="";while(n>0){n--;s=('A'.code+n%26).toChar()+s;n/=26};return s }
    }
    private val relNs="http://schemas.openxmlformats.org/package/2006/relationships"
    private val officeRel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun rels(body:String)="<Relationships xmlns=\"$relNs\">$body</Relationships>"
    private fun rel(id:String,type:String,target:String)="<Relationship Id=\"$id\" Type=\"$officeRel/$type\" Target=\"$target\"/>"
    private fun ZipOutputStream.part(name:String,text:String){putNextEntry(ZipEntry(name));write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+text).toByteArray());closeEntry()}
    private suspend fun archive(ext:String,write:suspend (ZipOutputStream)->Unit):File=withContext(Dispatchers.IO) {
        val file=File(File(store.context.cacheDir,"exports").apply{mkdirs()},"${UUID.randomUUID()}.$ext")
        try{ZipOutputStream(file.outputStream().buffered()).use {write(it)};file}catch(t:Throwable){file.delete();throw t}
    }
    suspend fun word(bookId:String,textOverride:String?=null):File {
        val pages=store.dao.snapshot(bookId);require(pages.isNotEmpty())
        val texts=textOverride?.let {listOf(it)}?:pages.map {requireNotNull(it.currentText()){"OCR required"}}
        return archive("docx") { zip ->
            zip.part("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
            zip.part("_rels/.rels",rels(rel("rId1","officeDocument","word/document.xml")))
            val body=texts.mapIndexed { index,text ->
                (if(index>0)"<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" else "")+text.lines().joinToString("") { line ->
                    val rtl=line.any { it in '\u0590'..'\u08ff' }
                    "<w:p><w:pPr>${if(rtl)"<w:bidi/><w:jc w:val=\"right\"/>" else ""}</w:pPr><w:r><w:rPr>${if(rtl)"<w:rtl/>" else ""}</w:rPr><w:t xml:space=\"preserve\">${xml(line)}</w:t></w:r></w:p>"
                }
            }.joinToString("")
            zip.part("word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>")
        }
    }
    suspend fun excel(bookId:String,textOverride:String?=null):File {
        val pages=store.dao.snapshot(bookId);require(pages.isNotEmpty());val texts=textOverride?.let {listOf(it)}?:pages.map {requireNotNull(it.currentText()){"OCR required"}}
        return archive("xlsx") { zip ->
            zip.part("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>${texts.indices.joinToString(""){"<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}}</Types>")
            zip.part("_rels/.rels",rels(rel("rId1","officeDocument","xl/workbook.xml")))
            zip.part("xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$officeRel\"><sheets>${texts.indices.joinToString(""){"<sheet name=\"Page ${it+1}\" sheetId=\"${it+1}\" r:id=\"rId${it+1}\"/>"}}</sheets></workbook>")
            zip.part("xl/_rels/workbook.xml.rels",rels(texts.indices.joinToString(""){rel("rId${it+1}","worksheet","worksheets/sheet${it+1}.xml")}))
            texts.forEachIndexed { i,text ->
                val lines=text.lines();require(lines.size<=1048576)
                val rows=lines.mapIndexed { y,line -> val cells=line.split('\t');require(cells.size<=16384)
                    "<row r=\"${y+1}\">"+cells.mapIndexed { x,cell ->require(cell.length<=32767);"<c r=\"${column(x)}${y+1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell)}</t></is></c>" }.joinToString("")+"</row>"
                }.joinToString("")
                zip.part("xl/worksheets/sheet${i+1}.xml","<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\" rightToLeft=\"${if(text.any{it in '\u0590'..'\u08ff'})1 else 0}\"/></sheetViews><sheetData>$rows</sheetData></worksheet>")
            }
        }
    }
    suspend fun powerpoint(bookId:String):File {
        val pages=store.dao.snapshot(bookId);require(pages.isNotEmpty())
        val p="http://schemas.openxmlformats.org/presentationml/2006/main";val a="http://schemas.openxmlformats.org/drawingml/2006/main"
        val group="<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
        val cmap="<p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>"
        return archive("pptx") { zip ->
            val contentTypes=mutableListOf("/ppt/presentation.xml" to "presentation.main","/ppt/slideMasters/slideMaster1.xml" to "slideMaster","/ppt/slideLayouts/slideLayout1.xml" to "slideLayout")+pages.indices.map {"/ppt/slides/slide${it+1}.xml" to "slide"}
            zip.part("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>${contentTypes.joinToString(""){(path,type)->"<Override PartName=\"$path\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.$type+xml\"/>"}}</Types>")
            zip.part("_rels/.rels",rels(rel("rId1","officeDocument","ppt/presentation.xml")))
            zip.part("ppt/presentation.xml","<p:presentation xmlns:p=\"$p\" xmlns:a=\"$a\" xmlns:r=\"$officeRel\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rMaster\"/></p:sldMasterIdLst><p:sldIdLst>${pages.indices.joinToString(""){"<p:sldId id=\"${256+it}\" r:id=\"rId${it+1}\"/>"}}</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
            zip.part("ppt/_rels/presentation.xml.rels",rels(rel("rMaster","slideMaster","slideMasters/slideMaster1.xml")+pages.indices.joinToString(""){rel("rId${it+1}","slide","slides/slide${it+1}.xml")}))
            zip.part("ppt/slideMasters/slideMaster1.xml","<p:sldMaster xmlns:p=\"$p\" xmlns:a=\"$a\" xmlns:r=\"$officeRel\"><p:cSld><p:spTree>$group</p:spTree></p:cSld>$cmap<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rLayout\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>")
            zip.part("ppt/slideMasters/_rels/slideMaster1.xml.rels",rels(rel("rLayout","slideLayout","../slideLayouts/slideLayout1.xml")))
            zip.part("ppt/slideLayouts/slideLayout1.xml","<p:sldLayout xmlns:p=\"$p\" xmlns:a=\"$a\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$group</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
            zip.part("ppt/slideLayouts/_rels/slideLayout1.xml.rels",rels(rel("rMaster","slideMaster","../slideMasters/slideMaster1.xml")))
            pages.forEachIndexed { i,page ->
                coroutineContext.ensureActive();val bitmap=ImagePipeline.render(store.source(page),page.edit())
                try {
                    val scale=minOf(12192000.0/bitmap.width,6858000.0/bitmap.height);val w=(bitmap.width*scale).toLong();val h=(bitmap.height*scale).toLong()
                    zip.part("ppt/slides/slide${i+1}.xml","<p:sld xmlns:p=\"$p\" xmlns:a=\"$a\" xmlns:r=\"$officeRel\"><p:cSld><p:spTree>$group<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Scan ${i+1}\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"rImage\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x=\"${(12192000-w)/2}\" y=\"${(6858000-h)/2}\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
                    zip.part("ppt/slides/_rels/slide${i+1}.xml.rels",rels(rel("rLayout","slideLayout","../slideLayouts/slideLayout1.xml")+rel("rImage","image","../media/image${i+1}.png")))
                    zip.putNextEntry(ZipEntry("ppt/media/image${i+1}.png"));check(bitmap.compress(Bitmap.CompressFormat.PNG,100,zip));zip.closeEntry()
                } finally {bitmap.recycle()}
            }
        }
    }
}
