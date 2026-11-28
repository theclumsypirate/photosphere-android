package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import androidx.compose.ui.geometry.Offset
import com.n30dyn4m1c.photosphere.sensor.OrientationData

/**
 * Release no-op. The real no-gyro viewfinder hook lives in the debug source
 * set; release builds always use the shipped smoother.
 */
object DebugOrientationSupport {
    fun isNoGyro(context: Context): Boolean = false

    fun wrap(context: Context, delegate: OrientationStabilizer): OrientationStabilizer = delegate

    fun reticleCenter(
        center: Offset,
        frozen: OrientationData,
        live: OrientationData,
        focalPx: Float,
        widthPx: Float,
        heightPx: Float,
    ): Offset = center
}
