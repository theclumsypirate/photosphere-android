package com.n30dyn4m1c.photosphere.camera

import android.os.Build
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Debug-only lens calibration, solved from what real captures actually show.
 *
 * The camera's own numbers put this device's main lens at a 49.297 by 62.918
 * degree field of view (upright portrait). Stitching real runs against their
 * image content disagreed: solving the focal from the matched features of the
 * last full run lands at 1186 px for a 857 by 1142 decode, which is a 39.730
 * by 51.444 degree field of view. The camera reports the active array of the
 * whole sensor, but the stills of a guided capture are cropped output, so
 * every frame covers less of the sphere than the profile claims: a plan laid
 * out on the reported field of view leaves less overlap than intended, and the
 * solved focal has to drink the whole shortfall through the refinement pass.
 *
 * The override rescales the estimated field of view by the ratio of the two
 * answers, per axis, rather than replacing it: the crop the stream reports
 * still applies, on top of the solved calibration, and a frame captured in the
 * other orientation (or cropped differently) keeps its own proportion.
 *
 * Gated behind [BuildConfig.DEBUG] and the model check: the solved numbers are
 * a property of one phone's main lens, not of every phone, and the release
 * behaviour of capture and stitch must not move (see AGENTS.md).
 */
internal object DebugFovCalibration {

    /** Horizontal field of view the content of a real run solved to. */
    private const val SOLVED_HORIZONTAL_DEGREES = 39.730f

    /** Vertical field of view the content of a real run solved to. */
    private const val SOLVED_VERTICAL_DEGREES = 51.444f

    /** Horizontal field of view the camera's own reporting claimed. */
    private const val CLAIMED_HORIZONTAL_DEGREES = 49.297f

    /** Vertical field of view the camera's own reporting claimed. */
    private const val CLAIMED_VERTICAL_DEGREES = 62.918f

    /** Per-axis scale from the reported field of view to the solved one. */
    private val horizontalScale = SOLVED_HORIZONTAL_DEGREES / CLAIMED_HORIZONTAL_DEGREES
    private val verticalScale = SOLVED_VERTICAL_DEGREES / CLAIMED_VERTICAL_DEGREES

    /**
     * Whether this device is one whose captures solved [to] the constants
     * above. The Galaxy S23 line is the phone the full runs came from; the
     * check mirrors the model prefix match the device profile uses.
     */
    private fun isSolvedDevice(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            (Build.MODEL.startsWith("SM-S911") ||
                Build.MODEL.startsWith("SM-S916") ||
                Build.MODEL.startsWith("SM-S918"))

    /**
     * Rescales [fieldOfView] onto the solved calibration. Debug builds on the
     * solved device only; anything else passes through untouched.
     */
    fun apply(fieldOfView: FieldOfView): FieldOfView {
        if (!BuildConfig.DEBUG) return fieldOfView
        if (!isSolvedDevice()) return fieldOfView
        val solved = FieldOfView(
            horizontalDegrees = (fieldOfView.horizontalDegrees * horizontalScale)
                .coerceIn(1f, 179f),
            verticalDegrees = (fieldOfView.verticalDegrees * verticalScale)
                .coerceIn(1f, 179f),
        )
        Logging.i(
            "Debug FOV calibration: reported ${fieldOfView.horizontalDegrees} x " +
                "${fieldOfView.verticalDegrees} -> solved ${solved.horizontalDegrees} x " +
                "${solved.verticalDegrees}",
        )
        return solved
    }
}
