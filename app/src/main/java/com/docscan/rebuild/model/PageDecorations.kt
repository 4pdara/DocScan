package com.docscan.rebuild.model

/** Coordinates refer to the processed page, after crop and rotation. Originals never change. */
data class InkStroke(val points: List<Vertex>, val width: Float = .004f)
data class EraseRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PageDecorations(
    val watermark: String = "", val timestamp: String = "", val signature: List<InkStroke> = emptyList(),
    val erasures: List<EraseRegion> = emptyList(), val removeColorMarks: Boolean = false,
    val restorePhoto: Boolean = false
) {
    fun valid(): Boolean = watermark.length <= 160 && timestamp.length <= 100 && signature.size <= 100 &&
        signature.sumOf { it.points.size } <= 2000 && signature.all { stroke -> stroke.width in .001f.. .03f && stroke.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f } } &&
        erasures.size <= 40 && erasures.all { r -> listOf(r.left,r.top,r.right,r.bottom).all { it.isFinite() && it in 0f..1f } && r.left < r.right && r.top < r.bottom }
}

object PageSelection {
    /** One-based inclusive ranges, preserving the user's explicit order and rejecting duplicates. */
    fun parse(text: String, count: Int): List<Int> {
        require(count > 0 && text.length <= 4000)
        val result = mutableListOf<Int>()
        for (piece in text.split(',')) {
            val part = piece.trim(); require(part.isNotEmpty())
            val ends = part.split('-'); require(ends.size in 1..2)
            val start = ends[0].trim().toInt(); val end = ends.getOrNull(1)?.trim()?.toInt() ?: start
            require(start in 1..count && end in start..count)
            for (number in start..end) { require(number - 1 !in result); result += number - 1 }
        }
        require(result.isNotEmpty()); return result
    }
}

/** Center crop inside the detected quadrilateral, without stretching a portrait to the target ratio. */
fun EditRecipe.centerCrop(aspect:Float,width:Int,height:Int):EditRecipe {
    require(aspect>0 && aspect.isFinite())
    val target=if(turns.mod(2)==1)1f/aspect else aspect
    val current=CropGeometry.dimensions(frame,width,height,outputAspect).aspect
    val sx=if(current>target)target/current else 1f
    val sy=if(current<target)current/target else 1f
    val left=(1f-sx)/2;val top=(1f-sy)/2
    fun at(u:Float,v:Float):Vertex {
        val p=frame.points
        return Vertex((1-u)*(1-v)*p[0].x+u*(1-v)*p[1].x+u*v*p[2].x+(1-u)*v*p[3].x,
            (1-u)*(1-v)*p[0].y+u*(1-v)*p[1].y+u*v*p[2].y+(1-u)*v*p[3].y)
    }
    val cropped=Frame(listOf(at(left,top),at(1-left,top),at(1-left,1-top),at(left,1-top)))
    require(cropped.valid());return copy(frame=cropped,outputAspect=target)
}
