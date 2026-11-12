package com.n30dyn4m1c.photosphere.camera

import android.content.Context

/**
 * Release stub for the debug-only target plan picker.
 *
 * The real implementation lives in the debug source set. This exists only
 * so main sources compile in release builds where call sites are behind
 * `BuildConfig.DEBUG` and never execute.
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
            SphereCaptureScope.Sphere,
        ),
        HORIZON_RING(
            "Horizon Ring",
            "Walk a single ring around the horizon. Fast, but no zenith/nadir.",
            SphereCaptureScope.Ring,
        ),
        TINY_PLANET(
            "Tiny Planet",
            "Stereographic projection: walk a ring, render as little planet.",
            SphereCaptureScope.Ring,
        ),
        ASTEROID(
            "Asteroid",
            "Inverted tiny planet: walk a ring, render as asteroid.",
            SphereCaptureScope.Ring,
        ),
        FREEZE_FRAME(
            "Freeze Frame",
            "Single ring, but each frame freezes the previous — for parallax tests.",
            SphereCaptureScope.Ring,
        ),
        SURROUNDING(
            "Surrounding",
            "Three rings: horizon, +30°, -30°. Good coverage without poles.",
            SphereCaptureScope.Sphere,
        ),
        THREE_STEPS(
            "Three Steps",
            "Three rings at fixed elevations. Debugging ring transitions.",
            SphereCaptureScope.Sphere,
        ),
        FORWARD_OVERTURN(
            "Forward Overturn",
            "Single ring with deliberate overturn. Tests roll handling.",
            SphereCaptureScope.Ring,
        ),
    }

    fun getSavedType(context: Context): Type = Type.FULL_SPHERE

    fun setSavedType(context: Context, type: Type) = Unit

    fun fullSphere(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan =
        SphereTargetPlan.createForFieldOfView(
            startYawDegrees = startYaw,
            fieldOfView = fov,
            scope = SphereCaptureScope.Sphere,
        )
}
