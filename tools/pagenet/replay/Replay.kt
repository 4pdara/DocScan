import com.docscan.rebuild.imaging.LiveDetection
import com.docscan.rebuild.imaging.PageNet
import com.docscan.rebuild.model.BoundaryTracker
import com.docscan.rebuild.model.Frame
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

// Replays the app's camera path (LiveDetection + BoundaryTracker, as in CaptureScreen) on video frames named
// <milliseconds>.png, at a phone's pace: a look at most every 120 ms, the detector finishing DET ms (default 650)
// after its frame. Prints per look: time, chosen source, shown outline, chosen outline, network outline.
// MODEL: the ONNX file; QUICK=0 replays the tracker of 1.3.7 and earlier.
fun main(args: Array<String>) {
    nu.pattern.OpenCV.loadLocally()
    PageNet.install(java.io.File(System.getenv("MODEL")).readBytes())
    val det = (System.getenv("DET") ?: "650").toLong()
    var time = 0L
    val queue = ArrayDeque<Pair<Long, Runnable>>()
    val live = LiveDetection({ queue.addLast(time + det to it) }, clock = { time })
    val tracker = BoundaryTracker(quick = System.getenv("QUICK") != "0")
    var last = -100000L
    fun f(fr: Frame?) = fr?.points?.joinToString(";") { "%.4f,%.4f".format(it.x, it.y) } ?: "none"
    for (path in args.sortedBy { it.substringAfterLast('/').substringBefore('.').toLong() }) {
        val t = path.substringAfterLast('/').substringBefore('.').toLong()
        if (t - last < 120) continue
        last = t; time = t
        while (queue.isNotEmpty() && queue.first().first <= t) queue.removeFirst().second.run()
        var bgr = Imgcodecs.imread(path)
        val k = 640.0 / maxOf(bgr.cols(), bgr.rows()); val s = Mat()
        Imgproc.resize(bgr, s, Size(Math.round(bgr.cols() * k).toDouble(), Math.round(bgr.rows() * k).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA); bgr = s
        val rgba = Mat(); val gray = Mat(); Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA); Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val chosen = live.outline(gray, rgba)
        val mine = PageNet.look(rgba)?.let { PageNet.outline(it, rgba.cols(), rgba.rows()) }
        val shown = tracker.sample(chosen)
        val which = if (chosen == null) "none" else if (chosen == mine) "net" else "det"
        println("$t\t$which\t${f(shown)}\t${f(chosen)}\t${f(mine)}")
    }
}
