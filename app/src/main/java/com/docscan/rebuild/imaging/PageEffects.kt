package com.docscan.rebuild.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.docscan.rebuild.model.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.*

object PageEffects {
    fun apply(bitmap: Bitmap, edits: PageDecorations) {
        require(edits.valid())
        if(edits.erasures.isNotEmpty() || edits.removeColorMarks || edits.restorePhoto) {
            val rgba=Mat();val rgb=Mat();val mask=Mat.zeros(bitmap.height,bitmap.width,CvType.CV_8UC1);val out=Mat()
            try {
                Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
                if(edits.removeColorMarks) {
                    val hsv=Mat()
                    try { Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV);Core.inRange(hsv,Scalar(0.0,70.0,25.0),Scalar(180.0,255.0,255.0),mask) }
                    finally { hsv.release() }
                }
                edits.erasures.forEach { r -> Imgproc.rectangle(mask,Point(r.left*bitmap.width.toDouble(),r.top*bitmap.height.toDouble()),Point(r.right*bitmap.width.toDouble(),r.bottom*bitmap.height.toDouble()),Scalar(255.0),-1) }
                if(Core.countNonZero(mask)>0) { Photo.inpaint(rgb,mask,out,3.0,Photo.INPAINT_TELEA);out.copyTo(rgb) }
                if(edits.restorePhoto) {
                    // Conservative local contrast and sharpening. No generated faces or invented detail.
                    val lab=Mat();val channels=ArrayList<Mat>();val blurred=Mat();val clahe=Imgproc.createCLAHE(1.6,Size(8.0,8.0))
                    try {
                        Imgproc.cvtColor(rgb,lab,Imgproc.COLOR_RGB2Lab);Core.split(lab,channels);clahe.apply(channels[0],channels[0]);Core.merge(channels,lab);Imgproc.cvtColor(lab,rgb,Imgproc.COLOR_Lab2RGB)
                        Imgproc.GaussianBlur(rgb,blurred,Size(0.0,0.0),1.0);Core.addWeighted(rgb,1.15,blurred,-.15,0.0,rgb)
                    } finally { lab.release();channels.forEach { it.release() };blurred.release();clahe.collectGarbage() }
                }
                val converted=Mat();val alpha=Mat()
                try {Core.extractChannel(rgba,alpha,3);Imgproc.cvtColor(rgb,converted,Imgproc.COLOR_RGB2RGBA);Core.insertChannel(alpha,converted,3);Utils.matToBitmap(converted,bitmap)}
                finally{converted.release();alpha.release()}
            } finally { rgba.release();rgb.release();mask.release();out.release() }
        }
        val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(20,45,110);style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
        edits.signature.forEach { stroke ->
            val path=Path();stroke.points.forEachIndexed { i,p -> if(i==0)path.moveTo(p.x*bitmap.width,p.y*bitmap.height) else path.lineTo(p.x*bitmap.width,p.y*bitmap.height) }
            paint.strokeWidth=stroke.width*min(bitmap.width,bitmap.height);canvas.drawPath(path,paint)
        }
        fun text(value:String,y:Float,watermark:Boolean) {
            if(value.isBlank())return
            val tp=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=if(watermark)Color.argb(85,80,80,80) else Color.BLACK;textSize=bitmap.width*(if(watermark).06f else .026f);typeface=Typeface.create("sans-serif",Typeface.BOLD) }
            val layout=StaticLayout.Builder.obtain(value,0,value.length,tp,(bitmap.width*.9f).toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            canvas.save();canvas.translate(bitmap.width*.05f,(y-layout.height/2f).coerceIn(0f,(bitmap.height-layout.height).coerceAtLeast(0).toFloat()));layout.draw(canvas);canvas.restore()
        }
        text(edits.watermark,bitmap.height*.5f,true);text(edits.timestamp,bitmap.height*.96f,false)
    }

    /** Candidate blobs only. The caller shows every marker for manual correction. */
    fun countCandidates(bitmap: Bitmap, darkObjects: Boolean, minimumArea: Double): List<Vertex> {
        ImagePipeline.ensureAvailable();val rgba=Mat();val gray=Mat();val binary=Mat();val labels=Mat();val stats=Mat();val centers=Mat()
        try {
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray,binary,0.0,255.0,(if(darkObjects)Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY) or Imgproc.THRESH_OTSU)
            val n=Imgproc.connectedComponentsWithStats(binary,labels,stats,centers)
            return (1 until n).filter { val area=stats.get(it,Imgproc.CC_STAT_AREA)[0];area>=minimumArea*bitmap.width*bitmap.height && area<bitmap.width*bitmap.height*.2 }.take(1000).map { Vertex((centers.get(it,0)[0]/bitmap.width).toFloat(),(centers.get(it,1)[0]/bitmap.height).toFloat()) }
        } finally { listOf(rgba,gray,binary,labels,stats,centers).forEach { it.release() } }
    }
}
