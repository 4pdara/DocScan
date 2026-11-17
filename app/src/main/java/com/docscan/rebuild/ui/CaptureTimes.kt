package com.docscan.rebuild.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import com.docscan.rebuild.R

/**
 * How long each step from the shutter to the editor took for the latest photo (1.3.9). The editor shows them when
 * "זמני זיהוי במצלמה" is on, so the slow step can be found on the phone itself: the owner's videos of 1.3.7 showed
 * 3.5 to 4 seconds from the shutter to the editor.
 *
 * Each step is the time since the previous one ended, so together they cover the whole wait.
 */
object CaptureTimes {
    class Step(@StringRes val label: Int, val ms: Long)

    @Volatile var steps: List<Step> = emptyList(); private set
    /** The page the steps belong to, once the photo is a saved page. */
    @Volatile var pageId: String? = null; private set
    /** When the current step began; 0 when no photo is being timed. */
    @Volatile private var mark = 0L

    /** The shutter: a new photo is timed from here. */
    fun start() { steps = emptyList(); pageId = null; mark = SystemClock.elapsedRealtime() }

    /** Ends the current step. */
    fun step(@StringRes label: Int) {
        val began = mark; if (began == 0L) return
        val now = SystemClock.elapsedRealtime(); steps = steps + Step(label, now - began); mark = now
    }

    /** The photo became [page]; the editor's first picture of it is the last step. */
    fun saved(page: String) { if (mark != 0L) pageId = page }

    /** The editor drew its first picture of [page]. */
    fun shown(page: String) { if (page == pageId && mark != 0L) { step(R.string.timing_editor); mark = 0L } }

    /** The photo failed; nothing more is timed until the next shutter. */
    fun stop() { mark = 0L }
}
