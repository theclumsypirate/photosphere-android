package com.n30dyn4m1c.photosphere.storage

import java.io.File

/**
 * One buffered frame: where the pixels live, and where the camera was pointing.
 *
 * The angles are copied from [OrientationData] and keep its conventions —
 * [yawDegrees] is the camera's compass bearing and [pitchDegrees] is **negative
 * above the horizon**. They are held in memory as well as stamped into the
 * file's EXIF so the stitcher can order and pre-align frames without paying to
 * re-read forty-odd JPEG headers.
 */
data class BufferedFrame(
    /** Position in the capture plan; also the file's sequence number. */
    val index: Int,
    /** Full-resolution JPEG in the session's cache directory. */
    val file: File,
    /** Compass bearing of the camera at capture, -180°..180°. */
    val yawDegrees: Float,
    /** Vertical aim at capture, -90°..90°, negative above the horizon. */
    val pitchDegrees: Float,
    /** Side tilt at capture, -180°..180°. */
    val rollDegrees: Float,
    /**
     * The camera's basis as a rotation matrix — the `[right, −up, forward]`
     * columns in the world frame, in the `CameraBasis.toRotationMatrix` layout.
     * Present for every frame the tracker captured, and what the stitcher
     * reconstructs the pose from: it carries the orientation exactly where the
     * Euler components collapse (a frame aimed at the zenith). Null for frames
     * recorded without a sensor sample, e.g. in tests.
     */
    val cameraBasis: FloatArray? = null,
    /** Wall clock at capture, for ordering frames of equal index. */
    val capturedAtMillis: Long,
) {
    /** Height above the horizon, positive up — the sign most people expect. */
    val elevationDegrees: Float get() = -pitchDegrees
}