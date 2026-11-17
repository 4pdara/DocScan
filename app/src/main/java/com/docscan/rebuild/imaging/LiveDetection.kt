package com.docscan.rebuild.imaging

import com.docscan.rebuild.model.Frame
import org.opencv.core.Mat
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Page outlines for the camera view (1.3.7). DocScan's page network ([PageNet]) looks at every frame it is given
 * (tens of milliseconds); the full detector, which takes most of a second on a phone, runs at its own pace on
 * [background], on a copy of the latest frame. Each frame's outline is [PageNet.choose] between the network's and the
 * detector's latest one (at most [STALE] ms old): while the phone is still, they agree and the detector's sharper
 * outline is shown; when it moves, the old outline no longer lies on the page's edges and the network's follows the
 * page at once. Without the network, every frame goes to the detector as before.
 *
 * 1.3.8: the detector's outline is used only while the network's outline is where it was on the detector's frame (the
 * phone has not moved since). Phone videos of 1.3.7 showed outlines up to two seconds old chosen while moving.
 */
class LiveDetection(private val background: Executor, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** The latest times, in milliseconds, of the network's look and of the detector's (null until measured). */
    data class Timing(val networkMs: Long? = null, val detectorMs: Long? = null)

    @Volatile var timing = Timing(); private set
    private val running = AtomicBoolean(false)
    /** A frame given to the detector: when, the network's outline on it, and the detector's. */
    private class Look(val at: Long) { @Volatile var network: Frame? = null; @Volatile var found: Frame? = null }
    /** The detector's latest finished look. */
    @Volatile private var detected: Look? = null

    fun outline(gray: Mat, rgba: Mat): Frame? {
        if (!PageNet.available) {
            val started = clock()
            return ImagePipeline.detectGray(gray, rgba).also { timing = Timing(detectorMs = clock() - started) }
        }
        val now = clock()
        var given: Look? = null
        if (running.compareAndSet(false, true)) {
            val g = gray.clone(); val c = rgba.clone(); val job = Look(now); given = job
            val task = Runnable {
                try {
                    val started = clock()
                    job.found = BoundaryDetector.detect(g, c)
                    detected = job
                    timing = timing.copy(detectorMs = clock() - started)
                } catch (e: Exception) { android.util.Log.w("DocScanRebuild", "Detector failed on a camera frame", e) }
                finally { g.release(); c.release(); running.set(false) }
            }
            try { background.execute(task) } catch (e: RejectedExecutionException) { g.release(); c.release(); running.set(false); given = null }
        }
        val started = clock()
        val look = PageNet.look(rgba) ?: return detected?.takeIf { now - it.at <= STALE }?.found
        val mine = PageNet.outline(look, rgba.cols(), rgba.rows())
        given?.network = mine
        timing = timing.copy(networkMs = clock() - started)
        val recent = detected?.takeIf { now - it.at <= STALE && still(it, mine) }?.found
        return PageNet.choose(recent, look, mine)
    }

    /** The phone has not moved since the detector's frame: the network sees the page where it saw it then. */
    private fun still(look: Look, mine: Frame?): Boolean {
        if (mine == null) return true
        val then = look.network ?: return false
        return then.motion(mine) <= STILL
    }

    private companion object { const val STALE = 2000L; const val STILL = .025f }
}
