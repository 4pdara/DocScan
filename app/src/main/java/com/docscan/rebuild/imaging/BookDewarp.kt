package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.BookCurve
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/** Bounded strip remapping avoids two full-resolution float maps on memory-limited phones. */
object BookDewarp {
    fun apply(image: Mat, curve: BookCurve) {
        require(curve.valid())
        if (!curve.active) return
        val source=image.clone(); val mapX=Mat();val mapY=Mat();val result=Mat()
        try {
            val width=image.cols();val height=image.rows();val strip=64
            for(start in 0 until height step strip) {
                val rows=min(strip,height-start)
                val xs=FloatArray(width*rows);val ys=FloatArray(width*rows)
                for(y in 0 until rows)for(x in 0 until width) {
                    val i=y*width+x;xs[i]=x.toFloat()
                    ys[i]=curve.sourceY(x.toFloat()/(width-1).coerceAtLeast(1),(y+start).toFloat()/(height-1).coerceAtLeast(1))*(height-1)
                }
                mapX.create(rows,width,CvType.CV_32FC1);mapY.create(rows,width,CvType.CV_32FC1)
                mapX.put(0,0,xs);mapY.put(0,0,ys)
                Imgproc.remap(source,result,mapX,mapY,Imgproc.INTER_CUBIC,Core.BORDER_REPLICATE)
                val target=image.rowRange(start,start+rows)
                try { result.copyTo(target) } finally { target.release() }
            }
        } finally { source.release();mapX.release();mapY.release();result.release() }
    }
}
