package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import com.n30dyn4m1c.photosphere.camera.SphereCaptureScope

/**
 * Debug-only target plan picker.
 *
 * Changes the capture pattern: full sphere, horizon ring, tiny planet,
 * asteroid, freeze frame, surrounding, three steps, or forward overturn.
 * The user still aims manually; this only changes which targets the plan
 * lays out and in what order.
 */
object DebugTargetPlans {

    enum class Type(
        val label: String,
        val description: String,
        val scope: SphereCaptureScope,
    ) {
        FULL_SPHERE(
            "Full Sphere",
            "Walk a complete sphere: rings from horizon to both poles.",
            SphereCaptureScope.Sphere
        ),
        HORIZON_RING(
            "Horizon Ring",
            "Walk a single ring around the horizon. Fast, but no zenith/nadir.",
            SphereCaptureScope.Ring
        ),
        TINY_PLANET(
            "Tiny Planet",
            "Stereographic projection: walk a ring, render as little planet.",
            SphereCaptureScope.Ring
        ),
        ASTEROID(
            "Asteroid",
            "Inverted tiny planet: walk a ring, render as asteroid.",
            SphereCaptureScope.Ring
        ),
        FREEZE_FRAME(
            "Freeze Frame",
            "Single ring, but each frame freezes the previous — for parallax tests.",
            SphereCaptureScope.Ring
        ),
        SURROUNDING(
            "Surrounding",
            "Three rings: horizon, +30°, -30°. Good coverage without poles.",
            SphereCaptureScope.Sphere
        ),
        THREE_STEPS(
            "Three Steps",
            "Three rings at fixed elevations. Debugging ring transitions.",
            SphereCaptureScope.Sphere
        ),
        FORWARD_OVERTURN(
            "Forward Overturn",
            "Single ring with deliberate overturn. Tests roll handling.",
            SphereCaptureScope.Ring
        );

        companion object {
            private const val PREFS_KEY = "debug_target_plan_type"

            fun getSavedType(context: Context): Type {
                val name = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
                    .getString(PREFS_KEY, FULL_SPHERE.name)
                return Type.valueOf(name ?: FULL_SPHERE.name)
            }

            fun setSavedType(context: Context, type: Type) {
                context.getSharedPreferences("debug", Context.MODE_PRIVATE)
                    .edit().putString(PREFS_KEY, type.name).apply()
            }
        }
    }
}
