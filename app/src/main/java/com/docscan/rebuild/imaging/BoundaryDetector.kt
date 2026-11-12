package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import com.docscan.rebuild.model.Vertex
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Shared camera/still detector. All coordinates are normalized to the unrotated input. */
object BoundaryDetector {
    fun detect(gray: Mat, rgba: Mat? = null): Frame? {
        val blurred = Mat(); val edges = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val work = Mat(); val mask = Mat(); val hierarchy = Mat(); val supported = Mat()
        val small = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val contours = mutableListOf<MatOfPoint>(); val candidates = mutableListOf<Frame>()
        try {
            fun edgeChannel(channel: Mat) {
                Imgproc.GaussianBlur(channel, blurred, Size(5.0, 5.0), 0.0)
                // Two thresholds retain weak cover boundaries without making text the only proposal.
                for (high in listOf(55.0, 130.0)) {
                    Imgproc.Canny(blurred, work, high * .35, high)
                    Core.bitwise_or(edges, work, edges)
                }
            }
            edgeChannel(gray)
            if (rgba != null) {
                val channel = Mat()
                try { for (i in 0..2) { Core.extractChannel(rgba, channel, i); edgeChannel(channel) } }
                finally { channel.release() }
            }
            Imgproc.dilate(edges, supported, small)
            val pixels = ByteArray(gray.rows() * gray.cols()); supported.get(0, 0, pixels)
            fun ordered(raw: List<Point>): Frame? {
                if (raw.size != 4) return null
                val cx = raw.map { it.x }.average(); val cy = raw.map { it.y }.average()
                val points = raw.sortedBy { atan2(it.y-cy, it.x-cx) }
                val start = points.indices.minBy { points[it].x + points[it].y }
                val frame = Frame((0..3).map { val p=points[(start+it)%4]; Vertex((p.x/(gray.cols()-1)).toFloat(),(p.y/(gray.rows()-1)).toFloat()) })
                return frame.takeIf { it.valid() && it.area() in .055f.. .96f && it.points.all { p -> p.x in .003f.. .997f && p.y in .003f.. .997f } }
            }
            fun collect(image: Mat, mode: Int) {
                val found=mutableListOf<MatOfPoint>(); Imgproc.findContours(image, found, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.addAll(found)
                found.filter { abs(Imgproc.contourArea(it)) >= gray.total()*.045 }.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(30).forEach { contour ->
                    val hullIndices=MatOfInt(); val hull=MatOfPoint2f(); val approximation=MatOfPoint2f()
                    try {
                        Imgproc.convexHull(contour,hullIndices)
                        val points=contour.toArray(); hull.fromArray(*hullIndices.toArray().map { points[it] }.toTypedArray())
                        for (epsilon in listOf(.012,.025,.045,.07)) {
                            Imgproc.approxPolyDP(hull,approximation,Imgproc.arcLength(hull,true)*epsilon,true)
                            if(approximation.total()==4L) ordered(approximation.toList())?.let { candidates.add(it) }
                        }
                    } finally { hullIndices.release(); hull.release(); approximation.release() }
                }
            }
            for (diameter in listOf(3.0,9.0)) {
                val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(diameter,diameter))
                try { Imgproc.morphologyEx(edges,work,Imgproc.MORPH_CLOSE,kernel); collect(work,Imgproc.RETR_LIST) }
                finally { kernel.release() }
            }
            Imgproc.GaussianBlur(gray,blurred,Size(5.0,5.0),0.0)
            for (inverse in listOf(false,true)) {
                Imgproc.threshold(blurred,mask,0.0,255.0,Imgproc.THRESH_OTSU or if(inverse) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY)
                collect(mask,Imgproc.RETR_EXTERNAL)
            }
            // Recover four long boundaries even when glare, rounded corners or a spine break a contour.
            val lines=Mat()
            try {
                val side=min(gray.cols(),gray.rows()).toDouble()
                Imgproc.HoughLinesP(edges,lines,1.0,Math.PI/180, max(25,(side*.07).toInt()),side*.18,side*.07)
                data class Line(val a:Point,val b:Point) { val length get()=hypot(b.x-a.x,b.y-a.y); val x get()=(a.x+b.x)/2; val y get()=(a.y+b.y)/2 }
                val segments=(0 until lines.rows()).mapNotNull { i -> lines.get(i,0)?.takeIf { it.size>=4 }?.let { Line(Point(it[0],it[1]),Point(it[2],it[3])) } }.sortedByDescending { it.length }.take(70)
                val horizontal=segments.filter { abs(it.b.x-it.a.x)>abs(it.b.y-it.a.y)*1.1 }
                val vertical=segments.filter { abs(it.b.y-it.a.y)>abs(it.b.x-it.a.x)*1.1 }
                val tops=horizontal.filter { it.y<gray.rows()*.52 }.take(4); val bottoms=horizontal.filter { it.y>gray.rows()*.48 }.take(4)
                val lefts=vertical.filter { it.x<gray.cols()*.52 }.take(4); val rights=vertical.filter { it.x>gray.cols()*.48 }.take(4)
                fun intersection(a:Line,b:Line):Point? {
                    val ax=a.b.x-a.a.x;val ay=a.b.y-a.a.y;val bx=b.b.x-b.a.x;val by=b.b.y-b.a.y;val cross=ax*by-ay*bx
                    if(abs(cross)<1e-6)return null
                    val t=((b.a.x-a.a.x)*by-(b.a.y-a.a.y)*bx)/cross
                    return Point(a.a.x+t*ax,a.a.y+t*ay)
                }
                for(top in tops)for(bottom in bottoms)for(left in lefts)for(right in rights) {
                    if(bottom.y-top.y<side*.2 || right.x-left.x<side*.2)continue
                    val corners=listOf(intersection(top,left),intersection(top,right),intersection(bottom,right),intersection(bottom,left))
                    if(corners.all { it!=null })ordered(corners.filterNotNull())?.let { candidates.add(it) }
                }
            } finally { lines.release() }
            var best:Frame?=null;var bestScore=0.0
            candidates.distinctBy { f -> f.points.map { (it.x*100).roundToInt() to (it.y*100).roundToInt() } }.forEach { frame ->
                val support=frame.points.indices.map { i ->
                    val a=frame.points[i];val b=frame.points[(i+1)%4]
                    (2..38).count { sample ->
                        val t=sample/40.0;val x=((a.x+(b.x-a.x)*t)*(gray.cols()-1)).roundToInt();val y=((a.y+(b.y-a.y)*t)*(gray.rows()-1)).roundToInt()
                        x in 0 until gray.cols() && y in 0 until gray.rows() && pixels[y*gray.cols()+x].toInt()!=0
                    }/37.0
                }
                if(support.min()<.18 || support.average()<.45)return@forEach
                val score=sqrt(frame.area().toDouble())*.65+support.average()*.35
                if(score>bestScore){best=frame;bestScore=score}
            }
            return best
        } finally {
            contours.forEach { it.release() };blurred.release();edges.release();work.release();mask.release();hierarchy.release();supported.release();small.release()
        }
    }
}
