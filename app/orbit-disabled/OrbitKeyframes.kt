package com.n30dyn4m1c.photosphere.orbit

import com.n30dyn4m1c.photosphere.util.Logging
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.features2d.DescriptorMatcher

/**
 * Small store of past keyframe descriptors used to detect orbit loop closure.
 *
 * Pure logic, no Android and no camera: callers pass ORB descriptors in,
 * this class clones them, caps the history, and matches new descriptors
 * against the stored set.
 */
class OrbitKeyframes {
    /** Stored keyframe descriptors in insertion order, oldest first. */
    private val keyframes = ArrayDeque<StoredKeyframe>()

    /** Next id to hand out. Monotonic, never reused. */
    private var nextId = 0

    /**
     * Stored keyframe entry.
     *
     * @property id id returned by [addKeyframe].
     * @property descriptors cloned descriptors owned by this store.
     */
    private data class StoredKeyframe(
        val id: Int,
        val descriptors: Mat,
    )

    companion object {
        /** Maximum number of keyframes kept. Oldest entries are dropped first. */
        const val MAX_KEYFRAMES = 16

        /** Lowe ratio test threshold used in [matchLoop]. */
        const val LOWE_RATIO = 0.75f
    }

    /**
     * Clones and stores [descriptors], returning the new keyframe id.
     *
     * When the store already holds [MAX_KEYFRAMES] entries, the oldest one
     * is dropped and its Mat released before the new entry is added.
     *
     * @param descriptors ORB descriptors to store. Never retained directly.
     * @return id of the stored keyframe.
     */
    fun addKeyframe(descriptors: Mat): Int {
        val id = nextId++
        if (keyframes.size >= MAX_KEYFRAMES) {
            val dropped = keyframes.removeFirst()
            dropped.descriptors.release()
        }
        keyframes.addLast(StoredKeyframe(id, descriptors.clone()))
        Logging.i("Orbit keyframe $id stored")
        return id
    }

    /**
     * Matches [descriptors] against every stored keyframe.
     *
     * Each candidate is matched with BRUTEFORCE_HAMMING knnMatch(k=2) and
     * filtered with the Lowe ratio test at [LOWE_RATIO]. All intermediate
     * Mats are released.
     *
     * @param descriptors query ORB descriptors. Not retained, not released.
     * @param minGoodMatches minimum good matches required for a hit.
     * @return id of the best matching keyframe, or null when no keyframe
     * reaches [minGoodMatches].
     */
    fun matchLoop(descriptors: Mat, minGoodMatches: Int = 15): Int? {
        if (keyframes.isEmpty()) return null
        if (descriptors.empty()) return null
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        try {
            var bestId: Int? = null
            var bestCount = 0
            for (keyframe in keyframes) {
                if (keyframe.descriptors.empty()) continue
                val count = countGoodMatches(matcher, descriptors, keyframe.descriptors)
                if (count > bestCount) {
                    bestCount = count
                    bestId = keyframe.id
                }
            }
            val result = if (bestCount >= minGoodMatches) bestId else null
            if (result != null) Logging.i("Orbit loop closed on keyframe $result")
            return result
        } finally {
            matcher.clear()
        }
    }

    /**
     * Counts Lowe ratio filtered matches between [query] and [train].
     *
     * Uses knnMatch(k=2) and keeps a match only when the best candidate is
     * clearly better than the second best. All intermediate Mats are released.
     *
     * @param matcher matcher to run knnMatch on.
     * @param query query descriptors.
     * @param train stored keyframe descriptors.
     * @return number of good matches.
     */
    private fun countGoodMatches(matcher: DescriptorMatcher, query: Mat, train: Mat): Int {
        val knn = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(query, train, knn, 2)
            var good = 0
            for (result in knn) {
                val candidates = result.toArray()
                if (candidates.size >= 2 &&
                    candidates[0].distance < LOWE_RATIO * candidates[1].distance
                ) {
                    good++
                }
            }
            return good
        } finally {
            knn.forEach { it.release() }
        }
    }
}
