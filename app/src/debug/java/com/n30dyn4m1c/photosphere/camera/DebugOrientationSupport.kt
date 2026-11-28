package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
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
}
