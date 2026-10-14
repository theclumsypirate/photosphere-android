package com.n30dyn4m1c.photosphere.orbit

import kotlin.math.abs

/**
 * An orbit walk: the photographer circles the subject and shoots it from
 * evenly spaced compass bearings (outside-in, the opposite of a photo
 * sphere). Pure logic, no Android: the screen feeds it live yaw readings
 * and it answers "where next" and "are we done".
 *
 * @param angles target bearings in capture order, degrees -180..180.
 * @param toleranceDegrees how close a reading must be to count as on target.
 */
data class OrbitPlan(
    val angles: List<Float>,
    val toleranceDegrees: Float = 6f,
) {
    /** Index of the first angle without a photo, or null when complete. */
    fun nextTarget(captured: Set<Int>): Int? =
        angles.indices.firstOrNull { it !in captured }

    /** True when the reading sits on [angles[index]]. */
    fun isOnTarget(yawDegrees: Float, index: Int): Boolean =
        abs(wrapDelta(yawDegrees - angles[index])) <= toleranceDegrees

    /** Signed shortest turn from [from] to [to], degrees -180..180. */
    fun turnTo(fromYaw: Float, index: Int): Float =
        wrapDelta(angles[index] - fromYaw)
}

/** Evenly spaced bearings starting at [startYaw], clockwise. */
fun orbitPlan(count: Int = DEFAULT_ANGLE_COUNT, startYaw: Float = 0f): OrbitPlan {
    require(count in 3..36) { "orbit needs 3..36 angles, was $count" }
    val step = 360f / count
    return OrbitPlan(List(count) { i -> wrapAngle(startYaw + i * step) })
}

/** Wraps any angle to -180..180. */
fun wrapAngle(degrees: Float): Float {
    var a = (degrees + 180f) % 360f
    if (a < 0) a += 360f
    return a - 180f
}

/** Shortest signed difference, degrees -180..180. */
fun wrapDelta(degrees: Float): Float = wrapAngle(degrees)

const val DEFAULT_ANGLE_COUNT = 12
