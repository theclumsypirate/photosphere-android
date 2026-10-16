package com.n30dyn4m1c.photosphere.orbit

/**
 * Phase of the subject relative to the camera.
 */
enum class OrbitPhase {
    FRONT,
    SIDE_LEFT,
    SIDE_RIGHT,
    BACK,
    UNKNOWN,
}

/**
 * One subject reading as fractions of the frame.
 *
 * @param bodyCenterXFrac center X in 0..1 of frame width.
 * @param bodyCenterYFrac center Y in 0..1 of frame height.
 * @param bodyHeightFrac nose to ankle midpoint height as fraction of frame height, or null.
 * @param phase facing phase derived from landmark likelihoods.
 */
data class OrbitSubjectReading(
    val bodyCenterXFrac: Float,
    val bodyCenterYFrac: Float,
    val bodyHeightFrac: Float?,
    val phase: OrbitPhase,
)
