package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.min

/**
 * Debug-only graph-based subset selection: which frames earn their place.
 *
 * The user's idea, one-to-one from Brown and Lowe (ICCV 2003, IJCV 2007):
 * treat the capture as a network. Every pair of frames the pose refiner
 * matched is an edge, and every frame is a node. A frame whose overlaps match
 * nothing - a target shot at a dead angle, a frame the shutter fired through
 * something abandoned - is a weak node: it sits isolated in the graph, its
 * pose is the sensor's guess alone, and in the render it either duplicates
 * what its neighbours paint or paints a patch nothing else agreed to.
 *
 * The rule here is deliberately narrow. A frame is dropped only when *no*
 * overlap edge it takes part in produced an accepted match, so anything that
 * contributed even one honest correspondence is safe. Dropping is capped so a
 * degraded capture (everything failed to match) does not turn into an empty
 * stitch, and the deepest ring frame is preferred over the shallowest when
 * the cap bites, so the sphere keeps its strongest content.
 *
 * On the pilot runs: every target was captured on purpose, and the pose
 * refiner matches most pairs, so typically little or nothing is dropped -
 * which is the point. The log line always states the count either way:
 * "Frame graph: kept 36 of 38 frames, dropped: 12, 31".
 *
 * Pure Kotlin with no OpenCV, directly JVM-testable. Debug only: wired behind
 * a capture-screen debug switch, inert in release builds (see AGENTS.md).
 */
object FrameGraphQuality {

    /**
     * Frames dropped when nothing matched, as a share of the total.
     *
     * Past this the capture is failing as a whole, not as single targets, and
     * pruning it would turn a throttled stitch into a gap-riddled one. The
     * WebGL-style cap keeps the experiments honest: a big pruned index list is
     * a bug report, not a cleanup.
     */
    private const val MAX_DROPPED_SHARE = 0.25

    /**
     * Absolute cap on drops, so a short run cannot lose its couple of frames.
     */
    private const val MAX_DROPPED_ABSOLUTE = 4

    /**
     * Which frames to keep.
     *
     * [frames] is the total run; [acceptedEdges] holds the pairs the pose
     * refiner matched. Returns the indices to keep, in input order, whenever
     * the graph says nothing needs pruning (matches everywhere, cap hit, or
     * no accepted edges at all: with no content measurement there is nothing
     * to judge, and the whole run stays).
     */
    fun select(frameCount: Int, acceptedEdges: List<Pair<Int, Int>>): List<Int> {
        if (frameCount <= 0) return emptyList()
        if (acceptedEdges.isEmpty()) return (0 until frameCount).toList()

        val matched = IntArray(frameCount)
        for ((from, to) in acceptedEdges) {
            if (from in 0 until frameCount) matched[from]++
            if (to in 0 until frameCount) matched[to]++
        }
        val orphans = (0 until frameCount).filter { matched[it] == 0 }
        if (orphans.isEmpty()) return (0 until frameCount).toList()

        val maxDropped = min(
            (frameCount * MAX_DROPPED_SHARE).toInt(),
            MAX_DROPPED_ABSOLUTE,
        )
        if (maxDropped <= 0) return (0 until frameCount).toList()
        val dropped = orphans.take(maxDropped)
        val droppedSet = dropped.toSet()
        return (0 until frameCount).filter { it !in droppedSet }
    }
}
