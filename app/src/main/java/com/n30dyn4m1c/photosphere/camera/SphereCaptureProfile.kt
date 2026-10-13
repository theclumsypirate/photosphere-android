@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@file:SuppressLint("UnsafeOptInUsageError")

package com.n30dyn4m1c.photosphere.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * How one sphere is captured, decided once per device.
 *
 * A photosphere's seams are exposure and colour seams: if every frame re-runs
 * the camera's auto-exposure and auto-white-balance on its own, three frames of
 * a room and one of a window come back in three different brightnesses and
 * colour temperatures, and no amount of blending can hide that. So the session
 * holds them all — AE lock pins the ISO and shutter speed the HAL converged on
 * when the preview started, AWB lock pins the colour temperature, and focus is
 * locked on the scene (by tap, or the first scene's centre) so nothing
 * re-focuses mid-sweep. This is the same model GCam's Photosphere uses: lock
 * exposure and focus, then sweep.
 *
 * The one rule the locks observe: they are applied only *after* the 3A has
 * converged on the scene the phone is pointed at. Locking from the stream's
 * first frame pins the exposure at the HAL's stream-start defaults — a short
 * exposure and low ISO chosen for a bright scene — which leaves a dark scene
 * permanently black: the viewfinder never brightens and every frame records a
 * black image. The session starts unlocked, waits for AE/AWB to converge (the
 * exposure ramps up over the first moments in low light), and locks to the
 * values they settled on. See [applyStillImageOptions] and
 * PhotoSphereCameraScreen's convergence callback.
 *
 * The one thing that varies per device is *how* focus is fixed:
 *
 * - [FocusMode.FOCUS_POINT] — the default for any lens that can focus. The
 *   session runs `AF_MODE_AUTO`, and the user taps the viewfinder to aim focus
 *   at a scene region; the sweep converges there and the lens holds that
 *   distance for the rest of the session, exactly like a regular camera's
 *   tap-to-focus lock. Until the first tap the lens holds the centre of the
 *   first scene it saw. This is what keeps frames sharp: focus is *locked on
 *   what the scene actually is* rather than parked at infinity, which reads as
 *   blur on any scene with something nearer than the horizon.
 * - [FocusMode.FIXED_FOCUS] — the lens physically has no focus mechanism
 *   (`LENS_INFO_MINIMUM_FOCUS_DISTANCE` is 0); `AF_MODE_OFF` is all there is.
 */
data class SphereCaptureProfile(
    val focusMode: FocusMode,
    val aeLockSupported: Boolean,
    val awbLockSupported: Boolean,
    val opticalStabilizationSupported: Boolean,
)

/**
 * What focus does across a session.
 *
 * Kept top-level rather than nested so the screen can pattern-match on it
 * without qualification.
 */
enum class FocusMode {
    /** The lens has no focus mechanism; `AF_MODE_OFF` is mandatory. */
    FIXED_FOCUS,

    /**
     * Tap-to-focus: `AF_MODE_AUTO`, triggered where the user taps and then held.
     *
     * The lens stays locked on that distance until the next tap — no frame
     * re-focuses mid-sweep, so a sphere's frames keep one focal plane, and the
     * shots are sharp on the scene instead of parked at infinity.
     */
    FOCUS_POINT,
}

/**
 * The focus strategy for a lens.
 *
 * [minimumFocusDistance] is `LENS_INFO_MINIMUM_FOCUS_DISTANCE`: 0 means the
 * lens is fixed-focus and has nothing to lock; anything else means it can
 * focus, and tap-to-focus is the way to lock it on the scene.
 */
internal fun resolveFocusMode(minimumFocusDistance: Float?): FocusMode =
    if (minimumFocusDistance != null && minimumFocusDistance <= 0f) {
        FocusMode.FIXED_FOCUS
    } else {
        FocusMode.FOCUS_POINT
    }

/**
 * Whether AE/AWB locks are worth setting.
 *
 * The HAL reports support via `CONTROL_AE_LOCK_AVAILABLE`/`CONTROL_AWB_LOCK_AVAILABLE`.
 * Missing means the capability is unknown, not absent — every shipping camera
 * supports these, so an unknown characteristic is treated as supported (an
 * unsupported key is simply ignored by the HAL).
 */
internal fun resolveLockSupport(available: Boolean?): Boolean = available ?: true

/** Whether the lens offers hardware optical stabilisation. */
internal fun resolveOpticalStabilization(stabilizationModes: IntArray?): Boolean =
    stabilizationModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

/**
 * The capture profile for this device's rear camera.
 *
 * [cameraId] pins the profile to a specific lens (the one that actually bound,
 * once known); without it the profile is resolved for the lens the device
 * profile would bind, which is the right answer until a fallback changes the
 * lens.
 *
 * Deliberately does not throw: a camera that will not describe itself gets the
 * most conservative profile (locks everything, tap-to-focus on the first scene)
 * rather than failing capture.
 */
internal fun resolveSphereCaptureProfile(
    context: Context,
    profile: SphereDeviceProfile,
    cameraId: String? = null,
): SphereCaptureProfile {
    val characteristics = runCatching {
        if (cameraId != null) {
            context.getSystemService(CameraManager::class.java)
                ?.getCameraCharacteristics(cameraId)
        } else {
            backCameraCharacteristics(context, profile)
        }
    }.getOrNull()
    return SphereCaptureProfile(
        focusMode = resolveFocusMode(
            minimumFocusDistance =
                characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
        ),
        aeLockSupported = resolveLockSupport(
            characteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
        ),
        awbLockSupported = resolveLockSupport(
            characteristics?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)
        ),
        opticalStabilizationSupported = resolveOpticalStabilization(
            characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ),
    ).also { profile ->
        Log.i(
            Logging.TAG,
            "Capture profile for ${cameraId ?: "default lens"}: focus=${profile.focusMode} " +
                "aeLock=${profile.aeLockSupported} awbLock=${profile.awbLockSupported} " +
                "ois=${profile.opticalStabilizationSupported}",
        )
    }
}

/**
 * The back camera that captures the widest field of view, or null if none can
 * be found.
 *
 * A photosphere is captured faster the wider each frame is, so the widest
 * back-facing lens wins — on a Galaxy S23 that is the 12 MP ultrawide rather
 * than the 50 MP main, which roughly halves the number of frames a sphere
 * needs. Logical multi-cameras are skipped (they report their widest physical
 * lens but are not individually bindable), and depth/macro sensors are
 * excluded by requiring a real sensor behind the lens.
 */
internal fun widestBackCameraId(context: Context): String? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    var bestId: String? = null
    var bestFocal = Float.MAX_VALUE
    for (id in manager.cameraIdList) {
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            ?: continue
        if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
            CameraCharacteristics.LENS_FACING_BACK
        ) {
            continue
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            characteristics.physicalCameraIds.isNotEmpty()
        ) {
            continue
        }
        val focal = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
            ?: continue
        val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: continue
        if (array.width.toLong() * array.height < MIN_WIDE_SENSOR_PIXELS) continue
        if (focal < bestFocal) {
            bestFocal = focal
            bestId = id
        }
    }
    return bestId
}

/** Smallest sensor a "wide" lens may have before it is a depth or macro toy. */
private const val MIN_WIDE_SENSOR_PIXELS = 6_000_000L

/** The ID of the default back camera (the main lens), or null if none. */
internal fun defaultBackCameraId(context: Context): String? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    return manager.cameraIdList.firstOrNull { id ->
        runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    }
}

/**
 * The back camera to capture with, honouring the device profile's preference.
 *
 * Sharpness-first profiles pick the main lens; speed-first profiles pick the
 * widest one. Either way, a missing or unbindable preference falls back to the
 * main camera.
 */
internal fun captureBackCameraId(context: Context, profile: SphereDeviceProfile): String? =
    if (profile.preferWidestCamera) {
        widestBackCameraId(context) ?: defaultBackCameraId(context)
    } else {
        defaultBackCameraId(context)
    }

/** Characteristics of the chosen rear camera, or null if none can be queried. */
private fun backCameraCharacteristics(context: Context, profile: SphereDeviceProfile): CameraCharacteristics? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    val id = captureBackCameraId(context, profile) ?: return null
    return runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
}

/**
 * Applies the focus strategy to a use case's requests.
 *
 * This is deliberately applied to *both* the Preview and ImageCapture builders:
 * the preview is the session's repeating request and the stills must agree with
 * it. AE and AWB locks are *not* set here — see [applyStillImageOptions] for
 * where they live and why they are deferred.
 */
internal fun applySphereCaptureOptions(
    extender: Camera2Interop.Extender<*>,
    profile: SphereCaptureProfile,
) {
    when (profile.focusMode) {
        FocusMode.FIXED_FOCUS -> {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
        }
        FocusMode.FOCUS_POINT -> {
            // AUTO + a tap trigger is how a regular camera locks focus on an
            // area: the sweep converges and the lens holds that distance until
            // the next trigger, so no frame re-focuses mid-sweep.
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
            )
        }
    }
}

/**
 * Applies options that belong on the still-image requests only.
 *
 * OIS is the lens steadying the shot; it belongs on the capture, not the
 * preview. AE and AWB locks belong here too, but only as a safety net: the
 * session locks them on the repeating request once the 3A has converged, and a
 * still fired mid-convergence must not re-meter on its own — the lock pins it
 * to whatever the converging repeating request has reached, so a premature
 * capture cannot walk away from the exposure the viewfinder is showing. The
 * initial convergence itself is left to run, because locking the very first
 * request freezes the exposure at the HAL's stream-start defaults and a dark
 * scene would never brighten.
 */
internal fun applyStillImageOptions(
    extender: Camera2Interop.Extender<*>,
    profile: SphereCaptureProfile,
) {
    // AE lock holds the ISO and shutter speed, AWB lock holds the colour
    // temperature; together they keep every frame of the sphere identical in
    // brightness and tint.
    if (profile.aeLockSupported) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
    }
    if (profile.awbLockSupported) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
    }
    if (profile.opticalStabilizationSupported) {
        extender.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
    }
}
