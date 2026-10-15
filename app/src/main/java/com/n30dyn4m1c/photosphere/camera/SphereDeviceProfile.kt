package com.n30dyn4m1c.photosphere.camera

import android.os.Build
import com.n30dyn4m1c.photosphere.stitching.PivotModel

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
) {
    companion object {
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
        )

        /** The profile for the device this process is running on. */
        fun forDevice(): SphereDeviceProfile =
            if (isSamsungGalaxyS23()) {
                SAMSUNG_S23
            } else if (isSamsungGalaxyS21()) {
                SAMSUNG_S21
            } else {
                DEFAULT
            }

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
