package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData

/**
 * A draw-only attitude filter.
 *
 * The overlay projects markers from a steadied pose so hand tremor does not
 * shake them, while capture decisions keep the raw sensor samples. Debug-only
 * implementations can wrap the default smoother without changing that
 * contract; release builds use [OrientationSmoother] directly.
 */
interface OrientationStabilizer {
    fun update(sample: OrientationData): OrientationData
    fun reset()
}
