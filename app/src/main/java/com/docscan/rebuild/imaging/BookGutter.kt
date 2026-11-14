package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.EditRecipe
import com.docscan.rebuild.model.Treatment
import java.io.File
import kotlin.math.max

/** The spine of an open book photographed as one spread, so book mode can cut it into its two pages. */
object BookGutter {
    /**
     * Where the spine runs across the cropped spread, as a share of its width: the darkest band of columns
     * (the shadow in the fold) between 38% and 62%, or the middle when no fold is clearly darker than the paper.
     */
    fun find(file: File, recipe: EditRecipe): Float {
        val plain = recipe.withoutMarks().copy(flatten = emptyList(), erase = emptyList(), treatment = Treatment.ORIGINAL, brightness = 0f, contrast = 1f, turns = 0)
        val page = ImagePipeline.render(file, plain, 900)
        try {
            val w = page.width; val h = page.height
            if (w < 40 || h < 40) return .5f
            val sums = DoubleArray(w); val row = IntArray(w); var rows = 0
            var y = h / 5
            while (y < h * 4 / 5) {
                page.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) { val p = row[x]; sums[x] += ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000.0 }
                rows++; y += 2
            }
            val mean = DoubleArray(w) { sums[it] / rows }
            val half = max(2, w / 100)
            val smooth = DoubleArray(w) { x -> var s = 0.0; var n = 0; for (i in max(0, x - half)..minOf(w - 1, x + half)) { s += mean[i]; n++ }; s / n }
            val from = (w * .38).toInt(); val to = (w * .62).toInt()
            val darkest = (from..to).minByOrNull { smooth[it] } ?: return .5f
            val paper = smooth.sorted()[w / 2]
            return if (smooth[darkest] < paper - 8) (darkest.toFloat() / w).coerceIn(.38f, .62f) else .5f
        } finally { page.recycle() }
    }
}
