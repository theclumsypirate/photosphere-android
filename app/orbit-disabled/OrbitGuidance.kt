package com.n30dyn4m1c.photosphere.orbit

import android.content.Context
import com.n30dyn4m1c.photosphere.R
import kotlin.math.abs

/**
 * Spoken guidance for the orbit walk, in the exact words of the sphere
 * capture: "Next: to your left", with the angle position prepended so the
 * photographer knows where the walk stands.
 */
object OrbitGuidance {

    /**
     * The voice line for a fresh target, e.g. "Angle 3 of 12. Next: to
     * your right." Silent (centre) targets get no direction.
     */
    fun announceFor(context: Context, plan: OrbitPlan, yawDegrees: Float, index: Int): String {
        val position = "Angle ${index + 1} of ${plan.angles.size}."
        val direction = directionPhrase(context, plan, yawDegrees, index) ?: return position
        return "$position " + context.getString(R.string.guidance_target, direction)
    }

    /** Short on-screen hint with the same words as the voice line. */
    fun hintFor(context: Context, plan: OrbitPlan, yawDegrees: Float, index: Int): String =
        announceFor(context, plan, yawDegrees, index)

    /** Absolute aim error for the beep loop and the dwell gate. */
    fun distanceDegrees(plan: OrbitPlan, yawDegrees: Float, index: Int): Float =
        abs(plan.turnTo(yawDegrees, index))

    private fun directionPhrase(
        context: Context,
        plan: OrbitPlan,
        yawDegrees: Float,
        index: Int,
    ): String? {
        if (plan.isOnTarget(yawDegrees, index)) return null
        val turn = plan.turnTo(yawDegrees, index)
        return context.getString(
            if (turn > 0) R.string.guidance_right else R.string.guidance_left,
        )
    }
}
