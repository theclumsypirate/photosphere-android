package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Freezes the displayed target completely on phones without a gyroscope.
 *
 * Filtering cannot make a compass-backed overlay both steady and responsive:
 * a small threshold passes the jitter through, and a large one makes the dot
 * lag in steps. The debug build therefore pins the marker to the pose of the
 * first fix and never moves it, no matter what the sensor reports afterwards.
 * Capture still uses the raw samples, so stamping and framing are unchanged.
 */
internal class DebugStaticNoGyroStabilizer(
    private val delegate: OrientationStabilizer,
) : OrientationStabilizer {

    private var frozen: OrientationData? = null

    override fun update(sample: OrientationData): OrientationData {
        val smoothed = delegate.update(sample)
        if (!smoothed.hasFix) return smoothed

        val current = frozen
        if (current == null) {
            frozen = smoothed
            Logging.i(
                "Debug no-gyro viewfinder: target frozen at yaw=" +
                    "${smoothed.yawDegrees} pitch=${smoothed.pitchDegrees}",
            )
            return smoothed
        }
        return current
    }

    override fun reset() {
        delegate.reset()
        frozen = null
    }
}
