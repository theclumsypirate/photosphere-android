package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.ui.geometry.Offset
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Debug-only hook for the viewfinder's target dot.
 *
 * A phone without a gyroscope has no game rotation vector, so the tracker falls
 * back to the full rotation vector. Its yaw is compass-based and jitters by a
 * few degrees per sample, which no amount of filtering can turn into both a
 * steady and a responsive overlay. The debug wrapper pins the displayed pose
 * to the first fix, so the live target never moves on those devices. Capture
 * poses are untouched.
 */
object DebugOrientationSupport {
    fun isNoGyro(context: Context): Boolean {
        val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
        return manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null
    }

    fun wrap(context: Context, delegate: OrientationStabilizer): OrientationStabilizer {
        return if (!isNoGyro(context)) {
            delegate
        } else {
            Logging.i("Debug no-gyro viewfinder: static target dot enabled")
            DebugStaticNoGyroStabilizer(delegate)
        }
    }

    /**
     * Where the reticle should be drawn while the targets stay pinned.
     *
     * The markers are projected from the frozen pose, but the camera keeps
     * moving. Expressing the live pose in the frozen frame gives the reticle a
     * screen position that the user can move onto the static yellow dot with
     * an ordinary pan. The capture gate already uses the live pose, so the
     * shutter still fires at the same moment.
     */
    fun reticleCenter(
        center: Offset,
        frozen: OrientationData,
        live: OrientationData,
        focalPx: Float,
        widthPx: Float,
        heightPx: Float,
    ): Offset {
        if (!frozen.hasFix || !live.hasFix) return center
        val view = SphereProjection.cameraFrame(frozen).project(
            SphereTarget(live.yawDegrees, live.pitchDegrees),
        )
        if (!view.isInFront) return center
        val x = center.x + view.x / view.z * focalPx
        val y = center.y - view.y / view.z * focalPx
        return Offset(
            x.coerceIn(0f, widthPx),
            y.coerceIn(0f, heightPx),
        )
    }
}
