package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import kotlin.math.abs

/**
 * steadies the attitude stream for *drawing only*.
 *
 * The rotation vector arrives at ~50 Hz with sample noise and hand tremor on
 * top (±0.5–1°), and every marker the overlay projects moves with it — drawn
 * raw, the whole overlay visibly trembles. An exponential moving average takes
 * that out while the aim is held, and a snap threshold keeps fast pans honest:
 * any per-sample jump bigger than [snapDegrees] is real motion, not noise, and
 * is passed through untouched so the markers never trail the scene.
 *
 * Display-only: capture decisions ([AlignmentGate], [TargetSelection]) and the
 * poses stamped onto frames must keep using the raw samples — a smoothed pose
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

    /** Folds [sample] into the running average and returns the steadied pose. */
    fun update(sample: OrientationData): OrientationData {
        if (!sample.hasFix) {
            haveFix = false
            return sample
        }
        if (!haveFix) {
            yawDegrees = sample.yawDegrees
            pitchDegrees = sample.pitchDegrees
            rollDegrees = sample.rollDegrees
            haveFix = true
            return sample
        }
        yawDegrees = smoothAngle(yawDegrees, sample.yawDegrees)
        pitchDegrees = smoothLinear(pitchDegrees, sample.pitchDegrees)
        rollDegrees = smoothAngle(rollDegrees, sample.rollDegrees)
        return sample.copy(
            yawDegrees = normalizeDegrees(yawDegrees),
            pitchDegrees = pitchDegrees,
            rollDegrees = normalizeDegrees(rollDegrees),
        )
    }

    /** Forgets the running average; the next fix starts it over. */
    fun reset() {
        haveFix = false
    }

    /** Shortest-arc smoothing for angles that wrap at ±180°. */
    private fun smoothAngle(current: Float, target: Float): Float {
        var delta = target - current
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        if (abs(delta) > snapDegrees) return target
        return current + delta * alpha
    }

    /** Pitch never wraps (it is `asin`-bounded at ±90°), so plain smoothing. */
    private fun smoothLinear(current: Float, target: Float): Float {
        val delta = target - current
        if (abs(delta) > snapDegrees) return target
        return current + delta * alpha
    }

    companion object {
        /** Per-sample weight; ~60 ms time constant at a 50 Hz sensor rate. */
        const val SMOOTHING_ALPHA = 0.3f

        /**
         * Jumps bigger than this are a pan, not tremor: passed through so the
         * markers track fast motion with no lag.
         */
        const val SNAP_DEGREES = 3f
    }
}
