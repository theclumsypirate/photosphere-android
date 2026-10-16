package com.n30dyn4m1c.photosphere.orbit

import android.hardware.SensorManager
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Extracts absolute yaw from a rotation vector reading.
 *
 * Uses [SensorManager.getRotationMatrixFromVector] and
 * [SensorManager.getOrientation], so it works with the fused
 * rotation vector sensor on any device. No Context needed.
 *
 * @return yaw in degrees, wrapped to -180..180 via [wrapAngle].
 */
fun absoluteYawDegrees(rotationVector: FloatArray): Float {
    val matrix = FloatArray(9)
    SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
    val orientation = FloatArray(3)
    SensorManager.getOrientation(matrix, orientation)
    return wrapAngle(Math.toDegrees(orientation[0].toDouble()).toFloat())
}

/**
 * Tracks yaw relative to an anchored reference pose.
 *
 * Anchor once at a known pose, then each new rotation vector
 * yields a wrapped delta against that anchor. Drift free over
 * short windows but absolute, so it jumps if the sensor
 * re-bases its coordinate frame.
 */
class RelativeRotationTracker {
    /** Anchor yaw in degrees, null until [anchor] is called. */
    private var anchorYawDegrees: Float? = null

    /**
     * Stores the reference yaw from [rotationVector].
     */
    fun anchor(rotationVector: FloatArray) {
        anchorYawDegrees = absoluteYawDegrees(rotationVector)
        Logging.i("Orbit gyro anchored")
    }

    /**
     * Returns the wrapped delta from the anchor to [rotationVector].
     *
     * @return signed delta in degrees, -180..180 via [wrapDelta].
     * @throws IllegalStateException if [anchor] was never called.
     */
    fun yawDeltaDegrees(rotationVector: FloatArray): Float {
        val anchor = checkNotNull(anchorYawDegrees) { "call anchor() before yawDeltaDegrees()" }
        return wrapDelta(absoluteYawDegrees(rotationVector) - anchor)
    }

    /** Clears the anchor; next [yawDeltaDegrees] call throws until re-anchored. */
    fun reset() {
        anchorYawDegrees = null
    }
}

/**
 * Hybrid orbit yaw engine: fast gyro deltas plus slow absolute fixes.
 *
 * The gyro path ([onGyroYawDelta]) is smooth and high rate but drifts.
 * Each vision anchor ([reanchor]) snaps the estimate back to truth,
 * e.g. when the FRONT phase gives a known absolute bearing.
 *
 * @param anchorGain blend weight 0..1 applied to each absolute fix.
 */
class HybridOrbitEngine(
    private val anchorGain: Float = 1f,
) {
    /** Current fused yaw estimate in degrees, -180..180. */
    var currentYawDegrees: Float = 0f
        private set

    /**
     * Accumulates one smooth high rate gyro yaw step.
     *
     * @param deltaDegrees signed yaw step since last call, degrees.
     */
    fun onGyroYawDelta(deltaDegrees: Float) {
        currentYawDegrees = wrapAngle(currentYawDegrees + deltaDegrees)
    }

    /**
     * Corrects drift toward a trusted absolute yaw.
     *
     * Applies [anchorGain] of the wrapped error, so gain 1 snaps
     * exactly and smaller gains ease toward the anchor.
     *
     * @param absoluteYawDegrees trusted absolute yaw, degrees.
     */
    fun reanchor(absoluteYawDegrees: Float) {
        require(anchorGain in 0f..1f) { "anchorGain must be 0..1, was $anchorGain" }
        val error = wrapDelta(absoluteYawDegrees - currentYawDegrees)
        currentYawDegrees = wrapAngle(currentYawDegrees + anchorGain * error)
        Logging.i("Orbit hybrid reanchored, error $error")
    }

    /** Resets the estimate to zero. */
    fun reset() {
        currentYawDegrees = 0f
    }
}
