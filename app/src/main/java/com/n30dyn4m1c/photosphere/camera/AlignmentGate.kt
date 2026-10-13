package com.n30dyn4m1c.photosphere.camera

/**
 * What the reticle should currently be showing.
 *
 * Kept separate from [AlignmentGate] so the overlay can be handed a value class
 * and previewed without a sensor behind it.
 */
data class AlignmentState(
    /** Angle between the camera's aim and the active target. NaN before the first fix. */
    val distanceDegrees: Float = Float.NaN,
    /** How much of the dwell has elapsed, 0..1. */
    val dwellProgress: Float = 0f,
    /** True while the aim is inside the capture threshold. */
    val isAligned: Boolean = false,
    /** True while a shutter is in flight. */
    val isCapturing: Boolean = false,
) {
    val hasDistance: Boolean get() = !distanceDegrees.isNaN()
}

/**
 * Decides when a held aim becomes a capture.
 *
 * The rule is "inside [thresholdDegrees] continuously for [dwellMillis]". The
 * dwell is what stops a frame being taken while the phone is still swinging
 * through the target: at a walking pan rate the reticle crosses a 2° window in
 * well under 300 ms, so only a deliberate stop fires the shutter. Any sample
 * outside the threshold clears the timer — there is no leniency, because a
 * frame captured mid-swing is a blurred frame the stitcher has to reject later.
 *
 * The caller supplies the clock, which keeps this pure and testable; use a
 * monotonic source such as `SystemClock.elapsedRealtime()` in production so a
 * wall-clock adjustment cannot fire the shutter.
 */
class AlignmentGate(
    private val thresholdDegrees: Float = DEFAULT_THRESHOLD_DEGREES,
    private val dwellMillis: Long = DEFAULT_DWELL_MILLIS,
) {

    /** Timestamp the current uninterrupted alignment began, or null if not aligned. */
    private var alignedSinceMillis: Long? = null

    /** The gate's verdict for one orientation sample. */
    data class Reading(
        val dwellProgress: Float,
        val isAligned: Boolean,
        /** True on the single sample that completes the dwell. */
        val isTriggered: Boolean,
    )

    fun update(distanceDegrees: Float, nowMillis: Long): Reading {
        if (distanceDegrees.isNaN() || distanceDegrees > thresholdDegrees) {
            alignedSinceMillis = null
            return Reading(dwellProgress = 0f, isAligned = false, isTriggered = false)
        }

        val since = alignedSinceMillis ?: nowMillis.also { alignedSinceMillis = it }
        val held = nowMillis - since
        val progress = if (dwellMillis <= 0L) 1f else (held.toFloat() / dwellMillis).coerceIn(0f, 1f)
        val triggered = held >= dwellMillis
        // The trigger is consumed here: leaving the timer running would fire
        // again on the very next sample while the user is still holding still.
        if (triggered) alignedSinceMillis = null

        return Reading(dwellProgress = progress, isAligned = true, isTriggered = triggered)
    }

    /** Drops any part-completed dwell, e.g. after advancing to a new target. */
    fun reset() {
        alignedSinceMillis = null
    }

    companion object {
        /** Aim tolerance. Tighter than the stitcher needs, loose enough to hold by hand. */
        const val DEFAULT_THRESHOLD_DEGREES: Float = 2f

        /** How long the aim must hold before the shutter fires. */
        const val DEFAULT_DWELL_MILLIS: Long = 300L
    }
}
