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
     * The OpenCV detail stitcher's confidence cut (cv.detail
     * leaveBiggestComponent, which OpenStitching's [Subsetter] drives at the
     * default threshold 1).
     */
    const val CONFIDENCE_THRESHOLD = 1f

    /**
     * Below this share of the run the biggest component is not trusted and
     * the whole capture stays - a pruned pano worse than the raw one.
     */
    private const val MIN_KEPT_SHARE = 0.5

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

    /**
     * The OpenCV DetailConfidenceThreshold rule, ported: keep the biggest
     * connected component of the *confidence-weighted* match graph
     * (cv.detail.leaveBiggestComponent, which the OpenStitching subsetter
     * drives at the default threshold 1).
     *
     * The confidence an edge carries is the OpenCV one:
     *
     *     confidence = inliers / (8 + 0.3 * matches)
     *
     * an edge with almost nothing but matches (few inliers) scores low even
     * when its inlier count is huge, so repetitive texture cannot buy a node
     * in - and a component splits where the honest measurement is weaker than
     * the threshold. The kept set is the largest component after splitting,
     * with the rest of the frames handed to the sensor poses.
     *
     * A safety floor is added here that leaveBiggestComponent does not have:
     * this pipeline's covers are on purpose, not a casual photo heap, so when
     * the biggest component reaches under [MIN_KEPT_SHARE] of the run the
     * judgement is treated as failed and the whole run stays (logged). Same
     * rule as [select]: with no measured edge at all, nothing is dropped.
     */
    fun selectLargestComponent(
        frameCount: Int,
        acceptedEdges: List<Pair<Int, Int>>,
        confidences: List<Float>,
        confidenceThreshold: Float = CONFIDENCE_THRESHOLD,
    ): List<Int> {
        if (frameCount <= 0) return emptyList()
        if (confidences.size != acceptedEdges.size) {
            return select(frameCount, acceptedEdges)
        }

        val adjacency = Array(frameCount) { ArrayList<Int>() }
        for ((index, edge) in acceptedEdges.withIndex()) {
            if (confidences[index] < confidenceThreshold) continue
            val (from, to) = edge
            if (from in 0 until frameCount && to in 0 until frameCount) {
                adjacency[from] += to
                adjacency[to] += from
            }
        }

        // Depth-first components, biggest wins.
        val component = IntArray(frameCount) { -1 }
        var bestComponent = -1
        var bestSize = 0
        var nextComponent = 0
        for (start in 0 until frameCount) {
            if (component[start] != -1) continue
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            component[start] = nextComponent
            var size = 0
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                size++
                for (next in adjacency[node]) {
                    if (component[next] == -1) {
                        component[next] = nextComponent
                        stack.addLast(next)
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size
                bestComponent = nextComponent
            }
            nextComponent++
        }

        if (bestSize * 100 < frameCount * (MIN_KEPT_SHARE * 100)) {
            return (0 until frameCount).toList()
        }
        return (0 until frameCount).filter { component[it] == bestComponent }
    }
}
