package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import kotlin.math.abs

/**
 * Steadies the attitude stream for *drawing only*.
 *
 * The attitude arrives at ~50 Hz with sample noise and hand tremor on top
 * (+/-0.5 to 1 degree on a gyro-assisted phone), and every marker the overlay
 * projects moves with it. Drawn raw, the whole overlay visibly trembles. An
 * exponential moving average takes that out while the aim is held, and a snap
 * threshold keeps fast pans honest: any per-sample jump bigger than
 * [snapDegrees] is real motion, not noise, and is passed through untouched so
 * the markers never trail the scene.
 *
 * A phone without a gyroscope has no fused, magnetometer-free rotation vector
 * to fall back on: its attitude is magnetometer-only and its yaw jitters by
 * several degrees per sample, easily past [snapDegrees] every frame. The snap
 * test then reads every one of those jitters as a pan and passes it straight
 * through, which is what makes the target marker wander on such phones. Those
 * samples are marked [OrientationData.isGyroAssisted] false, and are run
 * through a short window median and a heavier average first, so the spikes
 * stop reaching the markers while a real pan still snaps through.
 *
 * Display-only: capture decisions ([AlignmentGate], [TargetSelection]) and the
 * poses stamped onto frames must keep using the raw samples - a smoothed pose
 * would smear the dwell mean and the stitch start guess.
 */
class OrientationSmoother(
    private val alpha: Float = SMOOTHING_ALPHA,
    private val snapDegrees: Float = SNAP_DEGREES,
) {
    private var haveFix = false
    private var yawDegrees = 0f
    private var pitchDegrees = 0f
    private var rollDegrees = 0f

    /**
     * Three-deep raw history for the noisy path's median, one ring per axis.
     * Angles only, and fixed size, so the 50 Hz stream stays allocation-free.
     */
    private val rawYaw = FloatArray(MEDIAN_WINDOW)
    private val rawPitch = FloatArray(MEDIAN_WINDOW)
    private val rawRoll = FloatArray(MEDIAN_WINDOW)
    private var rawIndex = 0
    private var rawFilled = 0

    /** Folds [sample] into the running average and returns the steadied pose. */
    fun update(sample: OrientationData): OrientationData {
        if (!sample.hasFix) {
            haveFix = false
            rawFilled = 0
            rawIndex = 0
            return sample
        }
        if (!haveFix) {
            yawDegrees = sample.yawDegrees
            pitchDegrees = sample.pitchDegrees
            rollDegrees = sample.rollDegrees
            haveFix = true
            return sample
        }

        var targetYaw = sample.yawDegrees
        var targetPitch = sample.pitchDegrees
        var targetRoll = sample.rollDegrees
        var weight = alpha

        if (!sample.isGyroAssisted) {
            rawYaw[rawIndex] = sample.yawDegrees
            rawPitch[rawIndex] = sample.pitchDegrees
            rawRoll[rawIndex] = sample.rollDegrees
            rawIndex = (rawIndex + 1) % MEDIAN_WINDOW
            rawFilled = minOf(rawFilled + 1, MEDIAN_WINDOW)
            weight = NOISY_ALPHA
            // Only once the window is full: a median of fewer than three
            // samples would just pass the newest (noisy) value through.
            if (rawFilled == MEDIAN_WINDOW) {
                targetYaw = median3Wrapped(rawYaw[0], rawYaw[1], rawYaw[2])
                targetPitch = median3(rawPitch[0], rawPitch[1], rawPitch[2])
                targetRoll = median3Wrapped(rawRoll[0], rawRoll[1], rawRoll[2])
            }
        }

        yawDegrees = smooth(yawDegrees, targetYaw, weight, wrapped = true)
        pitchDegrees = smooth(pitchDegrees, targetPitch, weight, wrapped = false)
        rollDegrees = smooth(rollDegrees, targetRoll, weight, wrapped = true)
        return sample.copy(
            yawDegrees = normalizeDegrees(yawDegrees),
            pitchDegrees = pitchDegrees,
            rollDegrees = normalizeDegrees(rollDegrees),
        )
    }

    /** Forgets the running average; the next fix starts it over. */
    fun reset() {
        haveFix = false
        rawFilled = 0
        rawIndex = 0
    }

    /**
     * One step of the average. A jump past [snapDegrees] is taken as real
     * motion and passed through whole; anything smaller is blended in.
     */
    private fun smooth(current: Float, target: Float, weight: Float, wrapped: Boolean): Float {
        var delta = target - current
        if (wrapped) delta = normalizeDegrees(delta)
        if (abs(delta) > snapDegrees) return target
        return current + delta * weight
    }

    /** Middle of three plain values. */
    private fun median3(a: Float, b: Float, c: Float): Float =
        maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    /**
     * Middle of three angles that may straddle the +/-180 degree seam, taken
     * the short way round so a cluster near the seam does not average through
     * zero (which would swing the markers the long way).
     */
    private fun median3Wrapped(a: Float, b: Float, c: Float): Float {
        val b2 = a + normalizeDegrees(b - a)
        val c2 = a + normalizeDegrees(c - a)
        return normalizeDegrees(median3(a, b2, c2))
    }

    companion object {
        /** Per-sample weight; ~60 ms time constant at a 50 Hz sensor rate. */
        const val SMOOTHING_ALPHA = 0.3f

        /**
         * Jumps bigger than this are a pan, not tremor: passed through so the
         * markers track fast motion with no lag.
         */
        const val SNAP_DEGREES = 3f

        /**
         * Averaging weight for a source without a gyroscope. Slightly heavier
         * than [SMOOTHING_ALPHA]: the window median removes the worst of the
         * spikes, and this takes the leftover jitter down a little more without
         * changing how a pan feels (that is [snapDegrees]'s job).
         */
        const val NOISY_ALPHA = 0.25f

        /** Samples in the noisy path's median window. Must stay three. */
        const val MEDIAN_WINDOW = 3
    }
}
