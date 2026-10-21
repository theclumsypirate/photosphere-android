package com.n30dyn4m1c.photosphere.orbit

import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.abs

/**
 * Distance of the current reading relative to the locked reference.
 */
enum class OrbitDistance {
    /** Body appears larger than at lock time. */
    TOO_CLOSE,

    /** Body size is within tolerance of lock time. */
    GOOD,

    /** Body appears smaller than at lock time. */
    TOO_FAR,

    /** No lock or reading is incomplete. */
    UNKNOWN,
}

/**
 * Holds a locked subject reference for picking the same person across frames.
 *
 * Lock stores body height fraction and body center X fraction at lock time.
 */
class OrbitSubjectLock {

    /** Locked body height fraction, or null when unlocked. */
    private var lockedHeightFrac: Float? = null

    /** Locked body center X fraction, or null when unlocked. */
    private var lockedCenterXFrac: Float? = null

    /** True when a reference is currently locked. */
    val isLocked: Boolean
        get() = lockedHeightFrac != null && lockedCenterXFrac != null

    /**
     * Locks the reference from a reading.
     *
     * @param reading current subject reading.
     * @return true when locked, false when reading is incomplete.
     */
    fun lock(reading: OrbitSubjectReading): Boolean {
        val height = reading.bodyHeightFrac ?: return false
        lockedHeightFrac = height
        lockedCenterXFrac = reading.bodyCenterXFrac
        Logging.i("Orbit subject locked at height $height")
        return true
    }

    /**
     * Clears the locked reference.
     */
    fun unlock() {
        lockedHeightFrac = null
        lockedCenterXFrac = null
        Logging.i("Orbit subject unlocked")
    }

    /**
     * Scores similarity of a reading against the locked reference.
     *
     * Compares body height fraction to the locked value.
     * Returns 0 when unlocked or reading is incomplete.
     *
     * @param reading current subject reading.
     * @return score in 0..1, where 1 is an exact match.
     */
    fun matchScore(reading: OrbitSubjectReading): Float {
        val locked = lockedHeightFrac ?: return 0f
        val height = reading.bodyHeightFrac ?: return 0f
        if (locked <= 0f) return 0f
        val diff = abs(height - locked) / locked
        return (1f - diff).coerceIn(0f, 1f)
    }

    /**
     * Hints camera distance from a reading against the locked reference.
     *
     * Uses 20 percent tolerance around the locked body height fraction.
     * A larger body means the subject is closer.
     *
     * @param reading current subject reading.
     * @return distance hint, or UNKNOWN when unlocked or incomplete.
     */
    fun distanceHint(reading: OrbitSubjectReading): OrbitDistance {
        val locked = lockedHeightFrac ?: return OrbitDistance.UNKNOWN
        val height = reading.bodyHeightFrac ?: return OrbitDistance.UNKNOWN
        if (locked <= 0f) return OrbitDistance.UNKNOWN
        val ratio = height / locked
        return when {
            ratio > 1.2f -> OrbitDistance.TOO_CLOSE
            ratio < 0.8f -> OrbitDistance.TOO_FAR
            else -> OrbitDistance.GOOD
        }
    }
}
