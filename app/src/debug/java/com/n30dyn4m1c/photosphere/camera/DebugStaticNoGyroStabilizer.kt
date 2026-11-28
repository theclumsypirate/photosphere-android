package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import kotlin.math.abs
import kotlin.math.max

/**
 * Holds the displayed target still on phones without a gyroscope.
 *
 * The compass-backed rotation vector jitters by several degrees between
 * samples. A single jump past the smoother's snap threshold is a spike, so it
 * is ignored here. The pose is adopted only when the movement stays beyond
 * [HOLD_DEGREES] for [SUSTAIN_NANOS], which is a deliberate pan rather than
 * noise. Everything downstream of the viewfinder still sees raw samples.
 */
internal class DebugStaticNoGyroStabilizer(
    private val delegate: OrientationStabilizer,
) : OrientationStabilizer {

    private var stable: OrientationData? = null
    private var candidateSinceNanos = 0L

    override fun update(sample: OrientationData): OrientationData {
        val smoothed = delegate.update(sample)
        if (!smoothed.hasFix) {
            reset()
            return smoothed
        }

        val held = stable
        if (held == null) {
            stable = smoothed
            return smoothed
        }

        val change = max(
            abs(normalizeDegrees(smoothed.yawDegrees - held.yawDegrees)),
            max(
                abs(smoothed.pitchDegrees - held.pitchDegrees),
                abs(normalizeDegrees(smoothed.rollDegrees - held.rollDegrees)),
            ),
        )
        if (change <= HOLD_DEGREES) {
            candidateSinceNanos = 0L
            return held
        }
        if (candidateSinceNanos == 0L) {
            candidateSinceNanos = smoothed.timestampNanos
            return held
        }
        if (smoothed.timestampNanos - candidateSinceNanos < SUSTAIN_NANOS) return held

        stable = smoothed
        candidateSinceNanos = 0L
        return smoothed
    }

    override fun reset() {
        delegate.reset()
        stable = null
        candidateSinceNanos = 0L
    }

    companion object {
        /** Per-sample wiggle below this is noise, not a pan. */
        const val HOLD_DEGREES = 4f

        /** A real pan has to persist this long before the dot follows it. */
        const val SUSTAIN_NANOS = 250_000_000L
    }
}
