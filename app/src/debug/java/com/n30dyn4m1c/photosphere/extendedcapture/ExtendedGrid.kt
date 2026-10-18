package com.n30dyn4m1c.photosphere.extendedcapture

import com.n30dyn4m1c.photosphere.camera.FieldOfView
import com.n30dyn4m1c.photosphere.camera.SphereTarget
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan
import com.n30dyn4m1c.photosphere.orbit.wrapDelta
import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.abs

/*
 * The 62 point sphere grid proven in `tools/pano_proto`, as the app's own plan.
 *
 * Five pitch rows of ten bearings each, thirty six degrees apart, plus six
 * zenith and six nadir caps. The coordinates, the sign convention (negative is
 * up) and the yaw anchoring at walk start all mirror the Python side
 * (`virtual_frames_stairs.py`), which closed the loop against the source
 * panorama to 1.8/255 with no structural defects. This screen exists so a real
 * capture can be aimed at exactly those coordinates.
 *
 * The plan is the app's [SphereTargetPlan] rather than a private type, so the
 * overlay, the target selection and the progress readout all speak the same
 * language the normal capture path does. Only the reference grid is laid out
 * here; a lens that is not the one this grid was proven for walks the adaptive
 * plan from its own field of view instead (see
 * [SphereTargetPlan.createForFieldOfView]).
 *
 * The walk order is not the Python cut order: that script starts its rows at
 * -45 degrees and its caps at the nadir, while this walks level first for a
 * hand, so frame NN of a real run is not vframe NN of the virtual set. The
 * stitch is unaffected, because every frame is placed by its own pose sidecar
 * rather than by its position in the list; only a frame by frame comparison of
 * the two sets has to account for it.
 *
 * Debug only: only referenced from `app/src/debug`.
 */

/** Each aim must come within this on both axes before the shutter is allowed. */const val EXTENDED_TOLERANCE_DEGREES = 3f

/** Sensor convention: negative pitch is up. */
private const val PITCH_TOP = -45f
private const val PITCH_HIGH = -20f
private const val PITCH_LEVEL = 0f
private const val PITCH_LOW = 20f
private const val PITCH_BOTTOM = 45f

/** Caps sit close to the poles. */
private const val PITCH_ZENITH = -75f
private const val PITCH_NADIR = 75f

/** Ten bearings per ring, thirty six degrees apart. */
private const val RING_COUNT = 10

/** Six caps per pole. */
private const val CAP_COUNT = 6

/** A target past this tilt belongs to a cap rather than a ring. */
private const val CAP_PITCH_DEGREES = 60f

/**
 * Ring order is a hand-friendly walk: level first, then up, down, further up,
 * further down, then the zenith and nadir caps last.
 */
private val REFERENCE_RINGS: List<Pair<Float, Int>> = listOf(
    PITCH_LEVEL to RING_COUNT,
    PITCH_HIGH to RING_COUNT,
    PITCH_LOW to RING_COUNT,
    PITCH_TOP to RING_COUNT,
    PITCH_BOTTOM to RING_COUNT,
    PITCH_ZENITH to CAP_COUNT,
    PITCH_NADIR to CAP_COUNT,
)

/**
 * Builds the 62 target grid, first bearing at [startYawDegrees].
 *
 * The bearing is absolute, not relative to the walk: the plan is anchored on
 * the bearing the walk starts facing, so every target carries the compass
 * heading to aim at. The pose sidecars stay relative to the same start, which
 * is what lets the stitch put the start bearing in the middle of the sphere.
 */
fun extendedReferencePlan(startYawDegrees: Float = 0f): SphereTargetPlan {
    val targets = ArrayList<SphereTarget>(62)
    val rings = ArrayList<IntRange>(REFERENCE_RINGS.size)
    REFERENCE_RINGS.forEach { (pitchDegrees, count) ->
        val start = targets.size
        val step = 360f / count
        repeat(count) { i ->
            targets += SphereTarget(
                yawDegrees = wrapDelta(startYawDegrees + i * step),
                pitchDegrees = pitchDegrees,
            )
        }
        rings += start..targets.lastIndex
    }
    val plan = SphereTargetPlan(targets, rings)
    Logging.i(
        "Extended reference plan: ${plan.size} targets, ${plan.rings.size} rings, " +
            "start yaw $startYawDegrees",
    )
    return plan
}

/** True when an aim sits on target [index] within [EXTENDED_TOLERANCE_DEGREES]. */
fun SphereTargetPlan.isOnTarget(
    yawDegrees: Float,
    pitchDegrees: Float,
    index: Int,
): Boolean {
    val target = targets[index]
    return abs(wrapDelta(yawDegrees - target.yawDegrees)) <= EXTENDED_TOLERANCE_DEGREES &&
        abs(pitchDegrees - target.pitchDegrees) <= EXTENDED_TOLERANCE_DEGREES
}

/** Signed shortest yaw turn from [fromYawDegrees] to [index]. Right is positive. */
fun SphereTargetPlan.turnTo(fromYawDegrees: Float, index: Int): Float =
    wrapDelta(targets[index].yawDegrees - fromYawDegrees)

/** Pitch move from [fromPitchDegrees] to [index]. Negative means tilt up. */
fun SphereTargetPlan.tiltTo(fromPitchDegrees: Float, index: Int): Float =
    targets[index].pitchDegrees - fromPitchDegrees

/** True when [index] sits at a cap rather than on a ring. */
fun SphereTargetPlan.isCap(index: Int): Boolean =
    abs(targets[index].pitchDegrees) > CAP_PITCH_DEGREES

/** The frame the 62 grid was proven at: the Python lab's H_FOV by V_FOV. */
private const val REFERENCE_WIDTH_FOV_DEGREES = 49f
private const val REFERENCE_HEIGHT_FOV_DEGREES = 63f

/** How far a lens may sit from the reference frame and still be its lens. */
private const val REFERENCE_FOV_BAND = 0.15f

/**
 * True when [fieldOfView] is close enough to the frame the 62 grid was proven
 * at that the grid can be walked without gaps.
 *
 * The grid spaces its ring bearings thirty six degrees apart, which leaves room
 * inside a 49 degree wide frame and nothing inside a narrower one. On a lens
 * that sees less than that the reference grid would ask for frames that do not
 * overlap, so the screen falls back to the plan laid out from the lens's own
 * field of view instead.
 */
fun matchesReferenceFieldOfView(fieldOfView: FieldOfView): Boolean {
    fun within(value: Float, reference: Float): Boolean =
        abs(value - reference) <= reference * REFERENCE_FOV_BAND
    return within(fieldOfView.horizontalDegrees, REFERENCE_WIDTH_FOV_DEGREES) &&
        within(fieldOfView.verticalDegrees, REFERENCE_HEIGHT_FOV_DEGREES)
}
