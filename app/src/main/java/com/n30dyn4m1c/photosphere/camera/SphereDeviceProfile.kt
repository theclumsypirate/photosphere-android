package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.stitching.NightFrameMerger
import com.n30dyn4m1c.photosphere.stitching.PivotModel
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Per-device tuning, decided once per phone.
 *
 * The generic defaults suit a mid-range device; this profile is the single
 * place a specific phone is tuned. It picks which lens to capture with, caps
 * the still-capture size to what the stitch can actually use, sets how many
 * frames each target is burst before the sharpest is kept, and decides how
 * much resolution and sharpening the stitch ends with.
 *
 * The device detection keys off the model, so a new flagship is a few lines
 * here rather than a sprinkling of `if (Build.MODEL == …)` across the camera
 * and stitching code.
 */
data class SphereDeviceProfile(
    /**
     * Whether to prefer the back camera with the widest field of view.
     *
     * True trades sharpness for speed — a wide lens shoots the sphere in far
     * fewer frames. False uses the main camera, which is the sharper lens.
     */
    val preferWidestCamera: Boolean,
    /** Long edge the still captures are capped to, in pixels (12 MP 4:3 = 4000). */
    val captureMaxLongEdgePx: Int,
    /** Frames each target is burst before the sharpest is kept. */
    val burstPerTarget: Int,
    /** Long edge each frame is decoded to before stitching. */
    val stitchMaxInputDimension: Int,
    /** Width cap for the finished sphere; the canvas is always half as tall. */
    val stitchMaxOutputWidth: Int,
    /** Unsharp-mask strength on the finished canvas, 0 = off. */
    val unsharpAmount: Float,
    /**
     * How the phone is assumed to be swung around: how far the lens sits from
     * the axis the user turns about, and how far away the scene is taken to be.
     * See [PivotModel] — this is the correction for pivoting around your body
     * rather than around the camera.
     */
    val pivot: PivotModel,
    /** Night mode 360 configuration. Null = night mode not supported on this device. */
    val nightMode: NightFrameMerger.Config? = null,
    /** Whether the device's camera hardware supports night mode (queried via Camera2). */
    val nightModeSupported: Boolean = false,
) {
    companion object {
        /** Default night config for devices without specific tuning. Debug only. */
        private val NIGHT_DEFAULT: NightFrameMerger.Config? = if (BuildConfig.DEBUG) NightFrameMerger.Config(
            framesPerTarget = 8,
            maxExposureTimeMs = 250,
            maxIso = 1600,
            mergeMethod = NightFrameMerger.MergeMethod.WeightedAverage,
            alignMethod = NightFrameMerger.AlignMethod.ECC,
            minFramesForMerge = 4,
            alignDownscale = 0.5f,
            medianTileSize = 512,
        ) else null

        /**
         * The default: a mid-range phone with one usable back camera. The wider
         * lens is preferred because every extra frame is capture time and
         * sensor drift, and the stitch runs at the compact 1024 → 4096 profile
         * that a mid-range chip and memory envelope can chew through.
         */
        private val DEFAULT = SphereDeviceProfile(
            preferWidestCamera = true,
            captureMaxLongEdgePx = 4000,
            burstPerTarget = 2,
            stitchMaxInputDimension = 1024,
            stitchMaxOutputWidth = 4096,
            unsharpAmount = 0f,
            pivot = PivotModel.HandheldBodySwivel,
            nightMode = NIGHT_DEFAULT,
        )

        /**
         * Samsung Galaxy S23 / S23+ / S23 Ultra.
         *
         * Tuned for sharpness, because the hardware earns it:
         *
         * - **The 50 MP main is the capture lens** ([preferWidestCamera] is
         *   false). It is the sharpest camera on the phone — better glass, OIS,
         *   and a much larger sensor than the ultrawide — so a sphere on the
         *   main is notably crisper even at the same output size. It costs more
         *   frames (~33 instead of ~11), which is the price of sharpness.
         * - **Stills are capped at the 12 MP binned output**, which writes fast
         *   and is far more than the stitch reads; 50 MP stills would only slow
         *   the burst.
         * - **The stitch runs at 2000 → 6144.** Frames are decoded at a 2000 px
         *   long edge and the sphere is rendered up to 6144 wide (Google's
         *   Photo Sphere standard is 5376), so the main camera's detail reaches
         *   the finished photo instead of being thrown away at 4096.
         * - **Burst three and sharpen lightly.** Three frames per target make a
         *   sharp survivor more likely, and a gentle unsharp mask lifts the
         *   finished canvas without haloing the parts of the sphere that were
         *   never shot.
         */
        private val SAMSUNG_S23 = SphereDeviceProfile(
            preferWidestCamera = false,
            captureMaxLongEdgePx = 4000,
            burstPerTarget = 3,
            stitchMaxInputDimension = 2000,
            stitchMaxOutputWidth = 6144,
            unsharpAmount = 0.3f,
            pivot = PivotModel.HandheldBodySwivel,
            nightMode = if (BuildConfig.DEBUG) NightFrameMerger.Config(
                framesPerTarget = 16,
                maxExposureTimeMs = 500,
                maxIso = 6400,
                mergeMethod = NightFrameMerger.MergeMethod.Median,
                alignMethod = NightFrameMerger.AlignMethod.ECC,
                minFramesForMerge = 6,
                alignDownscale = 0.5f,
                medianTileSize = 512,
            ) else null,
        )

        /**
         * Samsung Galaxy S21 / S21+ / S21 Ultra.
         *
         * Same sharpness-first shape as the S23 profile: the main 12 MP lens
         * with OIS captures, capped at its binned output, burst three, and
         * the stitch runs wide enough to keep the detail. The 64 MP tele
         * mode is not part of capture; it lives behind the Settings test
         * button ([SamsungHighRes]) because it needs its own session.
         */
        private val SAMSUNG_S21 = SphereDeviceProfile(
            preferWidestCamera = false,
            captureMaxLongEdgePx = 4000,
            burstPerTarget = 3,
            stitchMaxInputDimension = 2000,
            stitchMaxOutputWidth = 6144,
            unsharpAmount = 0.3f,
            pivot = PivotModel.HandheldBodySwivel,
            nightMode = if (BuildConfig.DEBUG) NightFrameMerger.Config(
                framesPerTarget = 12,
                maxExposureTimeMs = 333,
                maxIso = 3200,
                mergeMethod = NightFrameMerger.MergeMethod.Median,
                alignMethod = NightFrameMerger.AlignMethod.ECC,
                minFramesForMerge = 5,
                alignDownscale = 0.5f,
                medianTileSize = 512,
            ) else null,
        )

        /** The profile for the device this process is running on. */
        fun forDevice(context: Context): SphereDeviceProfile {
            val nightSupported = checkNightModeSupport(context)
            val profile =
                if (isSamsungGalaxyS23()) {
                    SAMSUNG_S23
                } else if (BuildConfig.DEBUG && isSamsungGalaxyS21()) {
                    SAMSUNG_S21
                } else {
                    DEFAULT
                }
            return if (BuildConfig.DEBUG) debugTuned(profile.copy(nightModeSupported = nightSupported)) else profile.copy(nightModeSupported = nightSupported)
        }

        /**
         * Checks if the device camera supports night mode via Camera2 API.
         * Queries the camera characteristics for night mode capability.
         */
        private fun checkNightModeSupport(context: Context): Boolean {
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraIds = cameraManager.cameraIdList
                for (id in cameraIds) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    // Only check back-facing cameras
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        // Check if the device supports night mode via hardware level
                        // Level 3 (DEVICE_LEVEL_3) supports multi-frame processing, RAW, etc.
                        val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 ||
                            hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
                            return true
                        }
                        // Check for burst capture capability which is needed for night mode
                        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                        if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)) {
                            return true
                        }
                    }
                }
                false
            } catch (e: Exception) {
                Logging.w("Night mode support check failed: ${e.message}")
                false
            }
        }

        /**
         * Debug-only capture and stitch tuning, from what the full pilot runs
         * actually showed. The pivot is the one at play here: the prototype's
         * scene distance for the runs past was 3.5 m, and the room the runs
         * shot in sits that close, while the shipped profile nominally assumes
         * 10 m. A near scene wants the bigger correction, and the pose
         * refiner absorbs what the nominal value leaves over (see
         * [PivotModel]). Release builds keep the shipped numbers untouched;
         * see AGENTS.md.
         */
        private fun debugTuned(profile: SphereDeviceProfile): SphereDeviceProfile {
            val tuned = profile.copy(
                pivot = profile.pivot.copy(sceneDistanceMeters = DEBUG_SCENE_DISTANCE_METERS),
            )
            Logging.i(
                "Debug pivot tuning: scene distance " +
                    "${profile.pivot.sceneDistanceMeters} -> " +
                    "${tuned.pivot.sceneDistanceMeters} m",
            )
            return tuned
        }

        /** Scene distance the debug pivot tuning assumes, in metres. */
        private const val DEBUG_SCENE_DISTANCE_METERS = 3.0f

        private fun isSamsungGalaxyS23(): Boolean =
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                (Build.MODEL.startsWith("SM-S911") ||
                    Build.MODEL.startsWith("SM-S916") ||
                    Build.MODEL.startsWith("SM-S918"))

        private fun isSamsungGalaxyS21(): Boolean =
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                (Build.MODEL.startsWith("SM-G991") ||
                    Build.MODEL.startsWith("SM-G996") ||
                    Build.MODEL.startsWith("SM-G998"))
    }
}
