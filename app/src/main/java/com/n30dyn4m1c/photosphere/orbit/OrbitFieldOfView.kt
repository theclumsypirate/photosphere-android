package com.n30dyn4m1c.photosphere.orbit

import android.content.Context
import android.hardware.camera2.CameraManager
import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Field of view read off the bound lens, orbit-local copy.
 *
 * Same physics as the normal camera (sensor size over focal length),
 * re-implemented here so orbit never touches the normal camera files.
 * Null when the lens will not describe itself; callers fall back to
 * the fixed 36 target grid.
 *
 * @param hFovDegrees horizontal field of view.
 * @param vFovDegrees vertical field of view.
 */
data class OrbitFieldOfView(
    val hFovDegrees: Float,
    val vFovDegrees: Float,
)

/**
 * Reads [cameraId] lens geometry through the Camera2 manager.
 *
 * Picks the shortest focal length and the physical sensor size, the
 * same combination the normal camera reconciles against calibration.
 */
fun orbitCameraFieldOfView(context: Context, cameraId: String): OrbitFieldOfView? {
    val characteristics = runCatching {
        context.getSystemService(CameraManager::class.java)
            ?.getCameraCharacteristics(cameraId)
    }.getOrNull() ?: return null
    val focal = characteristics
        .get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.filter { it > 0f }?.minOrNull() ?: return null
    val size = characteristics
        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        ?: return null
    return OrbitFieldOfView(
        hFovDegrees = 2f * Math.toDegrees(atan(size.width / (2f * focal))).toFloat(),
        vFovDegrees = 2f * Math.toDegrees(atan(size.height / (2f * focal))).toFloat(),
    ).also {
        Logging.i("Orbit lens FOV: ${it.hFovDegrees}x${it.vFovDegrees}")
    }
}

/**
 * Pitch grid sized to the lens, like the normal camera adapts its rings.
 *
 * Rings step by vertical FOV minus overlap so vertical neighbours share
 * a third of the frame; the outer ring always reaches past the poles.
 * Each ring holds as many yaw targets as fit in 360 at the horizontal
 * spacing, widened by 1/cos(elevation) as rings shrink. A wide lens
 * (ultrawide ~100 degrees) lands around 6 per ring, a narrow one more.
 */
fun orbitPitchPlanForFieldOfView(fieldOfView: OrbitFieldOfView): OrbitPitchPlan {
    val hPlanned = fieldOfView.hFovDegrees * 0.9f
    val vPlanned = fieldOfView.vFovDegrees * 0.9f
    val overlap = 0.35f
    val step = vPlanned * (1f - overlap)
    val cap = min(75f, max(step, 90f - vPlanned / 2f + step / 2f))
    val upward = mutableListOf<Float>()
    var elevation = step
    while (elevation < cap - step / 2f) {
        upward += elevation
        elevation += step
    }
    upward += cap
    val elevations = listOf(0f) + upward + upward.map { -it }
    val spacing = hPlanned * (1f - overlap)
    val rings = elevations.mapIndexed { ringIndex, elev ->
        val shrink = cos(Math.toRadians(elev.toDouble())).toFloat()
        val ringSpacing = if (shrink <= 1e-3f) 360f else spacing / shrink
        val count = (360f / ringSpacing).roundToInt().coerceAtLeast(1)
        ringTargets(ringIndex, -elev, count)
    } + listOf(
        ringTargets(elevations.size, PITCH_RING_ZENITH, 1),
        ringTargets(elevations.size + 1, PITCH_RING_NADIR, 1),
    )
    return OrbitPitchPlan(rings, yawToleranceDegrees = 4f, pitchToleranceDegrees = 4f)
        .also { Logging.i("Orbit adaptive plan: ${it.size} targets") }
}
