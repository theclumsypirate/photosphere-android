package com.n30dyn4m1c.photosphere.stitching

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One frame ready to be painted onto the sphere: its pixels, where the camera
 * was, and the block of canvas it can reach.
 *
 * [image] is `CV_8UC3` and belongs to whoever built the frame — the renderer
 * reads from it and never releases it.
 */
internal class PreparedFrame(
    val image: Mat,
    val basis: CameraBasis,
    val intrinsics: FrameIntrinsics,
    val footprint: CanvasFootprint,
)

/**
 * Paints frames onto an equirectangular canvas.
 *
 * Each output pixel is a direction on the sphere. For every frame that can see
 * that direction, the direction is rotated into the frame's own axes and divided
 * through by depth, which gives the pixel of the frame that looked at it — the
 * inverse of the projection the lens performed. `remap` then does the sampling.
 *
 * Where frames overlap, the result is a multi-band blend rather than whichever
 * frame was painted last. The feather weight becomes each frame's mask, and the
 * mask and the frame are each split into a Laplacian/Gaussian pyramid: fine
 * detail is faded across a narrow cross-fade, broad illumination across a wide
 * one, so a seam that survives one band does not survive the one that carried
 * it. See [MultibandBlender] for the maths.
 *
 * With a [SeamWeights] assignment the per-pixel feather is replaced by the seam
 * weights: the winning frame of each pixel contributes at full strength and
 * every other frame contributes nothing, except for a few pixels either side of
 * each cut where the loser's contribution ramps in — seam carving, with the
 * multi-band machinery left to handle the narrow transition. Outside an overlap
 * the weights are still all on one frame, so single-coverage detail comes
 * through untouched either way. See [SeamFinder].
 *
 * **Memory.** A 4096-wide canvas needs 100 MB of float accumulator if it is
 * held all at once, which is exactly the kind of allocation that ends a stitch
 * on a mid-range phone. Every level is therefore built in horizontal bands:
 * only the band being accumulated exists in float, and each frame contributes
 * through the intersection of the band with its own footprint. The coarser
 * levels, which carry the wide cross-fade, live in mats a fraction of the
 * canvas. The total work is unchanged — every frame still touches each of its
 * pixels once at every level — but the peak is tens of megabytes rather than a
 * hundred.
 */
internal object EquirectangularRenderer {

    /** Rows accumulated at once. Sets the renderer's peak memory, with the width. */
    private const val BAND_HEIGHT = 128

    /** What a render produced, and how much of the sphere it reached. */
    class Rendered(
        /** The finished `CV_8UC3` canvas. The caller owns it. */
        val canvas: Mat,
        /** Fraction of the canvas any frame reached, 0..1. */
        val coverage: Float,
    )

    /**
     * Renders [frames] into a [canvasWidth] × [canvasHeight] canvas.
     *
     * [gains], when given, holds one brightness multiplier per frame, aligned by
     * position with [frames]; it is applied before the overlaps are blended so
     * the exposure compensation lands inside the blend rather than on top of
     * it. [onBandComplete] is called with the number of units finished so far
     * and the total, for progress reporting; [checkCancelled] is called at
     * every band and level boundary and may throw to abandon the render.
     *
     * [pivotRatio] is [PivotModel.ratio]: how far the lens sat from the axis the
     * user turned about, as a fraction of the scene's distance. Zero treats the
     * lens as the pivot, which is what a tripod gives and what a hand-held
     * capture never quite does.
     *
     * [seams], when given, switches the overlaps from the wide cross-fade to the
     * seam-carved assignment (see [SeamWeights]): each pixel is painted by one
     * frame at full strength, and only a few pixels across each cut blend the
     * loser in. Without it the render behaves exactly as before.
     *
     * The four span/centre numbers describe the region of the sphere the canvas
     * holds (see [Equirectangular]); they default to a full 360°×180° sphere.
     * The canvas is expected to be sized to them — `canvasHeight` should be
     * `canvasWidth * latitudeSpan / longitudeSpan` for square pixels.
     */
    fun render(
        frames: List<PreparedFrame>,
        canvasWidth: Int,
        canvasHeight: Int,
        gains: FloatArray? = null,
        seams: SeamWeights? = null,
        /**
         * Debug-only per-block refinement ([BlockExposure.Fields]). When
         * present, a frame's brightness is looked up per canvas pixel through
         * it instead of through the scalar [gains] entry alone; null keeps
         * the scalar path exactly as shipping builds see it.
         */
        gainFields: BlockExposure.Fields? = null,
        pivotRatio: Double = 0.0,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
        onBandComplete: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Rendered {
        val canvas = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3)
        var coveredPixels = 0L
        // Full-resolution coverage mask, 255 where a frame reached. One byte
        // per pixel, filled band by band next to the float accumulators, so
        // the finished canvas knows exactly which pixels no frame touched.
        val covered = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
        // One set of scratch buffers for the whole render. A pole-covering frame
        // reaches the full canvas width, so a per-segment allocation would churn
        // tens of megabytes of float array per band across forty frames.
        val scratch = SegmentScratch()

        // Progress is reported in roughly uniform units: one per coarse level,
        // one per band of the coarsest two levels' reconstruction. The single
        // level below, if there is one, just swaps the units.
        val levelCount = MultibandBlender.levelCountFor(canvasHeight)
        val levelOneBandCount = if (levelCount >= 3) {
            val heightOne = MultibandBlender.levelSize(canvasWidth, canvasHeight, 1).second
            (heightOne + BAND_HEIGHT - 1) / BAND_HEIGHT
        } else 0
        val levelZeroBandCount = (canvasHeight + BAND_HEIGHT - 1) / BAND_HEIGHT
        val progressTotal = (levelCount - 1) + levelOneBandCount + levelZeroBandCount
        var progressDone = 0

        // The coarse accumulator chain and the level-one accumulator are owned
        // at this scope, not inside the happy path, so a render that fails
        // midway can still release every pyramid mat the happy path had not
        // got to (see the catch below). Mat.release is idempotent: releasing
        // an accumulator the happy path already released — or one it aliases
        // (`levelOneAcc` is `acc` on a two-level canvas) — is safe.
        val coarse = ArrayList<MultibandBlender.PyramidLevel>(levelCount - 1)
        var acc: Mat? = null
        var levelOneAcc: Mat? = null

        try {
            if (levelCount <= 1) {
                // Degenerate canvas (shorter than the coarsest band): the
                // multi-band split has nowhere to go, so this is the plain
                // normalised mean the blend reduces to with a single level.
                for (band in 0 until levelZeroBandCount) {
                    checkCancelled()
                    val bandTop = band * BAND_HEIGHT
                    val bandHeight = min(BAND_HEIGHT, canvasHeight - bandTop)
                    val color = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC3)
                    val weight = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC1)
                    try {
                        accumulateLevel(
                            frames = frames,
                            colorSum = color,
                            weightSum = weight,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            bandTop = bandTop,
                            bandHeight = bandHeight,
                            sourceScale = 1,
                            gains = gains,
                            gainFields = gainFields,
                            seams = seams,
                            pivotRatio = pivotRatio,
                            longitudeSpanDegrees = longitudeSpanDegrees,
                            centerLongitudeDegrees = centerLongitudeDegrees,
                            latitudeSpanDegrees = latitudeSpanDegrees,
                            centerLatitudeDegrees = centerLatitudeDegrees,
                            scratch = scratch,
                        )
                        coveredPixels += Core.countNonZero(weight)
                        markCovered(weight, covered, bandTop, bandHeight)
                        val normalized = MultibandBlender.normalizeColor(color, weight)
                        val region = canvas.submat(Rect(0, bandTop, canvasWidth, bandHeight))
                        try {
                            normalized.convertTo(region, CvType.CV_8UC3)
                        } finally {
                            region.release()
                        }
                        normalized.release()
                    } finally {
                        color.release()
                        weight.release()
                    }
                    progressDone++
                    onBandComplete(progressDone, progressTotal)
                }
            } else {
                // -- Level accumulation --------------------------------------
                // Levels 1..L accumulate over their whole (small) canvas. Level
                // 0 accumulates band by band, later, once its coarser content
                // has been rebuilt.
                for (level in 1 until levelCount) {
                    checkCancelled()
                    val (width, height) = MultibandBlender.levelSize(canvasWidth, canvasHeight, level)
                    val color = Mat.zeros(height, width, CvType.CV_32FC3)
                    val weight = Mat.zeros(height, width, CvType.CV_32FC1)
                    accumulateLevel(
                        frames = frames,
                        colorSum = color,
                        weightSum = weight,
                        canvasWidth = width,
                        canvasHeight = height,
                        bandTop = 0,
                        bandHeight = height,
                        sourceScale = 1 shl level,
                        gains = gains,
                        gainFields = gainFields,
                        seams = seams,
                        pivotRatio = pivotRatio,
                        longitudeSpanDegrees = longitudeSpanDegrees,
                        centerLongitudeDegrees = centerLongitudeDegrees,
                        latitudeSpanDegrees = latitudeSpanDegrees,
                        centerLatitudeDegrees = centerLatitudeDegrees,
                        scratch = scratch,
                    )
                    coarse += MultibandBlender.PyramidLevel(color, weight)
                    progressDone++
                    onBandComplete(progressDone, progressTotal)
                }

                // -- Coarse reconstruction ------------------------------------
                // Levels L down to 2 rebuild whole-canvas; they are small, and
                // the bands they expand into are a fraction of the canvas.
                acc = MultibandBlender.normalizeColor(
                    coarse[levelCount - 2].color,
                    coarse[levelCount - 2].weight,
                )
                for (level in (levelCount - 2) downTo 2) {
                    checkCancelled()
                    // The accumulator from the previous pass (or the
                    // initialisation above) is always set here; the compiler
                    // cannot see that through the loop, so the rebuild and the
                    // release work from a non-null local.
                    val current = checkNotNull(acc) {
                        "coarse accumulator missing at level $level"
                    }
                    val (width, height) = MultibandBlender.levelSize(canvasWidth, canvasHeight, level)
                    val (coarseWidth, coarseHeight) =
                        MultibandBlender.levelSize(canvasWidth, canvasHeight, level + 1)
                    val next = MultibandBlender.reconstructBand(
                        colorBand = coarse[level - 1].color,
                        weightBand = coarse[level - 1].weight,
                        coarseAcc = current,
                        coarseColor = coarse[level].color,
                        coarseWeight = coarse[level].weight,
                        canvasWidth = width,
                        canvasHeight = height,
                        coarseWidth = coarseWidth,
                        coarseHeight = coarseHeight,
                        bandTop = 0,
                        bandHeight = height,
                    )
                    current.release()
                    acc = next
                    // The level this iteration just folded in (level 3 or up) is
                    // spent; level 2 is still needed by the level-1 bands, and
                    // level 1 by the canvas.
                    coarse[level].release()
                }
                // acc is now level 2's reconstruction; coarse[1] holds level
                // 2's colour sum and coarse[0] level 1's sums. A canvas that
                // split into only two levels has no level 2, and acc already is
                // level 1's reconstruction.

                // -- Level-1 reconstruction, banded --------------------------
                val (levelOneWidth, levelOneHeight) =
                    MultibandBlender.levelSize(canvasWidth, canvasHeight, 1)
                if (levelCount >= 3) {
                    val (levelTwoWidth, levelTwoHeight) =
                        MultibandBlender.levelSize(canvasWidth, canvasHeight, 2)
                    levelOneAcc = Mat.zeros(levelOneHeight, levelOneWidth, CvType.CV_32FC3)
                    for (band in 0 until levelOneBandCount) {
                        checkCancelled()
                        val bandTop = band * BAND_HEIGHT
                        val bandHeight = min(BAND_HEIGHT, levelOneHeight - bandTop)
                        val colorBand = coarse[0].color.submat(Rect(0, bandTop, levelOneWidth, bandHeight))
                        val weightBand = coarse[0].weight.submat(Rect(0, bandTop, levelOneWidth, bandHeight))
                        val target = levelOneAcc.submat(Rect(0, bandTop, levelOneWidth, bandHeight))
                        val band = MultibandBlender.reconstructBand(
                            colorBand = colorBand,
                            weightBand = weightBand,
                            coarseAcc = acc,
                            coarseColor = coarse[1].color,
                            coarseWeight = coarse[1].weight,
                            canvasWidth = levelOneWidth,
                            canvasHeight = levelOneHeight,
                            coarseWidth = levelTwoWidth,
                            coarseHeight = levelTwoHeight,
                            bandTop = bandTop,
                            bandHeight = bandHeight,
                        )
                        try {
                            band.copyTo(target)
                        } finally {
                            band.release()
                            colorBand.release()
                            weightBand.release()
                            target.release()
                        }
                        progressDone++
                        onBandComplete(progressDone, progressTotal)
                    }
                    // The level-2 content has done its job; level 1's weight sum
                    // is still needed by the level-0 bands below.
                    acc.release()
                    coarse[1].release()
                } else {
                    // Two levels only: acc already is level 1's reconstruction.
                    levelOneAcc = acc
                }

                // -- Level-0 reconstruction, banded, into the canvas ----------
                // Exactly one of the branches above built the level-one
                // accumulator; from here it is owned by the level-0 bands and
                // released once they are done.
                val levelOne = checkNotNull(levelOneAcc) {
                    "level-one accumulator was not built"
                }
                for (band in 0 until levelZeroBandCount) {
                    checkCancelled()
                    val bandTop = band * BAND_HEIGHT
                    val bandHeight = min(BAND_HEIGHT, canvasHeight - bandTop)
                    val color = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC3)
                    val weight = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC1)
                    try {
                        accumulateLevel(
                            frames = frames,
                            colorSum = color,
                            weightSum = weight,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            bandTop = bandTop,
                            bandHeight = bandHeight,
                            sourceScale = 1,
                            gains = gains,
                            gainFields = gainFields,
                            seams = seams,
                            pivotRatio = pivotRatio,
                            longitudeSpanDegrees = longitudeSpanDegrees,
                            centerLongitudeDegrees = centerLongitudeDegrees,
                            latitudeSpanDegrees = latitudeSpanDegrees,
                            centerLatitudeDegrees = centerLatitudeDegrees,
                            scratch = scratch,
                        )
                        coveredPixels += Core.countNonZero(weight)
                        markCovered(weight, covered, bandTop, bandHeight)
                        val band = MultibandBlender.reconstructBand(
                            colorBand = color,
                            weightBand = weight,
                            coarseAcc = levelOne,
                            coarseColor = coarse[0].color,
                            coarseWeight = coarse[0].weight,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            coarseWidth = levelOneWidth,
                            coarseHeight = levelOneHeight,
                            bandTop = bandTop,
                            bandHeight = bandHeight,
                        )
                        val region = canvas.submat(Rect(0, bandTop, canvasWidth, bandHeight))
                        try {
                            band.convertTo(region, CvType.CV_8UC3)
                        } finally {
                            region.release()
                        }
                        band.release()
                    } finally {
                        color.release()
                        weight.release()
                    }
                    progressDone++
                    onBandComplete(progressDone, progressTotal)
                }
                levelOne.release()
                coarse[0].color.release()
                coarse[0].weight.release()
            }
        } catch (e: Throwable) {
            // The happy path releases the pyramid progressively as it rebuilds
            // it; on a failure it has released only the levels it got past, so
            // release whatever is still held. Mat.release is idempotent, and
            // `levelOneAcc` may alias `acc` on a two-level canvas, so
            // releasing any of these twice is safe.
            coarse.forEach { it.release() }
            acc?.release()
            levelOneAcc?.release()
            canvas.release()
            covered.release()
            throw e
        }

        val total = canvasWidth.toLong() * canvasHeight
        val coverage = (coveredPixels.toDouble() / total).toFloat()
        try {
            fillSmallHoles(canvas, covered, coverage)
        } finally {
            covered.release()
        }
        return Rendered(canvas, coverage = coverage)
    }

    /**
     * Copies the covered pixels of one full-resolution weight band into the
     * canvas-sized [covered] mask (255 = a frame reached the pixel).
     */
    private fun markCovered(
        weight: Mat,
        covered: Mat,
        bandTop: Int,
        bandHeight: Int,
    ) {
        val binary = Mat()
        try {
            Core.compare(weight, Scalar(0.0), binary, Core.CMP_GT)
            val region = covered.submat(Rect(0, bandTop, covered.cols(), bandHeight))
            try {
                binary.copyTo(region)
            } finally {
                region.release()
            }
        } finally {
            binary.release()
        }
    }

    /**
     * Paints over the small uncovered patches of an almost-complete sphere.
     *
     * Sensor poses drift by a degree or two between neighbouring frames, so a
     * run where every target was captured can still come back at 99%: thin
     * slivers between frames that no frame touched, rendered black. Inpainting
     * grows the surrounding content into them, which reads as sphere rather
     * than a black corner. Runs under [FILL_MIN_COVERAGE] keep their black:
     * a half-missing sphere is an honest gap the user should re-shoot, not a
     * smear.
     */
    private fun fillSmallHoles(canvas: Mat, covered: Mat, coverage: Float) {
        if (coverage < FILL_MIN_COVERAGE || coverage >= 1f) return
        val holes = Mat()
        val dilated = Mat()
        val filled = Mat()
        try {
            Core.bitwise_not(covered, holes)
            // A few pixels past the hole edge so the dark fringe blends too.
            Imgproc.dilate(
                holes, dilated,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0)),
            )
            Photo.inpaint(canvas, dilated, filled, 8.0, Photo.INPAINT_TELEA)
            filled.copyTo(canvas)
            Logging.i("Filled pinhole gaps at ${(coverage * 100).toInt()}% coverage")
        } finally {
            holes.release()
            dilated.release()
            filled.release()
        }
    }

    /** Coverage at or above which the leftover holes are filled, not kept. */
    private const val FILL_MIN_COVERAGE = 0.95f

    /**
     * Accumulates every frame that reaches one band of one pyramid level into
     * [colorSum] and [weightSum].
     *
     * [canvasWidth]/[canvasHeight] are the *level's* canvas size and
     * [bandTop]/[bandHeight] the band within it (the whole canvas for levels
     * above the finest). [sourceScale] is `2^level`: at level 0 it is 1 and
     * the frames are sampled whole; above that each frame is downsampled to a
     * quarter the area first, so the warp lands on the level's coarse Gaussian
     * rather than a decimated copy of the full-res one.
     */
    private fun accumulateLevel(
        frames: List<PreparedFrame>,
        colorSum: Mat,
        weightSum: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        bandTop: Int,
        bandHeight: Int,
        sourceScale: Int,
        gains: FloatArray?,
        seams: SeamWeights?,
        gainFields: BlockExposure.Fields? = null,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        scratch: SegmentScratch,
    ) {
        val divisor = sourceScale.toDouble()
        val interpolation =
            if (sourceScale == 1) Imgproc.INTER_LANCZOS4 else Imgproc.INTER_LINEAR
        for (frameIndex in frames.indices) {
            val frame = frames[frameIndex]
            val footprint = scaleFootprint(frame.footprint, sourceScale)
            if (footprint.isEmpty) continue

            val rowStart = max(bandTop, footprint.startRow)
            val rowEnd = min(bandTop + bandHeight, footprint.startRow + footprint.rowSpan)
            if (rowEnd <= rowStart) continue

            val ownsSource = sourceScale > 1
            val source = if (ownsSource) {
                val downsampled = Mat()
                Imgproc.resize(
                    frame.image, downsampled,
                    Size(
                        (frame.intrinsics.widthPx / sourceScale).coerceAtLeast(1).toDouble(),
                        (frame.intrinsics.heightPx / sourceScale).coerceAtLeast(1).toDouble(),
                    ),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
                downsampled
            } else {
                frame.image
            }
            try {
                forEachColumnRun(
                    footprint,
                    canvasWidth,
                    longitudeSpanDegrees,
                ) { canvasColumn, columnSpan ->
                    accumulateSegment(
                        frame = frame,
                        frameIndex = frameIndex,
                        source = source,
                        sourceDivisor = divisor,
                        interpolation = interpolation,
                        colorSum = colorSum,
                        weightSum = weightSum,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        bandTop = bandTop,
                        rowStart = rowStart,
                        rowCount = rowEnd - rowStart,
                        columnStart = canvasColumn,
                        columnCount = columnSpan,
                        gain = gains?.getOrNull(frameIndex) ?: 1f,
                        gainField = gainFields?.frame(frameIndex),
                        seams = seams,
                        pivotRatio = pivotRatio,
                        longitudeSpanDegrees = longitudeSpanDegrees,
                        centerLongitudeDegrees = centerLongitudeDegrees,
                        latitudeSpanDegrees = latitudeSpanDegrees,
                        centerLatitudeDegrees = centerLatitudeDegrees,
                        scratch = scratch,
                    )
                }
            } finally {
                if (ownsSource) source.release()
            }
        }
    }

    /**
     * Projects one rectangle of canvas back through a frame and adds it in.
     *
     * The two trigonometric factors of a direction separate — longitude varies
     * only across columns and latitude only down rows — so the per-column parts
     * are computed once for the whole rectangle and the inner loop is left with
     * a couple of multiplies per pixel.
     *
     * [source] is the frame's image, either whole ([sourceDivisor] 1) or
     * downsampled by [sourceDivisor] — the map coordinates are divided by it so
     * they land in the right pixels either way. The feather weight is measured
     * against the *ideal* (pinhole) pixel at full resolution, which is
     * scale-invariant.
     */
    private fun accumulateSegment(
        frame: PreparedFrame,
        frameIndex: Int,
        source: Mat,
        sourceDivisor: Double,
        interpolation: Int,
        colorSum: Mat,
        weightSum: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        bandTop: Int,
        rowStart: Int,
        rowCount: Int,
        columnStart: Int,
        columnCount: Int,
        gain: Float,
        seams: SeamWeights?,
        gainField: BlockExposure.FrameField? = null,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        scratch: SegmentScratch,
    ) {
        if (rowCount <= 0 || columnCount <= 0) return

        val basis = frame.basis
        val intrinsics = frame.intrinsics
        val centreX = intrinsics.centerXPx
        val centreY = intrinsics.centerYPx
        val maxColumn = intrinsics.widthPx - 1.0
        val maxRow = intrinsics.heightPx - 1.0
        val radial = intrinsics.radial

        scratch.prepare(rowCount, columnCount)

        // Longitude is periodic, so a column that was unwrapped past the seam
        // gives the same direction as the column it wraps onto — the canvas
        // column can be used directly here. On a region canvas this holds too:
        // only [forEachColumnRun] decides whether the seam wraps or clips.
        val forwardHorizontal = scratch.forwardHorizontal
        val rightHorizontal = scratch.rightHorizontal
        val upHorizontal = scratch.upHorizontal
        for (offset in 0 until columnCount) {
            val longitude = Math.toRadians(
                Equirectangular.longitudeDegrees(
                    columnStart + offset,
                    canvasWidth,
                    longitudeSpanDegrees,
                    centerLongitudeDegrees,
                )
            )
            val sinLongitude = sin(longitude)
            val cosLongitude = cos(longitude)
            forwardHorizontal[offset] = sinLongitude * basis.forwardX + cosLongitude * basis.forwardY
            rightHorizontal[offset] = sinLongitude * basis.rightX + cosLongitude * basis.rightY
            upHorizontal[offset] = sinLongitude * basis.upX + cosLongitude * basis.upY
        }

        val mapX = scratch.mapX
        val mapY = scratch.mapY
        val weights = scratch.weights
        val gainMap = if (gainField != null) scratch.gains else null
        var anyCovered = false

        for (rowOffset in 0 until rowCount) {
            val latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(
                    rowStart + rowOffset,
                    canvasHeight,
                    latitudeSpanDegrees,
                    centerLatitudeDegrees,
                )
            )
            val cosLatitude = cos(latitude)
            val sinLatitude = sin(latitude)
            val rowBase = rowOffset * columnCount

            for (columnOffset in 0 until columnCount) {
                // The canvas direction is a unit vector from the pivot, and the
                // lens sits `pivotRatio` of the way out along its own optical
                // axis (see PivotModel). The lever arm is parallel to forward, so
                // it drops out of the two lateral components and shortens the
                // depth alone — the whole parallax correction is this subtraction.
                val depth = cosLatitude * forwardHorizontal[columnOffset] +
                    sinLatitude * basis.forwardZ - pivotRatio
                val index = rowBase + columnOffset
                if (depth <= MIN_DEPTH) {
                    // Behind the camera. A map coordinate outside the source
                    // makes remap emit the border colour, and the zero weight
                    // keeps it out of the blend regardless.
                    mapX[index] = -1f
                    mapY[index] = -1f
                    weights[index] = 0f
                    continue
                }

                val lateral = cosLatitude * rightHorizontal[columnOffset] +
                    sinLatitude * basis.rightZ
                val vertical = cosLatitude * upHorizontal[columnOffset] + sinLatitude * basis.upZ

                // The lens model in two steps: the ideal pinhole pixel, then the
                // radial push to where the lens actually recorded it. The ideal
                // pixel is what the feather measures against — it is proportional
                // to the angle off the optical axis — while the distorted pixel
                // is what `remap` samples.
                val idealColumn = centreX + intrinsics.focalXPx * lateral / depth
                val idealRow = centreY - intrinsics.focalYPx * vertical / depth
                var sourceColumn = idealColumn
                var sourceRow = idealRow
                if (radial != null) {
                    val x = idealColumn - centreX
                    val y = idealRow - centreY
                    val factor = radialFactor(radial, x * x + y * y)
                    sourceColumn = centreX + x * factor
                    sourceRow = centreY + y * factor
                }
                if (sourceColumn < 0.0 || sourceColumn > maxColumn ||
                    sourceRow < 0.0 || sourceRow > maxRow
                ) {
                    mapX[index] = -1f
                    mapY[index] = -1f
                    weights[index] = 0f
                    continue
                }

                mapX[index] = (sourceColumn / sourceDivisor).toFloat()
                mapY[index] = (sourceRow / sourceDivisor).toFloat()
                val weight = if (seams != null) {
                    // Seam carving: the winner paints at full strength, the
                    // loser only within a few pixels of the cut. The canvas
                    // row/column are the level's own coordinates, which the
                    // seam grid maps back from.
                    seams.weightFor(
                        frameIndex,
                        rowStart + rowOffset,
                        columnStart + columnOffset,
                        sourceDivisor.toInt(),
                    )
                } else {
                    featherWeight(idealColumn, idealRow, centreX, centreY).toFloat()
                }
                weights[index] = weight
                if (gainField != null) {
                    // Debug per-block exposure: the frame's brightness is
                    // looked up from its refinement grid in canvas space, so
                    // a brighter seam band gets the bigger part of the
                    // correction where the frames actually overlap. The
                    // per-pixel lookup costs a bilinear read on the small
                    // grid, which is nothing next to the projection above.
                    val u =
                        (columnStart + columnOffset).toFloat() / max(1, canvasWidth - 1).toFloat()
                    val v =
                        (rowStart + rowOffset).toFloat() / max(1, canvasHeight - 1).toFloat()
                    gainMap!![index] = gainField.combinedAt(u, v)
                }
                if (weight > 0f) anyCovered = true
            }
        }

        if (!anyCovered) return

        val mapXMat = Mat(rowCount, columnCount, CvType.CV_32FC1)
        val mapYMat = Mat(rowCount, columnCount, CvType.CV_32FC1)
        val weightMat = Mat(rowCount, columnCount, CvType.CV_32FC1)
        val gainMat = if (gainField != null) Mat(rowCount, columnCount, CvType.CV_32FC1) else Mat()
        val warped = Mat()
        val warpedFloat = Mat()
        val weight3 = Mat()
        val gain3 = if (gainField != null) Mat(rowCount, columnCount, CvType.CV_32FC3) else Mat()
        try {
            // The scratch arrays are sized to the largest segment seen so far
            // and so are usually longer than this one. `Mat.put` clamps the copy
            // to the matrix's own element count, which is exactly the leading
            // `rowCount * columnCount` entries these loops just filled.
            mapXMat.put(0, 0, mapX)
            mapYMat.put(0, 0, mapY)
            weightMat.put(0, 0, weights)
            if (gainField != null) gainMat.put(0, 0, gainMap)

            // Lanczos on the finest level, linear above it: the canvas is now
            // often rendered close to the frames' own resolution, and the
            // sharper kernel keeps the edge detail that a linear filter would
            // soften. Coarse levels sample an already-downsampled source, where
            // linear is all there is to keep.
            Imgproc.remap(
                source,
                warped,
                mapXMat,
                mapYMat,
                interpolation,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0),
            )
            warped.convertTo(warpedFloat, CvType.CV_32FC3)
            Core.merge(listOf(weightMat, weightMat, weightMat), weight3)
            Core.multiply(warpedFloat, weight3, warpedFloat)
            if (gainField != null) {
                // Debug per-block exposure: the per-pixel lookup is merged
                // into three channels once and multiplied like the scalar
                // gain path would, so the blend sees exactly the brightness
                // the per-block solve asked for.
                Core.merge(listOf(gainMat, gainMat, gainMat), gain3)
                Core.multiply(warpedFloat, gain3, warpedFloat)
            } else if (gain != 1f) {
                Core.multiply(warpedFloat, Scalar(gain.toDouble(), gain.toDouble(), gain.toDouble()), warpedFloat)
            }

            val target = Rect(columnStart, rowStart - bandTop, columnCount, rowCount)
            val colorRegion = colorSum.submat(target)
            val weightRegion = weightSum.submat(target)
            try {
                Core.add(colorRegion, warpedFloat, colorRegion)
                Core.add(weightRegion, weightMat, weightRegion)
            } finally {
                colorRegion.release()
                weightRegion.release()
            }
        } finally {
            mapXMat.release()
            mapYMat.release()
            weightMat.release()
            gainMat.release()
            warped.release()
            warpedFloat.release()
            weight3.release()
            gain3.release()
        }
    }

    /**
     * A footprint in level-[scale] (power-of-two) canvas coordinates.
     *
     * The level-0 footprint is divided by [scale] with the bounds pushed out a
     * row and a column, so the coarse warp never misses a border the sampled
     * footprint was one pixel short of — the projection's own bounds check
     * trims any overreach.
     */
    internal fun scaleFootprint(footprint: CanvasFootprint, scale: Int): CanvasFootprint {
        if (scale <= 1) return footprint
        val startColumn = Math.floorDiv(footprint.startColumn, scale) - 1
        val endColumn = Math.floorDiv(footprint.startColumn + footprint.columnSpan - 1, scale) + 2
        val startRow = Math.floorDiv(footprint.startRow, scale) - 1
        val endRow = Math.floorDiv(footprint.startRow + footprint.rowSpan - 1, scale) + 2
        return CanvasFootprint(
            startColumn = startColumn,
            columnSpan = endColumn - startColumn,
            startRow = startRow,
            rowSpan = endRow - startRow,
        )
    }

    /**
     * Splits a footprint's column range into runs of contiguous canvas columns.
     *
     * A footprint that crosses the ±180° seam is one unbroken band of longitude
     * but two blocks of canvas, so [block] is called twice for it and once for
     * everything else. That seam is only periodic when the canvas holds a full
     * turn of longitude: a region canvas (fewer than 360° of it) has an edge
     * that is a real cut, so a footprint running past either edge is *clipped*
     * there instead — the far side is outside the captured region and stays
     * black. The span never exceeds the canvas width, so a full-turn canvas
     * never produces more than two runs.
     */
    private inline fun forEachColumnRun(
        footprint: CanvasFootprint,
        canvasWidth: Int,
        longitudeSpanDegrees: Float,
        block: (canvasColumn: Int, columnSpan: Int) -> Unit,
    ) {
        val span = min(footprint.columnSpan, canvasWidth)
        if (span <= 0) return
        if (longitudeSpanDegrees >= 360f) {
            val first = wrapColumn(footprint.startColumn, canvasWidth)
            val firstSpan = min(span, canvasWidth - first)
            block(first, firstSpan)
            val remainder = span - firstSpan
            if (remainder > 0) block(0, remainder)
        } else {
            val first = max(footprint.startColumn, 0)
            val last = min(footprint.startColumn + span, canvasWidth)
            if (last > first) block(first, last - first)
        }
    }

    /**
     * Grow-only working buffers for one segment's projection.
     *
     * A render walks every frame across every band and level, and each visit
     * needs three float arrays the size of the rectangle it is painting plus
     * three the width of it. Allocating those per visit is tens of megabytes of
     * short-lived array per band on a wide canvas — enough garbage to stall a
     * stitch on a mid-range phone. The buffers are only ever grown, so after
     * the first few segments the render allocates nothing at all.
     *
     * Confined to the rendering thread; a render is single-threaded by
     * construction, and one scratch is created per [render] call.
     */
    private class SegmentScratch {
        var mapX: FloatArray = FloatArray(0)
            private set
        var mapY: FloatArray = FloatArray(0)
            private set
        var weights: FloatArray = FloatArray(0)
            private set
        var gains: FloatArray = FloatArray(0)
            private set
        var forwardHorizontal: DoubleArray = DoubleArray(0)
            private set
        var rightHorizontal: DoubleArray = DoubleArray(0)
            private set
        var upHorizontal: DoubleArray = DoubleArray(0)
            private set

        fun prepare(rowCount: Int, columnCount: Int) {
            val pixels = rowCount * columnCount
            if (mapX.size < pixels) {
                mapX = FloatArray(pixels)
                mapY = FloatArray(pixels)
                weights = FloatArray(pixels)
                gains = FloatArray(pixels)
            }
            if (forwardHorizontal.size < columnCount) {
                forwardHorizontal = DoubleArray(columnCount)
                rightHorizontal = DoubleArray(columnCount)
                upHorizontal = DoubleArray(columnCount)
            }
        }
    }
}
