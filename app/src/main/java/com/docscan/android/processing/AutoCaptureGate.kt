package com.docscan.android.processing

enum class CaptureStatus { SEARCHING, HOLD_STEADY, WAIT_FOR_NEXT, PAUSED }
data class CaptureDecision(val status: CaptureStatus, val progress: Float = 0f, val capture: Boolean = false)

/** Main-thread state machine: one capture, then a real page-removal interval.
 * A pause, long frame gap, or capture in progress can never advance stability.
 */
class AutoCaptureGate(private val stableMillis: Long = 450, private val removalMillis: Long = 300) {
    private var anchor: CropQuad? = null
    private var stableSince = -1L
    private var lastFrame = -1L
    private var missingSince = -1L
    private var waiting = false
    private var absentSince = -1L
    private var absentFrames = 0
    private var capturedFingerprint: PageFingerprint? = null
    private var stableFingerprint: PageFingerprint? = null
    private var lastFingerprint: PageFingerprint? = null

    fun interrupt() { anchor = null; stableFingerprint = null; stableSince = -1; lastFrame = -1; absentSince = -1; absentFrames = 0; missingSince = -1 }
    fun markCaptured() { waiting = true; capturedFingerprint = lastFingerprint; interrupt() }
    fun reset() { waiting = false; capturedFingerprint = null; lastFingerprint = null; interrupt() }

    fun update(quad: CropQuad?, now: Long, blocked: Boolean = false, fingerprint: PageFingerprint? = null): CaptureDecision {
        val detected = quad?.takeIf { it.isValid() }
        lastFingerprint = fingerprint
        if (blocked) { interrupt(); return CaptureDecision(CaptureStatus.PAUSED) }
        if (lastFrame >= 0 && (now - lastFrame !in 0..500)) interrupt()
        lastFrame = now
        if (waiting) {
            if (detected == null) {
                if (absentSince < 0) absentSince = now
                absentFrames++
                if (absentFrames >= 3 && now - absentSince >= removalMillis) {
                    waiting = false; anchor = null; stableSince = -1
                }
            } else {
                absentSince = -1; absentFrames = 0
                val captured = capturedFingerprint
                if (captured != null && fingerprint != null && fingerprint.differsFrom(captured)) {
                    return hold(detected, now, fingerprint)
                }
                anchor = null; stableFingerprint = null; stableSince = -1
            }
            return CaptureDecision(if (waiting) CaptureStatus.WAIT_FOR_NEXT else CaptureStatus.SEARCHING)
        }
        if (detected == null) {
            if (missingSince < 0) missingSince = now
            if (now - missingSince >= 220) { anchor = null; stableSince = -1; stableFingerprint = null }
            return CaptureDecision(CaptureStatus.SEARCHING)
        }
        return hold(detected, now, fingerprint)
    }

    private fun hold(quad: CropQuad, now: Long, fingerprint: PageFingerprint?): CaptureDecision {
        // A missed frame may retain the anchor, but never contributes to the stable duration.
        if (missingSince >= 0 && stableSince >= 0) stableSince += now - missingSince
        missingSince = -1
        if (anchor == null || quad.maxDistance(requireNotNull(anchor)) > .032f ||
            (stableFingerprint != null && fingerprint != null && fingerprint.distance(requireNotNull(stableFingerprint)) > 7f)) {
            anchor = quad; stableSince = now; stableFingerprint = fingerprint
        }
        val progress = ((now - stableSince).toFloat() / stableMillis).coerceIn(0f, 1f)
        if (progress >= 1f) { markCaptured(); return CaptureDecision(CaptureStatus.WAIT_FOR_NEXT, 1f, true) }
        return CaptureDecision(CaptureStatus.HOLD_STEADY, progress)
    }
}
