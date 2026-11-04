package com.docscan.android.processing

/** Live sampling resolution and edge-search limits; capture quality and timing stay independent. */
enum class DetectionMode(val analysisEdge: Int, val stillEdge: Int, val liveConfidence: Float) {
    STANDARD(360, 480, .15f),
    ANGLED_DISTANCE(480, 480, .45f),
}
