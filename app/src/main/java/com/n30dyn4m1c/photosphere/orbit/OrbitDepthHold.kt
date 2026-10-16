package com.n30dyn4m1c.photosphere.orbit

/**
 * Estimates distance from camera to the person in frame.
 *
 * A MiDaS style monocular depth model can implement this later
 * by converting relative depth or body size cues to meters.
 */
interface OrbitDepthEstimator {
    /**
     * Estimates person distance in meters.
     *
     * @param frameWidth frame width in pixels.
     * @param frameHeight frame height in pixels.
     * @param bodyHeightFrac fraction of frame height covered by the body, from 0 to 1.
     * @return distance in meters, or null when unknown.
     */
    fun estimatePersonDistanceMeters(
        frameWidth: Int,
        frameHeight: Int,
        bodyHeightFrac: Float,
    ): Float?
}

/**
 * No operation depth estimator that always reports unknown distance.
 *
 * Used as the default until a real depth model is wired in.
 */
object OrbitNoopDepthEstimator : OrbitDepthEstimator {
    /**
     * Always returns null to signal unknown distance.
     *
     * @param frameWidth frame width in pixels.
     * @param frameHeight frame height in pixels.
     * @param bodyHeightFrac fraction of frame height covered by the body, from 0 to 1.
     * @return always null.
     */
    override fun estimatePersonDistanceMeters(
        frameWidth: Int,
        frameHeight: Int,
        bodyHeightFrac: Float,
    ): Float? = null
}

/**
 * Holds the target camera radius and maps a distance reading to a user hint.
 *
 * @param targetMeters desired distance in meters.
 * @param toleranceMeters accepted deviation around [targetMeters].
 */
data class OrbitRadiusHold(
    val targetMeters: Float = 1.5f,
    val toleranceMeters: Float = 0.3f,
) {
    /**
     * Returns a guidance hint for the current distance.
     *
     * @param distanceMeters measured distance in meters, or null when unknown.
     * @return "Step closer", "Step back", "Good distance", or "Distance unknown".
     */
    fun hint(distanceMeters: Float?): String {
        if (distanceMeters == null) return "Distance unknown"
        if (distanceMeters < targetMeters - toleranceMeters) return "Step closer"
        if (distanceMeters > targetMeters + toleranceMeters) return "Step back"
        return "Good distance"
    }
}
