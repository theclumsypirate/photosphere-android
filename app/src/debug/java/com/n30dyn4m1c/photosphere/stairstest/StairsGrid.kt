package com.n30dyn4m1c.photosphere.stairstest

import com.n30dyn4m1c.photosphere.orbit.wrapDelta
import kotlin.math.abs

/**
 * One point of the stairs capture grid. Pure data.
 *
 * @param index flat capture order, and the photofile number.
 * @param yawDegrees bearing measured from where the walk started, -180..180.
 * @param pitchDegrees aim tilt in the sensor convention: negative is up.
 */
data class StairsTarget(
    val index: Int,
    val yawDegrees: Float,
    val pitchDegrees: Float,
) {
    /** Ring the target belongs to; see [StairsPlan.rings]. */
    val isCap: Boolean get() = abs(pitchDegrees) > 60f
}

/**
 * The 62 point sphere grid proven in `tools/pano_proto`.
 *
 * Five pitch rows of ten bearings each, thirty six degrees apart, plus six
 * zenith and six nadir caps. The layout, the sign convention (negative is
 * up) and the yaw anchoring at walk start all mirror the Python side
 * (`virtual_frames_stairs.py`), which closed the loop against the source
 * panorama to 1.8/255 with no structural defects. This screen exists so a
 * real capture can be aimed frame by frame at the same coordinates.
 *
 * Debug only: only referenced from `app/src/debug`.
 */
data class StairsPlan(
    val rings: List<List<StairsTarget>>,
    val yawToleranceDegrees: Float = 3f,
    val pitchToleranceDegrees: Float = 3f,
) {
    /** All targets in capture order. */
    val targets: List<StairsTarget> = rings.flatten()

    val size: Int get() = targets.size

    /** Index of the first target without a photo, or null when complete. */
    fun nextTarget(captured: Set<Int>): Int? =
        targets.indices.firstOrNull { it !in captured }

    /** True when an aim sits on [index] in yaw and pitch. */
    fun isOnTarget(yawDegrees: Float, pitchDegrees: Float, index: Int): Boolean {
        val t = targets[index]
        return abs(wrapDelta(yawDegrees - t.yawDegrees)) <= yawToleranceDegrees &&
            abs(pitchDegrees - t.pitchDegrees) <= pitchToleranceDegrees
    }

    /** Signed shortest yaw turn from [fromYaw] to [index]. Positive is right. */
    fun turnTo(fromYaw: Float, index: Int): Float =
        wrapDelta(targets[index].yawDegrees - fromYaw)

    /** Pitch move from [fromPitch] to [index]. Negative means tilt up. */
    fun tiltTo(fromPitch: Float, index: Int): Float =
        targets[index].pitchDegrees - fromPitch

    /** Nearest uncaptured target to a live aim, or null when complete. */
    fun nearestTarget(yawDegrees: Float, pitchDegrees: Float, captured: Set<Int>): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for (i in targets.indices) {
            if (i in captured) continue
            val t = targets[i]
            val d = abs(wrapDelta(t.yawDegrees - yawDegrees)) +
                abs(t.pitchDegrees - pitchDegrees)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }
}

/** Sensor convention: negative pitch is up. */
const val STAIRS_PITCH_TOP = -45f
const val STAIRS_PITCH_HIGH = -20f
const val STAIRS_PITCH_LEVEL = 0f
const val STAIRS_PITCH_LOW = 20f
const val STAIRS_PITCH_BOTTOM = 45f

/** Caps sit close to the poles. */
const val STAIRS_PITCH_ZENITH = -75f
const val STAIRS_PITCH_NADIR = 75f

/** Ten bearings per ring, thirty six degrees apart. */
const val STAIRS_RING_COUNT = 10

/** Six caps per pole. */
const val STAIRS_CAP_COUNT = 6

/**
 * Builds the 62 target grid, first bearing at [startYaw].
 *
 * Ring order is a hand-friendly walk: level first, then up, down, further
 * up, further down, then the zenith and nadir caps last.
 */
fun stairsPlan(startYaw: Float = 0f): StairsPlan {
    val rings = listOf(
        ringTargets(STAIRS_PITCH_LEVEL, STAIRS_RING_COUNT, startYaw),
        ringTargets(STAIRS_PITCH_HIGH, STAIRS_RING_COUNT, startYaw),
        ringTargets(STAIRS_PITCH_LOW, STAIRS_RING_COUNT, startYaw),
        ringTargets(STAIRS_PITCH_TOP, STAIRS_RING_COUNT, startYaw),
        ringTargets(STAIRS_PITCH_BOTTOM, STAIRS_RING_COUNT, startYaw),
        ringTargets(STAIRS_PITCH_ZENITH, STAIRS_CAP_COUNT, startYaw),
        ringTargets(STAIRS_PITCH_NADIR, STAIRS_CAP_COUNT, startYaw),
    )
    var index = 0
    val numbered = rings.map { ring ->
        ring.map { target -> target.copy(index = index++) }
    }
    return StairsPlan(numbered)
}

/** Evenly spaced bearings for one ring starting at [startYaw], clockwise. */
private fun ringTargets(
    pitchDegrees: Float,
    count: Int,
    startYaw: Float = 0f,
): List<StairsTarget> {
    require(count in 1..36) { "ring needs 1..36 targets, was $count" }
    val step = 360f / count
    return List(count) { i ->
        StairsTarget(
            index = 0,
            yawDegrees = wrapDelta(startYaw + i * step),
            pitchDegrees = pitchDegrees,
        )
    }
}
