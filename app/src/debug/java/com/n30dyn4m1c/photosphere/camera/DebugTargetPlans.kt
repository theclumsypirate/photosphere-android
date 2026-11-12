package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan.Companion.DEFAULT_EQUATOR_SPACING_DEGREES
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan.Companion.DEFAULT_RING_ELEVATIONS
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan.Companion.DEFAULT_TARGET_OVERLAP_FRACTION
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan.Companion.FIELD_OF_VIEW_SAFETY_FACTOR
import com.n30dyn4m1c.photosphere.camera.SphereCaptureScope
import com.n30dyn4m1c.photosphere.camera.FieldOfView
import com.n30dyn4m1c.photosphere.camera.SphereTarget
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Debug-only target plan picker.
 *
 * Changes the capture pattern: full sphere, horizon ring, tiny planet,
 * asteroid, freeze frame, surrounding, three steps, or forward overturn.
 * The user still aims manually; this only changes which targets the plan
 * lays out and in what order.
 */
object DebugTargetPlans {

    private const val PREFS = "debug_target_plans"
    private const val KEY_PLAN_TYPE = "plan_type"

    /** Reads the saved debug plan type, defaulting to FULL_SPHERE. */
    fun getSavedType(context: Context): Type {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PLAN_TYPE, Type.FULL_SPHERE.name) ?: Type.FULL_SPHERE.name
        return Type.valueOf(name)
    }

    /** Saves the debug plan type. */
    fun setSavedType(context: Context, type: Type) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAN_TYPE, type.name).apply()
        Logging.i("Debug target plan: ${type.label}")
    }

    /**
     * Standard full sphere: horizon ring, then up to zenith, then down to nadir.
     * Matches the production [SphereTargetPlan.createForFieldOfView].
     */
    fun fullSphere(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan =
        SphereTargetPlan.createForFieldOfView(
            startYawDegrees = startYaw,
            fieldOfView = fov,
            scope = SphereCaptureScope.Sphere,
        )

    /**
     * Single horizon ring only (no zenith/nadir).
     * Faster capture, equirectangular output with black poles.
     */
    fun horizonRing(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan =
        SphereTargetPlan.createForFieldOfView(
            startYawDegrees = startYaw,
            fieldOfView = fov,
            scope = SphereCaptureScope.Ring,
        )

    /**
     * "Tiny Planet" optimized: denser sampling around the horizon and nadir,
     * sparse at zenith. The stereo-graphic projection used for tiny planets
     * stretches the nadir heavily, so more frames there reduce artifacts.
     */
    fun tinyPlanet(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan {
        val planned = FieldOfView(
            horizontalDegrees = fov.horizontalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
            verticalDegrees = fov.verticalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
        )
        val spacing = planned.horizontalDegrees * (1f - DEFAULT_TARGET_OVERLAP_FRACTION)

        // Extra rings near nadir (-90°) for tiny planet nadir stretch
        val elevations = listOf(
            0f,                    // horizon
            20f, 40f, 60f, 75f,    // upward (zenith at 75° cap)
            -20f, -40f, -60f, -75f, -85f, -88f  // downward - extra near nadir
        )

        return createUnchecked(startYaw, elevations, spacing)
    }

    /**
     * "Asteroid" / "Little Planet" variant: starts at nadir, works upward.
     * User points down first, then sweeps up - matches Insta360's asteroid-north_pole
     * intent but handheld.
     */
    fun asteroid(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan {
        val planned = FieldOfView(
            horizontalDegrees = fov.horizontalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
            verticalDegrees = fov.verticalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
        )
        val spacing = planned.horizontalDegrees * (1f - DEFAULT_TARGET_OVERLAP_FRACTION)

        // Start at nadir, work up to zenith
        val elevations = listOf(
            -85f, -75f, -60f, -40f, -20f,
            0f, 20f, 40f, 60f, 75f
        )

        return createUnchecked(startYaw, elevations, spacing)
    }

    /**
     * "Freeze Frame" style: sparse horizon, dense at one elevation.
     * User captures a horizontal band at a specific pitch (e.g., eye level).
     * Mimics Insta360's freeze-normal.json intent.
     */
    fun freezeFrame(
        fov: FieldOfView,
        centerElevation: Float = 0f,
        bandHeight: Float = 30f,
        startYaw: Float = 0f
    ): SphereTargetPlan {
        val planned = FieldOfView(
            horizontalDegrees = fov.horizontalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
            verticalDegrees = fov.verticalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
        )
        val spacing = planned.horizontalDegrees * (1f - DEFAULT_TARGET_OVERLAP_FRACTION)

        // Just 3 rings: center, slightly up, slightly down
        val elevations = listOf(
            centerElevation,
            (centerElevation + bandHeight).coerceAtMost(75f),
            (centerElevation - bandHeight).coerceAtLeast(-75f)
        ).distinct()

        return createUnchecked(startYaw, elevations, spacing)
    }

    /**
     * "Surrounding" / orbit: single ring at fixed off-horizon elevation.
     * User walks in a circle at constant pitch. Matches Insta360 surrounding-normal.json.
     */
    fun surrounding(
        fov: FieldOfView,
        elevation: Float = 15f,
        startYaw: Float = 0f
    ): SphereTargetPlan {
        val planned = FieldOfView(
            horizontalDegrees = fov.horizontalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
            verticalDegrees = fov.verticalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
        )
        val spacing = planned.horizontalDegrees * (1f - DEFAULT_TARGET_OVERLAP_FRACTION)

        return createUnchecked(startYaw, listOf(elevation), spacing)
    }

    /**
     * Three-step: three distinct horizontal bands (low, mid, high).
     * User shoots horizon, then tilts up, then tilts down. Matches three_steps-normal.json.
     */
    fun threeSteps(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan {
        val planned = FieldOfView(
            horizontalDegrees = fov.horizontalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
            verticalDegrees = fov.verticalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
        )
        val spacing = planned.horizontalDegrees * (1f - DEFAULT_TARGET_OVERLAP_FRACTION)

        val elevations = listOf(0f, 45f, -45f)
        return createUnchecked(startYaw, elevations, spacing)
    }

    /**
     * Forward overturn (flip): starts at horizon, goes to zenith, then nadir.
     * User tilts phone up continuously then down. Matches forward_overturn-normal.json.
     */
    fun forwardOverturn(fov: FieldOfView, startYaw: Float = 0f): SphereTargetPlan {
        val planned = FieldOfView(
            horizontalDegrees = fov.horizontalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
            verticalDegrees = fov.verticalDegrees * FIELD_OF_VIEW_SAFETY_FACTOR,
        )
        val spacing = planned.horizontalDegrees * (1f - DEFAULT_TARGET_OVERLAP_FRACTION)

        // Sweep from horizon up to zenith, then down to nadir
        val elevations = mutableListOf<Float>()
        elevations += 0f
        var e = 15f
        while (e <= 75f) { elevations += e; e += 15f }
        e = -15f
        while (e >= -75f) { elevations += e; e -= 15f }

        return createUnchecked(startYaw, elevations, spacing)
    }

    /** All available debug plan types for a UI picker. */
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
    }

    /**
     * Internal copy of [SphereTargetPlan.Companion.createUnchecked] for debug use.
     */
    private fun createUnchecked(
        startYawDegrees: Float,
        ringElevations: List<Float>,
        equatorSpacingDegrees: Float
    ): SphereTargetPlan {
        val targets = ArrayList<SphereTarget>()
        val rings = ArrayList<IntRange>(ringElevations.size)
        ringElevations.forEachIndexed { ringIndex, elevation ->
            val ring = ringYaws(startYawDegrees, elevation, equatorSpacingDegrees)
            val ordered = if (ringIndex % 2 == 0) ring else ring.reversed()
            val start = targets.size
            ordered.forEach { yaw -> targets += SphereTarget.atElevation(yaw, elevation) }
            if (targets.size > start) rings += start..targets.lastIndex
        }
        return SphereTargetPlan(targets, rings)
    }

    private fun ringYaws(
        startYawDegrees: Float,
        elevationDegrees: Float,
        equatorSpacingDegrees: Float
    ): List<Float> {
        val shrink = cos(Math.toRadians(elevationDegrees.toDouble())).toFloat()
        val spacing = if (shrink <= 1e-3f) 360f else equatorSpacingDegrees / shrink
        val count = (360f / spacing).roundToInt().coerceAtLeast(1)
        val step = 360f / count
        return List(count) { normalizeDegrees(startYawDegrees + it * step) }
    }
}