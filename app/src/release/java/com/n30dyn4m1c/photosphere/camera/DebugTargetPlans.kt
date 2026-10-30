package com.n30dyn4m1c.photosphere.camera

import android.content.Context

/**
 * Release counterpart of the debug-only target plan picker.
 *
 * The real implementation lives in the debug source set; this stub exists
 * only so the main sources compile in a release build, where every call
 * site is behind `BuildConfig.DEBUG` and never executes. It exposes the
 * minimal API surface the main code references.
 */
object DebugTargetPlans {

    enum class Type(val label: String, val description: String) {
        FULL_SPHERE("Full Sphere", "Complete 360×180° sphere (default)"),
        HORIZON_RING("Horizon Ring", "Single ring at eye level, black poles"),
    }

    fun getSavedType(context: Context): Type = Type.FULL_SPHERE

    fun setSavedType(context: Context, type: Type) = Unit

    fun create(
        type: Type,
        fov: FieldOfView,
        startYaw: Float = 0f,
        freezeElevation: Float = 0f,
        surroundingElevation: Float = 15f,
    ): SphereTargetPlan =
        SphereTargetPlan.createForFieldOfView(
            startYawDegrees = startYaw,
            fieldOfView = fov,
            scope = SphereCaptureScope.Sphere,
        )
}
