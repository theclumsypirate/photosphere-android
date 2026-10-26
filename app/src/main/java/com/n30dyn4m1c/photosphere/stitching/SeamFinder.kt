package com.n30dyn4m1c.photosphere.stitching

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The seam-carving front end: decides which frame paints each output pixel.
 *
 * The renderer's cross-fade lets every overlapping frame contribute to a pixel,
 * which is what softens detail along the seams. Seam carving instead asks the
 * graph-cut in [SeamSolver] to assign each pixel to the single frame that
 * agrees best with its neighbours, then paints with a near-hard selection that
 * only cross-fades a few pixels across each cut — see [SeamWeights] and
 * [SeamFeather].
 *
 * The assignment is decided at a reduced resolution ([LABEL_GRID_WIDTH] across
 * the canvas), for three reasons. A seam only needs to be located to within a
 * few output pixels, so the solve can run on a grid a fraction of the size; the
 * graph-cut's cost scales with that grid rather than with the full canvas; and
 * the feather is then computed where it is wide enough to matter. Each grid
 * pixel's direction is projected back into every frame through the same lens
 * model the renderer samples through, the sample's colour is read from the
 * frame's pixels, and the [PackedGrid] the solver needs is built directly.
 *
 * This file is the only OpenCV-dependent part of seam carving; [SeamSolver],
 * [SeamWeights] and [SeamFeather] are pure Kotlin and JVM-tested.
 */
internal object SeamFinder {

    /** Label-grid width for a full canvas; seams are decided at ~this resolution. */
    private const val LABEL_GRID_WIDTH = 512

    /**
     * Long edge frames are downsampled to before their pixels are read for the
     * seam costs.
     *
     * The seam data only needs the *colour* each frame recorded at a pixel, and
     * the grid decides those pixels at ~[LABEL_GRID_WIDTH] across the canvas —
     * there is no detail worth carrying at the frames' full resolution. Working
     * from a ~512-long-edge copy keeps the sampled colours identical to within
     * an average while cutting the memory of holding every frame's pixels to a
     * few megabytes; the full-res copies would otherwise roughly double the
     * peak of an already memory-tight stitch.
     */
    private const val WORK_LONG_EDGE = 512

    /**
     * λ scaling the pairwise seam cost against the per-pixel data costs.
     *
     * The seam cost is the sum of the two colour differences across a cut edge,
     * so with colours in 0..255 an edge that cuts through genuine agreement
     * costs a few tens and one that cuts through a ghost costs hundreds. λ
     * around ten makes the cut follow agreement while still letting the data
     * term break ties in the interiors of overlaps.
     */
    internal const val SMOOTH_LAMBDA = 10.0

    /**
     * How much a sample sitting on its frame's border raises that frame's cost.
     *
     * A frame is least trustworthy at its own edge — radial distortion and
     * vignetting live there — so the data term nudges pixels away from
     * assigning a frame the very edge of its own coverage. The value is large
     * enough to matter against a consensus difference but far smaller than a
     * genuine colour disagreement, so it only moves seams that had nowhere
     * better to go.
     */
    internal const val BORDER_PENALTY = 1000.0

    /** Full-resolution half-width of the cross-fade across a seam, in pixels. */
    private const val FEATHER_FULL_RES_PIXELS = 12

    /**
     * RGB distance (0..441) two gain-corrected covers of one grid node must
     * exceed before the debug moving-object mask declares the node masked.
     *
     * Full-scale difference a large object moving against its own background
     * produces; honest residual disagreement after the gain solve sits far
     * below it. Only read when the mask is switched on.
     */
    internal const val MOVING_MASK_THRESHOLD = 90f

    /** α-expansion passes over the labels; two almost always converges. */
    private const val MAX_PASSES = 2

    /**
     * Computes the per-pixel frame assignment for one render.
     *
     * [frames] are the prepared frames with their corrected poses and lens
     * models. [gains] carries the per-frame exposure compensation so the
     * sampled colours already agree the way the renderer will blend them — the
     * seam is found in the world the renderer is about to paint. The result is
     * a [SeamWeights] at the reduced label resolution, for
     * [EquirectangularRenderer] to look up while it accumulates.
     */
    fun computeSeams(
        frames: List<PreparedFrame>,
        canvasWidth: Int,
        canvasHeight: Int,
        gains: FloatArray?,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        movingMask: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): SeamWeights {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must have extent" }
        val scale = max(1, (canvasWidth + LABEL_GRID_WIDTH - 1) / LABEL_GRID_WIDTH)
        val gridWidth = (canvasWidth + scale - 1) / scale
        val gridHeight = (canvasHeight + scale - 1) / scale

        // Each frame's pixels are read through one flat copy rather than a
        // per-sample Mat.get: the projection back into the frame runs millions
        // of times and a byte buffer indexed by hand is far cheaper than a JNI
        // call per pixel. The copy is made from a small downsampled version so
        // holding every frame at once costs a few megabytes, not the hundreds a
        // full-resolution working set would.
        val sampled = frames.map { downsampleAndRead(it.image) }

        val nodeCount = gridWidth * gridHeight
        val nodeLabelCount = IntArray(nodeCount)
        val nodeMean = FloatArray(nodeCount * 3)

        // Grow-only flat packing for the sparse labels; a long capture touches
        // each pixel two or three times, so the arrays start sized for that and
        // only grow if an overlap is denser than expected.
        var capacity = max(nodeCount * 4, 16)
        var packedLabels = IntArray(capacity)
        var packedData = FloatArray(capacity)
        var packedColors = FloatArray(capacity * 3)
        var packedSize = 0

        val tempCover = IntArray(frames.size)
        val tempColor = FloatArray(frames.size * 3)
        val tempCol = DoubleArray(frames.size)
        val tempRow = DoubleArray(frames.size)
        val scratch = DoubleArray(5)

        val totalProgress = gridHeight + frames.size * MAX_PASSES
        var progressDone = 0

        for (gridRow in 0 until gridHeight) {
            checkCancelled()
            // The sample sits at the centre of the grid pixel's block; a canvas
            // that is not a multiple of the scale has a last block shorter than
            // scale, so the centre is clamped onto the canvas.
            val centerRow = min(gridRow * scale + scale / 2, canvasHeight - 1)
            val latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(
                    centerRow, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees,
                )
            )
            val cosLatitude = cos(latitude)
            val sinLatitude = sin(latitude)
            for (gridCol in 0 until gridWidth) {
                val centerCol = min(gridCol * scale + scale / 2, canvasWidth - 1)
                val longitude = Math.toRadians(
                    Equirectangular.longitudeDegrees(
                        centerCol, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees,
                    )
                )
                val sinLongitude = sin(longitude)
                val cosLongitude = cos(longitude)
                val dirX = sinLongitude * cosLatitude
                val dirY = cosLongitude * cosLatitude
                val dirZ = sinLatitude

                var count = 0
                for (fi in frames.indices) {
                    val working = sampled[fi]
                    if (!sampleInto(
                            frame = frames[fi],
                            bytes = working.pixels,
                            width = working.width,
                            height = working.height,
                            scale = working.scale,
                            x = dirX,
                            y = dirY,
                            z = dirZ,
                            pivotRatio = pivotRatio,
                            out = scratch,
                        )
                    ) {
                        continue
                    }
                    val gain = gains?.getOrNull(fi) ?: 1f
                    tempCover[count] = fi
                    tempColor[count * 3] = (scratch[0] * gain).toFloat()
                    tempColor[count * 3 + 1] = (scratch[1] * gain).toFloat()
                    tempColor[count * 3 + 2] = (scratch[2] * gain).toFloat()
                    tempCol[count] = scratch[3]
                    tempRow[count] = scratch[4]
                    count++
                }

                val node = gridRow * gridWidth + gridCol
                if (count == 0) continue
                nodeLabelCount[node] = count

                var meanR = 0f
                var meanG = 0f
                var meanB = 0f
                for (k in 0 until count) {
                    meanR += tempColor[k * 3]
                    meanG += tempColor[k * 3 + 1]
                    meanB += tempColor[k * 3 + 2]
                }
                meanR /= count
                meanG /= count
                meanB /= count
                val meanBase = node * 3
                nodeMean[meanBase] = meanR
                nodeMean[meanBase + 1] = meanG
                nodeMean[meanBase + 2] = meanB

                // Debug moving-object mask: a node whose covering frames,
                // each already gain-corrected, still disagree by more than
                // [MOVING_MASK_THRESHOLD] is somewhere something moved between
                // the two shots. Leaving it in the solve lets the outlier pull
                // the data term and lets the seam snake around the object to
                // dodge a cost the scene, not the frames, created. The mask
                // instead makes the node neutral: every cover reads the same
                // mean colour there and the border penalty is dropped, so the
                // disagreement drives neither the assignment nor the seam
                // path, and the cut crosses the region for free.
                var masked = false
                if (movingMask && count >= 2) {
                    val limit = MOVING_MASK_THRESHOLD * MOVING_MASK_THRESHOLD
                    outer@ for (a in 0 until count) {
                        for (b in a + 1 until count) {
                            val dr = tempColor[a * 3] - tempColor[b * 3]
                            val dg = tempColor[a * 3 + 1] - tempColor[b * 3 + 1]
                            val db = tempColor[a * 3 + 2] - tempColor[b * 3 + 2]
                            if (dr * dr + dg * dg + db * db > limit) {
                                masked = true
                                break@outer
                            }
                        }
                    }
                }

                for (k in 0 until count) {
                    if (packedSize >= capacity) {
                        capacity *= 2
                        packedLabels = packedLabels.copyOf(capacity)
                        packedData = packedData.copyOf(capacity)
                        packedColors = packedColors.copyOf(capacity * 3)
                    }
                    if (masked) {
                        // Every cover of a masked node sees the mean, so the
                        // pairwise seam cost there is zero however the seam
                        // is cut, and the data term does not judge any frame
                        // by a moving object it did not mean to keep.
                        packedLabels[packedSize] = tempCover[k]
                        packedData[packedSize] = 0f
                        val colorBase = packedSize * 3
                        packedColors[colorBase] = meanR
                        packedColors[colorBase + 1] = meanG
                        packedColors[colorBase + 2] = meanB
                        packedSize++
                        continue
                    }
                    val r = tempColor[k * 3]
                    val g = tempColor[k * 3 + 1]
                    val b = tempColor[k * 3 + 2]
                    val dr = r - meanR
                    val dg = g - meanG
                    val db = b - meanB
                    var data = (dr * dr + dg * dg + db * db).toDouble()
                    data += borderPenalty(frames[tempCover[k]].intrinsics, tempCol[k], tempRow[k])
                    packedLabels[packedSize] = tempCover[k]
                    packedData[packedSize] = data.toFloat()
                    val colorBase = packedSize * 3
                    packedColors[colorBase] = r
                    packedColors[colorBase + 1] = g
                    packedColors[colorBase + 2] = b
                    packedSize++
                }
            }
            progressDone++
            onProgress(progressDone, totalProgress)
        }

        if (packedSize == 0) {
            return SeamWeights(
                gridWidth, gridHeight, scale,
                IntArray(nodeCount) { -1 },
                IntArray(nodeCount) { -1 },
                FloatArray(nodeCount),
            )
        }

        val grid = PackedGrid(
            width = gridWidth,
            height = gridHeight,
            labelCount = frames.size,
            nodeLabelCount = nodeLabelCount,
            nodeLabels = packedLabels,
            nodeData = packedData,
            nodeColors = packedColors,
            nodeMean = nodeMean,
        )
        val labels = SeamSolver.solve(
            grid = grid,
            smoothLambda = SMOOTH_LAMBDA,
            maxPasses = MAX_PASSES,
            onExpansion = {
                progressDone++
                onProgress(progressDone, totalProgress)
            },
            // A full-turn canvas is a cylinder in longitude: the solver and the
            // feather treat the first and last columns as neighbours, so the
            // seam can cross the ±180° meridian without showing a hard edge.
            wrapLongitude = longitudeSpanDegrees >= 360f,
        )
        val halfWidth = max(1, FEATHER_FULL_RES_PIXELS / scale)
        val (loserLabel, loserWeight) = SeamFeather.derive(
            labels, gridWidth, gridHeight, halfWidth,
            wrapLongitude = longitudeSpanDegrees >= 360f,
        )
        return SeamWeights(gridWidth, gridHeight, scale, labels, loserLabel, loserWeight)
    }

    /**
     * Winner-takes-all seams: the prototype's scheme, ported 1:1.
     *
     * Each grid node goes to the covering frame whose sample sits closest to
     * its own optical centre (the [borderPenalty] minimum) — the pixel nearest
     * a frame's middle, where distortion, vignetting and parallax are
     * smallest. No averaging anywhere, so nothing can ghost; the narrow
     * crossfade comes from the same [SeamFeather.derive] the carved path uses.
     * Returns [SeamWeights] shaped exactly like [computeSeams], for the same
     * renderer lookup (-1 where no frame reaches).
     */
    fun computeWinnerSeams(
        frames: List<PreparedFrame>,
        canvasWidth: Int,
        canvasHeight: Int,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): SeamWeights {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must have extent" }
        val scale = max(1, (canvasWidth + LABEL_GRID_WIDTH - 1) / LABEL_GRID_WIDTH)
        val gridWidth = (canvasWidth + scale - 1) / scale
        val gridHeight = (canvasHeight + scale - 1) / scale
        val nodeCount = gridWidth * gridHeight

        val sampled = frames.map { downsampleAndRead(it.image) }
        val labels = IntArray(nodeCount) { -1 }
        val coverLabels = IntArray(frames.size)
        val coverPenalties = DoubleArray(frames.size)
        val scratch = DoubleArray(5)

        for (gridRow in 0 until gridHeight) {
            checkCancelled()
            val centerRow = min(gridRow * scale + scale / 2, canvasHeight - 1)
            val latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(
                    centerRow, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees,
                )
            )
            val cosLatitude = cos(latitude)
            val sinLatitude = sin(latitude)
            for (gridCol in 0 until gridWidth) {
                val centerCol = min(gridCol * scale + scale / 2, canvasWidth - 1)
                val longitude = Math.toRadians(
                    Equirectangular.longitudeDegrees(
                        centerCol, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees,
                    )
                )
                val dirX = sin(longitude) * cosLatitude
                val dirY = cos(longitude) * cosLatitude
                val dirZ = sinLatitude

                var count = 0
                for (fi in frames.indices) {
                    val working = sampled[fi]
                    if (!sampleInto(
                            frame = frames[fi],
                            bytes = working.pixels,
                            width = working.width,
                            height = working.height,
                            scale = working.scale,
                            x = dirX,
                            y = dirY,
                            z = dirZ,
                            pivotRatio = pivotRatio,
                            out = scratch,
                        )
                    ) {
                        continue
                    }
                    coverLabels[count] = fi
                    coverPenalties[count] =
                        borderPenalty(frames[fi].intrinsics, scratch[3], scratch[4])
                    count++
                }
                labels[gridRow * gridWidth + gridCol] =
                    nearestCenter(coverLabels, coverPenalties, count)
            }
            onProgress(gridRow + 1, gridHeight)
        }

        val halfWidth = max(1, FEATHER_FULL_RES_PIXELS / scale)
        val (loserLabel, loserWeight) = SeamFeather.derive(
            labels, gridWidth, gridHeight, halfWidth,
            wrapLongitude = longitudeSpanDegrees >= 360f,
        )
        return SeamWeights(gridWidth, gridHeight, scale, labels, loserLabel, loserWeight)
    }

    /**
     * Which covering frame owns a node: the minimum-penalty one, or -1 when
     * nothing covers it.
     *
     * Pure so the rule is pinnable by a JVM test; the grid loop above only
     * gathers the inputs.
     */
    internal fun nearestCenter(
        labels: IntArray,
        penalties: DoubleArray,
        count: Int,
    ): Int {
        var best = -1
        var bestValue = Double.MAX_VALUE
        for (k in 0 until count) {
            if (penalties[k] < bestValue) {
                bestValue = penalties[k]
                best = labels[k]
            }
        }
        return best
    }

    /**
     * A frame's pixels, downsampled to a small working size for the seam costs.
     *
     * [scale] divides a full-resolution frame pixel to reach this copy, and
     * [width]/[height] are this copy's own extent — both kept so the sampling
     * loop can project through the full-resolution lens model and read through
     * the small buffer in one step.
     */
    internal class WorkingFrame(
        val pixels: ByteArray,
        val width: Int,
        val height: Int,
        val scale: Double,
    )

    /**
     * Downsamples [image] so its long edge is at most [WORK_LONG_EDGE] and
     * copies the result out as one flat BGR byte array.
     *
     * `CV_8UC3` storage is BGR and the copy is the whole image, so sampling a
     * pixel later is one array read plus an index — the access pattern the
     * seam loop actually needs. The downsampled Mat is released once its bytes
     * are in hand.
     */
    internal fun downsampleAndRead(image: Mat): WorkingFrame {
        val sourceWidth = image.cols()
        val sourceHeight = image.rows()
        val longest = max(sourceWidth, sourceHeight)
        val scale = longest / WORK_LONG_EDGE.toDouble()
        val width: Int
        val height: Int
        val bytes: ByteArray
        if (scale <= 1.0) {
            width = sourceWidth
            height = sourceHeight
            bytes = ByteArray(sourceWidth * sourceHeight * image.channels())
            image.get(0, 0, bytes)
        } else {
            width = max(1, (sourceWidth / scale).roundToInt())
            height = max(1, (sourceHeight / scale).roundToInt())
            val small = Mat()
            try {
                Imgproc.resize(
                    image, small,
                    Size(width.toDouble(), height.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
                bytes = ByteArray(width * height * small.channels())
                small.get(0, 0, bytes)
            } finally {
                small.release()
            }
        }
        return WorkingFrame(bytes, width, height, scale)
    }

    /**
     * Samples the pixel of [frame] that looks in direction ([x], [y], [z]).
     *
     * The direction is rotated into the frame's axes, divided through by depth
     * and pushed through the lens's radial distortion — the same projection
     * `projectDirection` performs, inlined here so the millions of calls
     * allocate nothing. The resulting full-resolution pixel is divided by
     * [scale] to land in the downsampled [bytes]. [out] carries back the RGB
     * sample in 0..2 and the full-resolution pixel position in 3..4 (for the
     * caller's border penalty, which is scale-invariant).
     */
    internal fun sampleInto(
        frame: PreparedFrame,
        bytes: ByteArray,
        width: Int,
        height: Int,
        scale: Double,
        x: Double,
        y: Double,
        z: Double,
        pivotRatio: Double,
        out: DoubleArray,
    ): Boolean {
        val basis = frame.basis
        val intrinsics = frame.intrinsics
        val depth = basis.depthOf(x, y, z) - pivotRatio
        if (depth <= MIN_DEPTH) return false
        val centreX = intrinsics.centerXPx
        val centreY = intrinsics.centerYPx
        val idealColumn = centreX + intrinsics.focalXPx * basis.lateralOf(x, y, z) / depth
        val idealRow = centreY - intrinsics.focalYPx * basis.verticalOf(x, y, z) / depth
        var column = idealColumn
        var row = idealRow
        val radial = intrinsics.radial
        if (radial != null) {
            val dx = idealColumn - centreX
            val dy = idealRow - centreY
            val factor = radialFactor(radial, dx * dx + dy * dy)
            column = centreX + dx * factor
            row = centreY + dy * factor
        }
        val col = (column / scale).roundToInt()
        val rowInt = (row / scale).roundToInt()
        if (col < 0 || col >= width || rowInt < 0 || rowInt >= height) return false

        val index = (rowInt * width + col) * 3
        out[0] = (bytes[index + 2].toInt() and 0xff).toDouble()
        out[1] = (bytes[index + 1].toInt() and 0xff).toDouble()
        out[2] = (bytes[index].toInt() and 0xff).toDouble()
        out[3] = column
        out[4] = row
        return true
    }

    /**
     * How much a sample sitting at ([column], [row]) on the frame's border
     * raises that frame's data cost, in 0..[BORDER_PENALTY].
     *
     * Measured against the frame's own optical centre the same way the render
     * feather is, so the penalty is 0 on the optical axis and 1 at the corners.
     */
    private fun borderPenalty(
        intrinsics: FrameIntrinsics,
        column: Double,
        row: Double,
    ): Double {
        val across = 1.0 - abs(column - intrinsics.centerXPx) / intrinsics.centerXPx
        val down = 1.0 - abs(row - intrinsics.centerYPx) / intrinsics.centerYPx
        if (across <= 0.0 || down <= 0.0) return BORDER_PENALTY
        return BORDER_PENALTY * (1.0 - across * down)
    }
}
