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
 * few degrees per sample, which the default smoother's snap threshold reads as
 * real motion and passes straight to the markers. The debug wrapper holds the
 * displayed pose still until the change is sustained, so the live target stops
 * wandering on those devices. Capture poses are untouched.
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
