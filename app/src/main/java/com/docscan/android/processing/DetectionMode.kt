package com.docscan.android.processing

/** The wider search is opt-in; the normal live camera sampling and capture timing stay fast. */
enum class DetectionMode(val analysisEdge: Int, val stillEdge: Int, val liveConfidence: Float) {
    STANDARD(288, 360, .15f),
    ANGLED_DISTANCE(480, 480, .45f),
}
