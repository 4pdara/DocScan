package com.docscan.android.processing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Metric rectangle recovery for a centred, square-pixel camera. An uncertain estimate is ignored. */
object PerspectiveAspect {
    fun estimate(width: Int, height: Int, quad: CropQuad): Float? {
        if (!quad.isValid() || width <= 0 || height <= 0) return null
        val p = quad.points.map { doubleArrayOf(it.x.toDouble()*width, it.y.toDouble()*height) }
        val dx1=p[1][0]-p[2][0]; val dx2=p[3][0]-p[2][0]
        val dy1=p[1][1]-p[2][1]; val dy2=p[3][1]-p[2][1]
        val sx=p[0][0]-p[1][0]+p[2][0]-p[3][0]
        val sy=p[0][1]-p[1][1]+p[2][1]-p[3][1]
        val determinant=dx1*dy2-dx2*dy1
        if (abs(determinant)<.000001) return null
        val g=(sx*dy2-dx2*sy)/determinant
        val h=(dx1*sy-sx*dy1)/determinant
        // Near-parallel vanishing directions do not constrain an unknown focal length reliably.
        if (abs(g)<.02 || abs(h)<.02) return null
        val cx=width/2.0; val cy=height/2.0
        val ax=p[1][0]-p[0][0]+g*(p[1][0]-cx)
        val ay=p[1][1]-p[0][1]+g*(p[1][1]-cy)
        val bx=p[3][0]-p[0][0]+h*(p[3][0]-cx)
        val by=p[3][1]-p[0][1]+h*(p[3][1]-cy)
        val focalSquared=-(ax*bx+ay*by)/(g*h)
        val edge=max(width,height).toDouble()
        if (!focalSquared.isFinite() || focalSquared !in (edge*.3)*(edge*.3)..(edge*4)*(edge*4)) return null
        val numerator=ax*ax+ay*ay+focalSquared*g*g
        val denominator=bx*bx+by*by+focalSquared*h*h
        if (numerator<=0 || denominator<=0) return null
        val ratio=sqrt(numerator/denominator)
        fun distance(a:Int,b:Int)=hypot(p[a][0]-p[b][0],p[a][1]-p[b][1])
        val observed=max(distance(0,1),distance(3,2))/max(distance(0,3),distance(1,2))
        // No extreme correction from noisy corners, a curved page or an already edited photograph.
        return ratio.takeIf { it.isFinite() && it in .25..4.0 && it/observed in .5..2.0 }?.toFloat()
    }
}
