package com.n30dyn4m1c.photosphere.orbit

import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.abs

/**
 * A target on the multi-ring orbit grid. Pure data.
 *
 * @param ringIndex which ring holds the target (0, 1, 2).
 * @param targetIndex index inside the ring.
 * @param yawDegrees relative bearing from walk start, degrees -180..180.
 * @param pitchDegrees ring pitch, degrees.
 */
data class OrbitPitchTarget(
    val ringIndex: Int,
    val targetIndex: Int,
    val yawDegrees: Float,
    val pitchDegrees: Float,
)

/**
 * Multi-ring orbit grid around a subject. Pure logic, no Android.
 * Rings: pitch 0 with 12 yaw targets, pitch +-30 with 8 each,
 * pitch +-60 with 4 each. Flat target order is ring by ring.
 * Yaw values are relative to the walk start bearing, not compass.
 *
 * @param rings target lists per ring, in capture order of rings.
 * @param yawToleranceDegrees how close yaw must be to count as on target.
 * @param pitchToleranceDegrees how close pitch must be to count as on target.
 */
data class OrbitPitchPlan(
    val rings: List<List<OrbitPitchTarget>>,
    val yawToleranceDegrees: Float = 6f,
    val pitchToleranceDegrees: Float = 6f,
) {
    /** All targets in capture order. */
    val targets: List<OrbitPitchTarget> = rings.flatten()

    /** Total target count across rings. */
    val size: Int get() = targets.size

    /** Index of the first flat target without a photo, or null when complete. */
    fun nextTarget(captured: Set<Int>): Int? =
        targets.indices.firstOrNull { it !in captured }

    /** True when the reading sits on [targets[index]] in yaw and pitch. */
    fun isOnTarget(yawDegrees: Float, pitchDegrees: Float, index: Int): Boolean {
        val t = targets[index]
        return abs(wrapDelta(yawDegrees - t.yawDegrees)) <= yawToleranceDegrees &&
            abs(pitchDegrees - t.pitchDegrees) <= pitchToleranceDegrees
    }

    /** Signed shortest yaw turn from [fromYaw] to [targets[index]]. */
    fun turnTo(fromYaw: Float, index: Int): Float =
        wrapDelta(targets[index].yawDegrees - fromYaw)

    /** Pitch move from [fromPitch] to [targets[index]]. Positive means tilt up. */
    fun tiltTo(fromPitch: Float, index: Int): Float =
        targets[index].pitchDegrees - fromPitch
}

/** Ring pitch values in capture order. */
const val PITCH_RING_HIGH = 30f
const val PITCH_RING_LEVEL = 0f
const val PITCH_RING_LOW = -30f
const val PITCH_RING_TOP = 60f
const val PITCH_RING_BOTTOM = -60f

/** Target counts per ring: level 12, +-30 eight each, +-60 four each. */
const val PITCH_RING_LEVEL_COUNT = 12
const val PITCH_RING_HIGH_COUNT = 8
const val PITCH_RING_LOW_COUNT = 8
const val PITCH_RING_TOP_COUNT = 4
const val PITCH_RING_BOTTOM_COUNT = 4

/** Builds the default 36 target grid starting at relative yaw 0. */
fun orbitPitchPlan(startYaw: Float = 0f): OrbitPitchPlan {
    val rings = listOf(
        ringTargets(0, PITCH_RING_LEVEL, PITCH_RING_LEVEL_COUNT, startYaw),
        ringTargets(1, PITCH_RING_HIGH, PITCH_RING_HIGH_COUNT, startYaw),
        ringTargets(2, PITCH_RING_LOW, PITCH_RING_LOW_COUNT, startYaw),
        ringTargets(3, PITCH_RING_TOP, PITCH_RING_TOP_COUNT, startYaw),
        ringTargets(4, PITCH_RING_BOTTOM, PITCH_RING_BOTTOM_COUNT, startYaw),
    )
    return OrbitPitchPlan(rings, yawToleranceDegrees = 4f, pitchToleranceDegrees = 4f)
        .also { Logging.i("Orbit pitch plan: ${it.size} targets") }
}

/** Evenly spaced yaw targets for one ring starting at [startYaw], clockwise. */
fun ringTargets(
    ringIndex: Int,
    pitchDegrees: Float,
    count: Int,
    startYaw: Float = 0f,
): List<OrbitPitchTarget> {
    require(count in 1..36) { "ring needs 1..36 targets, was $count" }
    val step = 360f / count
    return List(count) { i ->
        OrbitPitchTarget(
            ringIndex = ringIndex,
            targetIndex = i,
            yawDegrees = wrapAngle(startYaw + i * step),
            pitchDegrees = pitchDegrees,
        )
    }
}
