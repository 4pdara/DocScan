package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Camera quality/content checks retain chroma: a sharp coloured cover can be flat in luminance. */
object CaptureQuality {
    fun sharp(gray: Mat, rgba: Mat): Boolean {
        val laplace=Mat();val mean=MatOfDouble();val deviation=MatOfDouble();val channel=Mat()
        try {
            fun sharpPlane(input:Mat):Boolean {
                Imgproc.Laplacian(input,laplace,CvType.CV_64F);Core.meanStdDev(laplace,mean,deviation)
                return deviation.toArray().firstOrNull()?.let {it*it>=45}?:false
            }
            if(sharpPlane(gray))return true
            for(i in 0..2){Core.extractChannel(rgba,channel,i);if(sharpPlane(channel))return true}
            return false
        }finally{laplace.release();mean.release();deviation.release();channel.release()}
    }
    fun signature(rgba:Mat,frame:Frame):List<Float> {
        val from=MatOfPoint2f(*frame.points.map {Point(it.x*(rgba.cols()-1).toDouble(),it.y*(rgba.rows()-1).toDouble())}.toTypedArray())
        val to=MatOfPoint2f(Point(0.0,0.0),Point(63.0,0.0),Point(63.0,63.0),Point(0.0,63.0))
        val transform=Imgproc.getPerspectiveTransform(from,to);val small=Mat()
        try {
            Imgproc.warpPerspective(rgba,small,transform,Size(64.0,64.0))
            return (0 until 64).flatMap {cell ->val region=small.submat(Rect(cell%8*8,cell/8*8,8,8))
                try{Core.mean(region).`val`.take(3).map {it.toFloat()}}finally{region.release()}}
        }finally{from.release();to.release();transform.release();small.release()}
    }
}
