package com.n30dyn4m1c.photosphere.orbit

private const val CENTER_TOLERANCE = 0.12f
private const val CENTER = 0.5f

/**
 * Short on-screen hint that keeps the subject framed during the orbit walk.
 *
 * Priority is UNKNOWN first, then vertical offset, then horizontal offset,
 * then distance, then phase.
 *
 * @param reading current subject reading.
 * @param lock locked subject reference used for the distance hint.
 * @return one short hint line.
 */
fun subjectHint(reading: OrbitSubjectReading, lock: OrbitSubjectLock): String {
    if (reading.phase == OrbitPhase.UNKNOWN) {
        return "Find the person in frame"
    }
    if (reading.bodyCenterYFrac < CENTER - CENTER_TOLERANCE) {
        return "Lower the phone"
    }
    if (reading.bodyCenterYFrac > CENTER + CENTER_TOLERANCE) {
        return "Raise the phone"
    }
    if (reading.bodyCenterXFrac < CENTER - CENTER_TOLERANCE) {
        return "Step left around the subject"
    }
    if (reading.bodyCenterXFrac > CENTER + CENTER_TOLERANCE) {
        return "Step right around the subject"
    }
    when (lock.distanceHint(reading)) {
        OrbitDistance.TOO_CLOSE -> return "Step back"
        OrbitDistance.TOO_FAR -> return "Step closer"
        OrbitDistance.GOOD, OrbitDistance.UNKNOWN -> Unit
    }
    return when (reading.phase) {
        OrbitPhase.FRONT -> "Front locked, walk around"
        OrbitPhase.SIDE_LEFT -> "Left side, keep walking"
        OrbitPhase.SIDE_RIGHT -> "Right side, keep walking"
        OrbitPhase.BACK -> "Back view, keep walking"
        OrbitPhase.UNKNOWN -> "Find the person in frame"
    }
}
