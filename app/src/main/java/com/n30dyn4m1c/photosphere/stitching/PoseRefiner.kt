package com.n30dyn4m1c.photosphere.stitching

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.CancellationException

/**
 * One decoded frame on its way into refinement: the pixels, the lens model, and
 * the measured pose the sensor reported when it was shot.
 */
internal class DecodedFrame(
    val image: Mat,
    val intrinsics: FrameIntrinsics,
    val sensorBasis: CameraBasis,
)

/**
 * What feature-based pose refinement ended with.
 *
 * [bases] holds one refined camera basis per input frame — the sensor pose for
 * any frame the content did not support — and [gains] the per-frame brightness
 * gains that equalise the overlaps. [matchedEdges] is how many pose-graph edges
 * were actually measured against image content; zero means the sensor did all
 * the work and the output is a plain orientation-driven stitch.
 * [candidateEdges] is how many overlapping pairs the graph offered in the first
 * place, so the two together say whether a poor refinement means a sparse graph
 * or feature matching that rejected almost every pair.
 *
 * [focalScales] holds one focal-length multiplier per frame, aligned by position
 * with [bases]: 1.0 when the measured field of view needs no correction, or
 * when nothing matched and there was no content to measure against. The
 * stitcher applies it to each frame's intrinsics before rendering.
 */
internal data class RefinementResult(
    val bases: List<CameraBasis>,
    val gains: FloatArray,
    val matchedEdges: Int,
    val candidateEdges: Int,
    val focalScales: DoubleArray,
    /**
     * The pose-graph edges the image content accepted, as frame index pairs,
     * and how many of them each frame took part in. Debug-only subset
     * selection reads them; production consumers ignore the extras.
     */
    val acceptedEdges: List<Pair<Int, Int>> = emptyList(),
    val matchedEdgesPerFrame: IntArray = IntArray(0),
    /**
     * Debug-only local alignment meshes ([LocalWarp.Mesh]), one per frame or
     * null, present only when the caller asked the refinement to build them.
     */
    val localWarps: Array<LocalWarp.Mesh?>? = null,
)

/**
 * Sharpens the measured poses against the image content.
 *
 * The sensor places every frame to within a degree or two, which is good enough
 * for a panorama but leaves the overlaps soft. This pass measures the residual:
 * frames that overlap are matched on ORB features, a pure-rotation RANSAC
 * recovers the *content-observed* relative rotation, and a Gauss–Seidel solve
 * distributes those measurements over the whole pose graph with the sensor
 * rotations as the starting guess. The result is a small per-frame correction on
 * top of the measured pose — the sensor stays the anchor, and the pixels decide
 * the fine alignment.
 *
 * The same correspondences also sharpen the *field of view*. A focal-length
 * error pulls every bearing radially about its optical axis, so the residual
 * the rotations leave behind carries a measure of it; a per-frame focal scale
 * is solved from the matched bearings by least squares ([RotationMath.refineFocalLengths])
 * and handed back with the poses, and the stitcher projects through the
 * corrected focal lengths. Frame 0 is anchored — rotation averaging already
 * fixes it, and the whole sphere may be uniformly scaled without changing any
 * alignment, so the absolute scale is only defined relative to the frame that
 * is held still.
 *
 * Nothing here is allowed to make a stitch *worse*: every edge is checked
 * against the sensor-relative rotation it should be near, frames with no
 * reliable matches keep their sensor pose and their reported focal length, and
 * if nothing matches at all the whole pass hands the sensor rotations straight
 * back.
 */
internal object PoseRefiner {

    /** Long edge frames are downscaled to before ORB runs. */
    const val FEATURE_DETECT_LONG_EDGE = 512

    /** ORB detector settings: plenty of features, tolerant of small pose error. */
    private const val ORB_MAX_FEATURES = 1500

    /** Ratio test: keep a match only if clearly better than the second-best. */
    private const val RATIO_TEST = 0.8f

    /**
     * Below this many matches an edge is not worth solving for.
     *
     * Deliberately low: the RANSAC result still has to agree with the sensor's
     * relative rotation within [MAX_SENSOR_DEVIATION_DEGREES], so a sparse but
     * honest set of matches from a low-texture scene is accepted where a strict
     * count would hand the whole edge back to the sensor alone.
     */
    const val MIN_MATCHES = 16

    /** Below this many inliers a rotation estimate is not trusted. */
    const val MIN_INLIERS = 12

    /** Inlier agreement for the RANSAC, in degrees. */
    const val RANSAC_THRESHOLD_DEGREES = 2.0

    /** How many neighbours each frame is allowed to measure. */
    const val MAX_EDGES_PER_FRAME = 5

    /**
     * An edge whose rotation lands further than this from the sensor's relative
     * rotation is assumed to be a false match on repetitive texture, not a lens
     * that suddenly moved.
     */
    const val MAX_SENSOR_DEVIATION_DEGREES = 6.0

    /** Gauss–Seidel sweeps over the pose graph. */
    private const val AVERAGE_ITERATIONS = 8

    /**
     * Below this many matched feature correspondences the focal correction is
     * not worth solving for.
     *
     * The solve needs enough bearings spread across the frame to separate a
     * genuine focal-scale error from feature-location noise; a handful of
     * matches from a low-texture scene would happily explain themselves as a
     * focal shift, so below this floor the reported field of view is kept.
     */
    const val MIN_FOCAL_CORRESPONDENCES = 40

    /**
     * Largest focal correction a frame is allowed to take, as a fraction.
     *
     * A lens that genuinely wanted a bigger correction would be a badly
     * described device, and a bad solve must not be allowed to rescale a frame
     * enough to open gaps its neighbours cannot close. 15% is an order of
     * magnitude more than the residual scale error a loosely-described lens
     * leaves behind.
     */
    const val MAX_FOCAL_CORRECTION = 0.15

    /**
     * Gauss–Newton passes over the focal correction.
     *
     * Each pass re-linearises the residuals around the current estimate and
     * re-solves; two passes absorb most of the nonlinearity a multi-degree
     * correction leaves behind. The rotations stay put between passes — the
     * focal shift is a second-order correction to them — so each pass is just a
     * rebuild of the correspondences and one solve.
     */
    private const val FOCAL_ITERATIONS = 3

    fun refine(
        frames: List<DecodedFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
        pivotRatio: Double = 0.0,
        buildLocalWarps: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): RefinementResult {
        val candidateEdges = overlapGraph(frames, horizontalFovDegrees, verticalFovDegrees)

        val orb = ORB.create(
            ORB_MAX_FEATURES, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20,
        )
        val emptyMask = Mat()
        val features = frames.map { extractFeatures(it, orb, emptyMask, pivotRatio) }
        val acceptedEdges = ArrayList<RotationMath.RotationEdge>()
        // The inlier match indices of each accepted edge, kept in the same order
        // as [acceptedEdges] so the focal solve knows which pixels to re-project.
        val edgeInliers = ArrayList<List<Pair<Int, Int>>>()
        // One matcher for the whole pass: `create` builds a native object, and
        // the pose graph asks it for a hundred-odd pairs.
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

        try {
            val total = candidateEdges.size
            var done = 0
            for ((first, second) in candidateEdges) {
                val matches = match(matcher, features[first], features[second])
                if (matches.size >= MIN_MATCHES) {
                    val from = Array(matches.size) { features[first].bearings[matches[it].first] }
                    val to = Array(matches.size) { features[second].bearings[matches[it].second] }
                    val estimate = RotationMath.estimateRotation(
                        from = from,
                        to = to,
                        thresholdRadians = Math.toRadians(RANSAC_THRESHOLD_DEGREES),
                        maxIterations = 300,
                        random = Random(seedFor(first, second)),
                        minInliers = MIN_INLIERS,
                    )
                    if (estimate != null && estimate.inlierCount >= MIN_INLIERS) {
                        val sensorRelative = RotationMath.multiply(
                            RotationMath.transpose(frames[second].sensorBasis.toRotationMatrix()),
                            frames[first].sensorBasis.toRotationMatrix(),
                        )
                        val deviation = Math.toDegrees(
                            RotationMath.angle(estimate.rotation, sensorRelative)
                        )
                        if (deviation <= MAX_SENSOR_DEVIATION_DEGREES) {
                            acceptedEdges += RotationMath.RotationEdge(
                                from = first,
                                to = second,
                                rotation = estimate.rotation,
                                weight = estimate.inlierCount.toDouble(),
                            )
                            edgeInliers += estimate.inliers.map { matches[it] }
                        }
                    }
                }
                done++
                onProgress(done, total)
            }

            val initial = frames.map { it.sensorBasis.toRotationMatrix() }
            val refined = if (acceptedEdges.isEmpty()) {
                initial.map { CameraBasis.fromRotationMatrix(it) }
            } else {
                RotationMath.averageRotations(initial, acceptedEdges, AVERAGE_ITERATIONS)
                    .map { CameraBasis.fromRotationMatrix(it) }
            }

            val meanLuma = DoubleArray(frames.size) { features[it].meanLuma }
            val gains = ExposureCompensation.solveGains(meanLuma, candidateEdges)

            val focalScales = solveFocalScales(
                frames = frames,
                features = features,
                edges = acceptedEdges,
                edgeInliers = edgeInliers,
                refinedRotations = refined.map { it.toRotationMatrix() },
                pivotRatio = pivotRatio,
            )

            // The pose-graph extras the debug tools read: which edges the
            // content accepted, how loaded each frame came out of it, and -
            // on request - the per-frame local alignment meshes.
            val acceptedPairs = acceptedEdges.map { it.from to it.to }
            val matchedPerFrame = IntArray(frames.size)
            for ((from, to) in acceptedPairs) {
                matchedPerFrame[from]++
                matchedPerFrame[to]++
            }
            val warps = if (buildLocalWarps && acceptedEdges.isNotEmpty()) {
                try {
                    buildLocalWarps(
                        frames = frames,
                        features = features,
                        edges = acceptedEdges,
                        edgeInliers = edgeInliers,
                        refined = refined,
                        focalScales = focalScales,
                        pivotRatio = pivotRatio,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A debug experiment that cannot run must never sink a
                    // stitch the normal path would have produced.
                    Logging.w("Local warp mesh building failed; none applied", e)
                    null
                }
            } else {
                null
            }

            return RefinementResult(
                bases = refined,
                gains = gains,
                matchedEdges = acceptedEdges.size,
                candidateEdges = candidateEdges.size,
                focalScales = focalScales,
                acceptedEdges = acceptedPairs,
                matchedEdgesPerFrame = matchedPerFrame,
                localWarps = warps,
            )
        } finally {
            features.forEach { it.descriptors.release() }
            matcher.clear()
            orb.clear()
            emptyMask.release()
        }
    }

    private class FrameFeatures(
        val bearings: List<DoubleArray>,
        /**
         * The undistorted (ideal pinhole) pixel of each keypoint, aligned by
         * position with [bearings]. The bearing is the same information in
         * angular form under the *assumed* focal length; the pixel is what lets
         * the focal solve re-project the keypoint through a corrected one
         * without re-running ORB.
         */
        val idealPixels: List<DoubleArray>,
        val descriptors: Mat,
        val meanLuma: Double,
    )

    /**
     * Detects ORB features on a downscaled copy and converts each keypoint to a
     * unit bearing in the frame's camera space.
     *
     * The keypoint is scaled back to the decoded frame's pixels, un-distorted to
     * the ideal pinhole coordinate (the same lens model the renderer samples
     * through), and projected through the decoded intrinsics — so a bearing is
     * directly comparable to a bearing from any other frame.
     *
     * With a [pivotRatio] the bearing is then re-referred to the pivot: the ray
     * is followed out to the nominal scene distance and looked back along from
     * the axis the user turned about, then rotated back into the camera's own
     * frame. Two frames of the same body-swivel translate relative to each
     * other, and a pure-rotation RANSAC cannot describe that; two *pivot*
     * bearings of the same scene point differ by a rotation alone, which it can.
     */
    private fun extractFeatures(
        frame: DecodedFrame,
        orb: ORB,
        mask: Mat,
        pivotRatio: Double,
    ): FrameFeatures {
        val gray = Mat()
        val small = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            Imgproc.cvtColor(frame.image, gray, Imgproc.COLOR_RGB2GRAY)
            val width = frame.image.cols()
            val height = frame.image.rows()
            val longest = max(width, height)
            val scale = FEATURE_DETECT_LONG_EDGE.toDouble() / longest
            val targetWidth = (width * scale).roundToInt().coerceAtLeast(2)
            val targetHeight = (height * scale).roundToInt().coerceAtLeast(2)
            Imgproc.resize(
                gray, small,
                Size(targetWidth.toDouble(), targetHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            orb.detectAndCompute(small, mask, keypoints, descriptors)

            val scaleX = width.toDouble() / targetWidth
            val scaleY = height.toDouble() / targetHeight
            val intrinsics = frame.intrinsics
            val radial = intrinsics.radial
            val cx = intrinsics.centerXPx
            val cy = intrinsics.centerYPx
            val fx = intrinsics.focalXPx
            val fy = intrinsics.focalYPx

            val basis = frame.sensorBasis
            val bearings = ArrayList<DoubleArray>(keypoints.rows())
            val idealPixels = ArrayList<DoubleArray>(keypoints.rows())
            for (kp in keypoints.toArray()) {
                val u = kp.pt.x * scaleX
                val v = kp.pt.y * scaleY
                val ideal = if (radial != null) {
                    undistortPixel(u, v, cx, cy, radial)
                } else {
                    doubleArrayOf(u, v)
                }
                idealPixels += ideal
                var x = (ideal[0] - cx) / fx
                var y = -(ideal[1] - cy) / fy
                val length = Math.sqrt(x * x + y * y + 1.0)
                x /= length
                y /= length
                val z = 1.0 / length
                bearings += if (pivotRatio > 0.0) {
                    pivotReferredBearing(basis, pivotRatio, x, y, z)
                } else {
                    doubleArrayOf(x, y, z)
                }
            }

            return FrameFeatures(bearings, idealPixels, descriptors, Core.mean(small).`val`[0])
        } finally {
            gray.release()
            small.release()
            keypoints.release()
        }
    }

    /**
     * A camera-frame bearing rewritten as the bearing a camera *at the pivot*
     * would have recorded for the same scene point.
     *
     * Out to the world, out to the scene, back to the pivot, back into the
     * camera's axes. The round trip through the sensor pose is what makes the
     * result comparable between frames: the pivot is common to all of them,
     * so the only thing left between two frames' pivot bearings is rotation.
     */
    private fun pivotReferredBearing(
        basis: CameraBasis,
        pivotRatio: Double,
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray {
        val world = basis.toWorld(x, y, z)
        val fromPivot = pivotDirection(basis, pivotRatio, world[0], world[1], world[2])
        return doubleArrayOf(
            basis.lateralOf(fromPivot[0], fromPivot[1], fromPivot[2]),
            basis.verticalOf(fromPivot[0], fromPivot[1], fromPivot[2]),
            basis.depthOf(fromPivot[0], fromPivot[1], fromPivot[2]),
        )
    }

    /**
     * The focal-correction solve: one per-frame scale, or all ones when there
     * is nothing to measure against.
     *
     * The scale describes the lens the *feature matches* saw, which is why a
     * matchless run keeps the reported field of view: a correction with no
     * content behind it is a guess. The rotations from the pose graph are the
     * starting point, but the solve itself never touches them directly — each
     * correspondence carries its edge's rotation already baked into the
     * transformed bearing, so a rebuild of the correspondences is all a
     * Gauss–Newton pass needs.
     */
    private fun solveFocalScales(
        frames: List<DecodedFrame>,
        features: List<FrameFeatures>,
        edges: List<RotationMath.RotationEdge>,
        edgeInliers: List<List<Pair<Int, Int>>>,
        refinedRotations: List<DoubleArray>,
        pivotRatio: Double,
    ): DoubleArray {
        val scales = DoubleArray(frames.size) { 1.0 }
        if (edges.isEmpty()) return scales
        if (edgeInliers.sumOf { it.size } < MIN_FOCAL_CORRESPONDENCES) return scales

        // The relative rotations the rendering will actually use — smoother
        // than the raw RANSAC edges, since rotation averaging has already
        // spread each measurement across the graph.
        val relative = edges.map { edge ->
            RotationMath.multiply(
                RotationMath.transpose(refinedRotations[edge.to]),
                refinedRotations[edge.from],
            )
        }

        val logScale = DoubleArray(frames.size)
        repeat(FOCAL_ITERATIONS) {
            val correspondences = buildFocalCorrespondences(
                frames = frames,
                features = features,
                edges = edges,
                edgeInliers = edgeInliers,
                relativeRotations = relative,
                pivotRatio = pivotRatio,
                scales = scales,
            )
            val delta = RotationMath.refineFocalLengths(
                correspondences, frames.size, maxCorrection = MAX_FOCAL_CORRECTION,
            )
            for (frame in frames.indices) logScale[frame] += delta[frame]
            for (frame in frames.indices) scales[frame] = Math.exp(logScale[frame])
        }
        // The per-pass clamp bounds each step, but the steps accumulate; clamp
        // the total too so a pathological solve still cannot rescale a frame
        // beyond the safety limit.
        for (frame in frames.indices) {
            logScale[frame] = logScale[frame].coerceIn(-MAX_FOCAL_CORRECTION, MAX_FOCAL_CORRECTION)
            scales[frame] = Math.exp(logScale[frame])
        }
        return scales
    }

    /**
     * The [RotationMath.FocalCorrespondence]s the accepted edges imply, with
     * bearings and derivatives evaluated at [scales].
     *
     * Each inlier of each edge becomes one correspondence: the *from* bearing
     * is projected through the edge's (smoothed) relative rotation and carried
     * alongside the *to* bearing, with the derivative of each with respect to
     * its frame's focal scale. The derivatives are numerical — a central
     * difference of the full bearing pipeline including the pivot referral,
     * which has no closed form worth inlining — and each costs two re-projections
     * of one pixel, so a few hundred correspondences come out in microseconds.
     */
    private fun buildFocalCorrespondences(
        frames: List<DecodedFrame>,
        features: List<FrameFeatures>,
        edges: List<RotationMath.RotationEdge>,
        edgeInliers: List<List<Pair<Int, Int>>>,
        relativeRotations: List<DoubleArray>,
        pivotRatio: Double,
        scales: DoubleArray,
    ): List<RotationMath.FocalCorrespondence> {
        val correspondences = ArrayList<RotationMath.FocalCorrespondence>()
        for (edgeIndex in edges.indices) {
            val edge = edges[edgeIndex]
            val fromFrame = frames[edge.from]
            val toFrame = frames[edge.to]
            val fromFeatures = features[edge.from]
            val toFeatures = features[edge.to]
            val relative = relativeRotations[edgeIndex]
            for ((fromMatch, toMatch) in edgeInliers[edgeIndex]) {
                val fromPixel = fromFeatures.idealPixels[fromMatch]
                val toPixel = toFeatures.idealPixels[toMatch]
                val pFrom = cameraBearing(
                    fromFrame.intrinsics, fromFrame.sensorBasis, pivotRatio,
                    fromPixel, scales[edge.from],
                )
                val pTo = cameraBearing(
                    toFrame.intrinsics, toFrame.sensorBasis, pivotRatio,
                    toPixel, scales[edge.to],
                )
                val dFrom = focalDerivative(
                    fromFrame.intrinsics, fromFrame.sensorBasis, pivotRatio,
                    fromPixel, scales[edge.from],
                )
                val dTo = focalDerivative(
                    toFrame.intrinsics, toFrame.sensorBasis, pivotRatio,
                    toPixel, scales[edge.to],
                )
                correspondences += RotationMath.FocalCorrespondence(
                    from = edge.from,
                    to = edge.to,
                    transformedFrom = RotationMath.apply(relative, pFrom),
                    toBearing = pTo,
                    transformedFromDerivative = RotationMath.apply(relative, dFrom),
                    toDerivative = dTo,
                )
            }
        }
        return correspondences
    }

    /**
     * The unit bearing an ideal pixel records under a focal-length multiplier
     * of [scale].
     *
     * A focal scale of 1 reproduces the bearing [extractFeatures] computes, so
     * the two halves of the refinement measure the same geometry. Scaling the
     * focal length moves the bearing radially about the optical axis — the
     * pixel is divided through by the *corrected* focal length — and the pivot
     * referral (when there is one) runs afterwards, exactly as for a freshly
     * detected keypoint.
     */
    private fun cameraBearing(
        intrinsics: FrameIntrinsics,
        basis: CameraBasis,
        pivotRatio: Double,
        idealPixel: DoubleArray,
        scale: Double,
    ): DoubleArray {
        var x = (idealPixel[0] - intrinsics.centerXPx) / (intrinsics.focalXPx * scale)
        var y = -(idealPixel[1] - intrinsics.centerYPx) / (intrinsics.focalYPx * scale)
        val length = Math.sqrt(x * x + y * y + 1.0)
        x /= length
        y /= length
        val z = 1.0 / length
        return if (pivotRatio > 0.0) {
            pivotReferredBearing(basis, pivotRatio, x, y, z)
        } else {
            doubleArrayOf(x, y, z)
        }
    }

    /**
     * How a bearing at [idealPixel] moves when its frame's focal length is
     * scaled by `e^δ`, evaluated at the current [scale].
     *
     * Central difference in log-scale space: the bearing is re-projected at
     * `scale·e^±h` and the difference divided through. This is the derivative
     * the focal solve needs, and doing it numerically means the pivot referral
     * (a nonlinear map from the camera-frame bearing) is automatically included
     * — no closed-form Jacobian to derive and get wrong.
     */
    private fun focalDerivative(
        intrinsics: FrameIntrinsics,
        basis: CameraBasis,
        pivotRatio: Double,
        idealPixel: DoubleArray,
        scale: Double,
    ): DoubleArray {
        val step = 1e-4
        val plus = cameraBearing(intrinsics, basis, pivotRatio, idealPixel, scale * Math.exp(step))
        val minus = cameraBearing(intrinsics, basis, pivotRatio, idealPixel, scale * Math.exp(-step))
        return doubleArrayOf(
            (plus[0] - minus[0]) / (2.0 * step),
            (plus[1] - minus[1]) / (2.0 * step),
            (plus[2] - minus[2]) / (2.0 * step),
        )
    }

    /** Descriptor matches between two frames, filtered by the ratio test. */
    private fun match(
        matcher: DescriptorMatcher,
        a: FrameFeatures,
        b: FrameFeatures,
    ): List<Pair<Int, Int>> {
        if (a.bearings.isEmpty() || b.bearings.isEmpty()) return emptyList()
        if (a.descriptors.empty() || b.descriptors.empty()) return emptyList()
        val knn = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(a.descriptors, b.descriptors, knn, 2)
            val matched = ArrayList<Pair<Int, Int>>()
            for (result in knn) {
                val candidates = result.toArray()
                if (candidates.size >= 2 &&
                    candidates[0].distance < RATIO_TEST * candidates[1].distance
                ) {
                    matched += candidates[0].queryIdx to candidates[0].trainIdx
                }
            }
            return matched
        } finally {
            knn.forEach { it.release() }
        }
    }

    /**
     * The pose graph: pairs of frames whose images genuinely overlap, capped at
     * [MAX_EDGES_PER_FRAME] per frame and ordered by how much they overlap so
     * the strongest edges are measured first.
     *
     * A pair is a candidate only when [angularOverlap] says its aims fall
     * within one field of view of each other on *both* axes — a directional
     * test, rather than a single angular-distance threshold, because a portrait
     * frame is far taller than it is wide: two aims 55° apart overlap
     * vertically but not at all horizontally, and a distance-only threshold
     * would waste a match on them.
     */
    private fun overlapGraph(
        frames: List<DecodedFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
    ): List<Pair<Int, Int>> {
        val n = frames.size
        val candidates = ArrayList<Triple<Double, Int, Int>>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val overlap = angularOverlap(
                    a = frames[i].sensorBasis,
                    b = frames[j].sensorBasis,
                    horizontalFovDegrees = horizontalFovDegrees,
                    verticalFovDegrees = verticalFovDegrees,
                )
                if (overlap != null) candidates += Triple(overlap, i, j)
            }
        }
        candidates.sortBy { it.first }
        val degree = IntArray(n)
        val edges = ArrayList<Pair<Int, Int>>()
        for ((_, i, j) in candidates) {
            if (degree[i] >= MAX_EDGES_PER_FRAME || degree[j] >= MAX_EDGES_PER_FRAME) continue
            edges += i to j
            degree[i]++
            degree[j]++
        }
        return edges
    }

    private fun seedFor(a: Int, b: Int): Long = (a.toLong() * 31 + b.toLong() * 17) and 0x7fffffffL

    /** Below this depth a projected direction points behind the other lens. */
    private const val MIN_MESH_DEPTH = 0.05

    /**
     * The debug-only local alignment meshes ([LocalWarp.Mesh]), one per frame.
     *
     * For every RANSAC inlier of every accepted edge, each frame's recorded
     * ideal pixel is carried forward: the raw camera bearing goes through the
     * refined pose into world space, then back into the *other* frame through
     * its refined pose and corrected focal scale, which predicts where the
     * two frames should see the same scene point. The difference between that
     * prediction and where the pixel was actually recorded is the parallax
     * residual the global rotation cannot absorb; it lands in the recording
     * frame's cell grid as the sample shift the renderer will add back.
     *
     * The projection is deliberately the plain camera-frame one the renderer
     * itself runs (no pivot referral): the renderer subtracts the pivot's
     * lever arm only from the depth of the canvas direction, and
     * [MIN_MESH_DEPTH] plus the [pivotRatio] subtraction below guard the same
     * degenerate behind-the-lens directions it skips.
     */
    private fun buildLocalWarps(
        frames: List<DecodedFrame>,
        features: List<FrameFeatures>,
        edges: List<RotationMath.RotationEdge>,
        edgeInliers: List<List<Pair<Int, Int>>>,
        refined: List<CameraBasis>,
        focalScales: DoubleArray,
        pivotRatio: Double,
    ): Array<LocalWarp.Mesh?> {
        val grids = Array(frames.size) { frameIndex ->
            val intrinsics = frames[frameIndex].intrinsics
            val (columns, rows) = LocalWarp.cellCounts(
                intrinsics.widthPx.toDouble(),
                intrinsics.heightPx.toDouble(),
            )
            LocalWarp.FrameMeshGrid(
                columns = columns,
                rows = rows,
                widthPx = intrinsics.widthPx.toDouble(),
                heightPx = intrinsics.heightPx.toDouble(),
            )
        }

        // An ideal pixel under the corrected focal length: the focal scale
        // moves every pixel radially out from the optical centre, exactly as
        // the stitcher's scaledBy does to the intrinsics themselves.
        fun scaledPixel(
            frame: Int,
            pixel: DoubleArray,
        ): DoubleArray {
            val intrinsics = frames[frame].intrinsics
            val scale = focalScales[frame]
            return doubleArrayOf(
                intrinsics.centerXPx +
                    (pixel[0] - intrinsics.centerXPx) * scale,
                intrinsics.centerYPx +
                    (pixel[1] - intrinsics.centerYPx) * scale,
            )
        }

        // Where the [from] frame's ideal pixel should appear in the [to]
        // frame, under the refined poses and the corrected focal lengths, in
        // scaled ideal pixels. Null past the edge of the other lens's view.
        fun predictedPixel(
            from: Int,
            to: Int,
            pixel: DoubleArray,
        ): DoubleArray? {
            val source = frames[from].intrinsics
            var x = (pixel[0] - source.centerXPx) / source.focalXPx
            var y = -(pixel[1] - source.centerYPx) / source.focalYPx
            val length = Math.sqrt(x * x + y * y + 1.0)
            x /= length
            y /= length
            val world = refined[from].toWorld(x, y, 1.0 / length)
            val target = frames[to].intrinsics
            val basis = refined[to]
            val depth = basis.depthOf(world[0], world[1], world[2]) - pivotRatio
            if (depth <= MIN_MESH_DEPTH) return null
            val lateral = basis.lateralOf(world[0], world[1], world[2])
            val vertical = basis.verticalOf(world[0], world[1], world[2])
            val scale = focalScales[to]
            return doubleArrayOf(
                target.centerXPx + target.focalXPx * scale * lateral / depth,
                target.centerYPx - target.focalYPx * scale * vertical / depth,
            )
        }

        for (edgeIndex in edges.indices) {
            val edge = edges[edgeIndex]
            for ((fromMatch, toMatch) in edgeInliers[edgeIndex]) {
                val aPixel = scaledPixel(edge.from, features[edge.from].idealPixels[fromMatch])
                val bPixel = scaledPixel(edge.to, features[edge.to].idealPixels[toMatch])
                val predictedInB = predictedPixel(edge.from, edge.to, aPixel) ?: continue
                // The shift this frame's sample needs where the content sits:
                // where it was recorded, minus where the pose predicts it.
                grids[edge.to].add(
                    bPixel[0], bPixel[1],
                    bPixel[0] - predictedInB[0], bPixel[1] - predictedInB[1],
                )
                val predictedInA = predictedPixel(edge.to, edge.from, bPixel)
                if (predictedInA != null) {
                    grids[edge.from].add(
                        aPixel[0], aPixel[1],
                        aPixel[0] - predictedInA[0], aPixel[1] - predictedInA[1],
                    )
                }
            }
        }

        // An empty accumulator builds an all-zero mesh, which the renderer
        // would read as a no-op pass; handing null instead keeps the logs
        // honest about which frames actually learned a correction.
        return Array(frames.size) { index ->
            if (grids[index].isEmpty()) null else grids[index].build()
        }
    }
}
