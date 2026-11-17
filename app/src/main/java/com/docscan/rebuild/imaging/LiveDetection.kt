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
 */
class LiveDetection(private val background: Executor, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** The latest times, in milliseconds, of the network's look and of the detector's (null until measured). */
    data class Timing(val networkMs: Long? = null, val detectorMs: Long? = null)

    @Volatile var timing = Timing(); private set
    private val running = AtomicBoolean(false)
    /** The detector's latest outline and when its frame was given. */
    @Volatile private var detected: Pair<Long, Frame?>? = null

    fun outline(gray: Mat, rgba: Mat): Frame? {
        if (!PageNet.available) {
            val started = clock()
            return ImagePipeline.detectGray(gray, rgba).also { timing = Timing(detectorMs = clock() - started) }
        }
        val now = clock()
        if (running.compareAndSet(false, true)) {
            val g = gray.clone(); val c = rgba.clone()
            val task = Runnable {
                try {
                    val started = clock()
                    val found = BoundaryDetector.detect(g, c)
                    detected = now to found
                    timing = timing.copy(detectorMs = clock() - started)
                } catch (e: Exception) { android.util.Log.w("DocScanRebuild", "Detector failed on a camera frame", e) }
                finally { g.release(); c.release(); running.set(false) }
            }
            try { background.execute(task) } catch (e: RejectedExecutionException) { g.release(); c.release(); running.set(false) }
        }
        val started = clock()
        val look = PageNet.look(rgba) ?: return detected?.second
        val mine = PageNet.outline(look, rgba.cols(), rgba.rows())
        timing = timing.copy(networkMs = clock() - started)
        val recent = detected?.takeIf { now - it.first <= STALE }?.second
        return PageNet.choose(recent, look, mine)
    }

    private companion object { const val STALE = 2000L }
}
