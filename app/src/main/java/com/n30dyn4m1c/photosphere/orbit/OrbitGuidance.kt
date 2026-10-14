package com.n30dyn4m1c.photosphere.orbit

import kotlin.math.abs

/**
 * Spoken and sounded guidance for the orbit walk.
 *
 * Same vocabulary as the sphere capture (beeps that quicken as the aim
 * closes in, a haptic tick as the dwell lands, a voice line per target),
 * computed from an [OrbitPlan] instead of a sphere plan. The sounding
 * itself stays in [GuidanceFeedback][com.n30dyn4m1c.photosphere.camera.GuidanceFeedback];
 * this file only decides what to say and when.
 */
object OrbitGuidance {

    /**
     * The voice line for a fresh target: which angle it is and which way
     * to turn, or to hold still when already on it.
     */
    fun announceFor(plan: OrbitPlan, yawDegrees: Float, index: Int): String {
        val turn = plan.turnTo(yawDegrees, index)
        val position = "Angle ${index + 1} of ${plan.angles.size}."
        return if (plan.isOnTarget(yawDegrees, index)) {
            "$position Hold still."
        } else if (turn > 0) {
            "$position Turn right ${turn.toInt()} degrees."
        } else {
            "$position Turn left ${(-turn).toInt()} degrees."
        }
    }

    /** Short on-screen hint mirroring the voice line. */
    fun hintFor(plan: OrbitPlan, yawDegrees: Float, index: Int): String {
        val turn = plan.turnTo(yawDegrees, index)
        return when {
            plan.isOnTarget(yawDegrees, index) -> "Hold still…"
            turn > 0 -> "Turn right ${turn.toInt()}°"
            else -> "Turn left ${(-turn).toInt()}°"
        }
    }

    /** Absolute aim error for the beep loop and the dwell gate. */
    fun distanceDegrees(plan: OrbitPlan, yawDegrees: Float, index: Int): Float =
        abs(plan.turnTo(yawDegrees, index))
}
