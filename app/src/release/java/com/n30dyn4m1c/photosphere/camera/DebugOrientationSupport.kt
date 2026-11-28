package com.n30dyn4m1c.photosphere.camera

import android.content.Context

/**
 * Release no-op. The real no-gyro viewfinder hook lives in the debug source
 * set; release builds always use the shipped smoother.
 */
object DebugOrientationSupport {
    fun isNoGyro(context: Context): Boolean = false

    fun wrap(context: Context, delegate: OrientationStabilizer): OrientationStabilizer = delegate
}
