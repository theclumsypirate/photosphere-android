package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData

/**
 * Which target the reticle is working on, when targets may be shot in any order.
 *
 * The plan still has an order — rings swept boustrophedon, so the walk never
 * doubles back — and by default the active target follows it. But like Street
 * View, any marker can be shot: aim squarely at a different uncaptured dot and
 * it becomes the active target. That is what lets a user fill a gap they can
 * see, dodge a person walking through the next frame, or simply shoot the
 * sphere in whatever order their feet prefer, without the app insisting on its
 * own sequence.
 *
 * Pure: the capture loop hands in the attitude and the captured set, so the
 * rules are unit-testable without a device.
 */
object TargetSelection {

    /**
     * How close the aim must come to another uncaptured target to claim it.
     *
     * Comfortably outside the alignment gate's own 2° (so a claim happens
     * before the dwell could start) and far inside the 25°+ spacing between
     * neighbouring targets (so it can never be ambiguous which one is meant).
     */
    const val CLAIM_DEGREES: Float = 6f

    /**
     * How much nearer the aim must be to a different target than to the active
     * one before the claim wins.
     *
     * Without this, sensor noise (±0.5° at 50 Hz) flips the active index back
     * and forth when the aim sits between two dots — every flip restarts the
     * overlay's focus animation and the dwell, which reads as flicker.
     */
    const val CLAIM_HYSTERESIS_DEGREES: Float = 1.5f

    /**
     * The head start the plan's own next target gets when a shot hands over.
     *
     * After a shot the two neighbours on the ring are about equally far away,
     * and choosing between them on a fraction of a degree would flip the
     * direction of the sweep at random. The bias keeps the walk on the plan's
     * path unless a different gap is genuinely closer.
     */
    const val SUCCESSOR_BIAS_DEGREES: Float = 12f

    /**
     * The target to keep working on at this sample.
     *
     * [active] stays active unless the aim has come within [CLAIM_DEGREES] of
     * a different uncaptured target, which then takes over. A captured or
     * missing [active] hands over as [afterCapture] would.
     *
     * @return the active index, or null once every target is captured
     */
    fun select(
        plan: SphereTargetPlan,
        orientation: OrientationData,
        captured: Set<Int>,
        active: Int,
    ): Int? {
        if (active !in plan.targets.indices || active in captured) {
            return afterCapture(plan, orientation, captured, active)
        }
        var claimed = -1
        var claimedDistance = CLAIM_DEGREES
        for (index in plan.targets.indices) {
            if (index == active || index in captured) continue
            val distance = SphereProjection.angularDistanceDegrees(orientation, plan[index])
            if (distance < claimedDistance) {
                claimed = index
                claimedDistance = distance
            }
        }
        if (claimed < 0) return active
        // Only a target the aim is clearly nearer to than the active one: a
        // user who is already on the active target is never pulled off it, and
        // the hysteresis gap stops sensor noise flipping the two when the aim
        // sits halfway between them.
        val activeDistance = SphereProjection.angularDistanceDegrees(orientation, plan[active])
        return if (claimedDistance + CLAIM_HYSTERESIS_DEGREES < activeDistance) claimed else active
    }

    /**
     * The target to hand over to once [previous] is no longer workable —
     * usually because it was just shot.
     *
     * The nearest uncaptured target wins, with the plan's successor of
     * [previous] given [SUCCESSOR_BIAS_DEGREES] of head start.
     *
     * @return the next index, or null once every target is captured
     */
    fun afterCapture(
        plan: SphereTargetPlan,
        orientation: OrientationData,
        captured: Set<Int>,
        previous: Int,
    ): Int? {
        val successor = planSuccessor(plan.size, captured, previous) ?: return null
        var best = successor
        var bestScore =
            SphereProjection.angularDistanceDegrees(orientation, plan[successor]) -
                SUCCESSOR_BIAS_DEGREES
        for (index in plan.targets.indices) {
            if (index == successor || index in captured) continue
            val score = SphereProjection.angularDistanceDegrees(orientation, plan[index])
            if (score < bestScore) {
                best = index
                bestScore = score
            }
        }
        return best
    }

    /**
     * The first uncaptured index after [previous] in plan order, wrapping
     * round to the start; null when every index in `0 until size` is captured.
     */
    internal fun planSuccessor(size: Int, captured: Set<Int>, previous: Int): Int? {
        if (size <= 0) return null
        val start = (previous + 1).coerceIn(0, size)
        for (step in 0 until size) {
            val index = (start + step) % size
            if (index !in captured) return index
        }
        return null
    }
}
