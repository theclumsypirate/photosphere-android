package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Debug-only per-block brightness refinement on top of the per-frame gains.
 *
 * The scalar gain solve ([ExposureCompensation]) equalises what each frame
 * averaged overall. The pilot runs came back with per-frame gains spread
 * between 0.840 and 1.379, and that spread landed as visible steps where one
 * frame's blend handed over to its neighbour's: two frames of the same wall
 * disagreeing by a fraction of a stop read as a band, and the whole-frame mean
 * that drove the scalar solve had no way to see a difference that lived inside
 * one frame.
 *
 * This refines the scalar answer with a coarse field. The canvas is divided
 * into a small grid of blocks; each block's raw luminance is sampled per frame
 * through the same projection the seam costs sample through; and a log-gain is
 * solved per frame per block against what the overlapping frames measured in
 * the same block, shrunk toward neutral and clamped to the same rail the
 * scalar solve uses. The result is one small grid of linear refinement factors
 * per frame, which the renderer looks up bilinearly as a per-pixel multiplier
 * sitting on top of the scalar gain.
 *
 * Debug only: it is wired into the stitch behind a boolean that only the
 * debug capture screen sets, and a `null` return leaves the renderer's
 * behaviour exactly as shipping builds see it (see AGENTS.md).
 */
internal object BlockExposure {

    /** Blocks across the canvas; the rows follow the canvas's own aspect. */
    const val GRID_COLUMNS = 64

    /**
     * Gauss-Seidel sweeps. The scalar solve converges in twelve over its
     * smaller graph; this graph has frames times blocks nodes, so a couple of
     * more sweeps keep the worst chain honest.
     */
    private const val ITERATIONS = 20

    /**
     * Fraction of each implied update applied per sweep. The remainder pulls
     * the value toward neutral, which regularises the solve: a block that only
     * one frame ever covers is dragged back to 1 instead of absorbing that
     * frame's whole error.
     */
    private const val UPDATE_SHRINK = 0.7

    /** A frame's block refinement may not leave this ratio far from neutral. */
    private const val MAX_REFINEMENT = 1.6

    /** Below this a block sample is blank and takes no part in the solve. */
    private const val MIN_USABLE_LUMA = 1e-6

    /** Square blocks: full rows over [canvasHeight] tall a canvas. */
    internal fun gridRowsFor(canvasWidth: Int, canvasHeight: Int): Int {
        val blockHeight = (canvasWidth / GRID_COLUMNS).coerceAtLeast(1)
        return max(1, (canvasHeight + blockHeight - 1) / blockHeight)
    }

    /**
     * The per-frame refinement grids for one render.
     *
     * Solves against [frames] (whose bases and intrinsics are the final
     * corrected ones), [scalarGains] the per-frame gains the scalar solve
     * produced, and the render's canvas geometry. [wrapColumns] must match the
     * render's own canvas: a full-turn canvas wraps its first and last
     * columns together, so the field has to wrap with them. Returns null when
     * there is nothing to solve: fewer than two frames, or no usable block
     * sample anywhere, in which case the caller keeps the scalar gains alone.
     */
    fun solve(
        frames: List<PreparedFrame>,
        canvasWidth: Int,
        canvasHeight: Int,
        scalarGains: FloatArray,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        wrapColumns: Boolean,
        checkCancelled: () -> Unit = {},
    ): Fields? {
        if (frames.size < 2) return null
        val gridColumns = GRID_COLUMNS
        val gridRows = gridRowsFor(canvasWidth, canvasHeight)
        val frameCount = frames.size
        check(scalarGains.isEmpty() || scalarGains.size == frameCount) {
            "scalar gain count ${scalarGains.size} does not match $frameCount frames"
        }

        val sampled = frames.map { SeamFinder.downsampleAndRead(it.image) }
        val samples = meanLuma(
            frames = frames,
            sampled = sampled,
            gridColumns = gridColumns,
            gridRows = gridRows,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            pivotRatio = pivotRatio,
            longitudeSpanDegrees = longitudeSpanDegrees,
            centerLongitudeDegrees = centerLongitudeDegrees,
            latitudeSpanDegrees = latitudeSpanDegrees,
            centerLatitudeDegrees = centerLatitudeDegrees,
        )

        val blocks = gridRows * gridColumns
        fun usable(frame: Int, block: Int) =
            samples[frame * blocks + block] > MIN_USABLE_LUMA

        // One edge per covering pair per block, in log-gain space. The scalar
        // gains are already the answer at full-frame level, so each equation
        // says the two frames' scalar-scaled block luma must come out equal
        // once the refinement is applied:
        //
        //   log(refine_a) - log(refine_b) = log(s_b * m_b / (s_a * m_a))
        val (offsets, neighbours, deltas) = buildEquations(
            frameCount = frameCount,
            gridRows = gridRows,
            gridColumns = gridColumns,
            scalarGains = scalarGains,
            samples = samples,
            usable = ::usable,
        )
        if (offsets.last() == 0) return null

        // Gauss-Seidel with the shrink toward neutral standing in for the
        // regulariser. Neutral is zero: the scalar gains are already trusted.
        val logRefine = DoubleArray(frameCount * blocks)
        repeat(ITERATIONS) {
            checkCancelled()
            for (node in 0 until frameCount * blocks) {
                val start = offsets[node]
                val end = offsets[node + 1]
                if (end <= start) continue
                var sum = 0.0
                for (slot in start until end) {
                    sum += logRefine[neighbours[slot]] + deltas[slot]
                }
                logRefine[node] = sum / (end - start) * UPDATE_SHRINK
            }
        }

        // Gauge: the scalar solve pinned its own global level, so the block
        // residuals across every connected entry average out to zero and the
        // sphere is not brightened overall by the refinement.
        var logSum = 0.0
        var connected = 0
        for (node in 0 until frameCount * blocks) {
            if (offsets[node + 1] > offsets[node]) {
                logSum += logRefine[node]
                connected++
            }
        }
        val gauge = if (connected > 0) logSum / connected else 0.0

        val maxLog = ln(MAX_REFINEMENT)
        val refinements = Array(frameCount) { f ->
            FloatArray(blocks) { block ->
                val node = f * blocks + block
                if (offsets[node] >= offsets[node + 1]) {
                    1f
                } else {
                    exp((logRefine[node] - gauge).coerceIn(-maxLog, maxLog)).toFloat()
                }
            }
        }
        return Fields(
            gridColumns = gridColumns,
            gridRows = gridRows,
            wrapColumns = wrapColumns,
            scalarGains = scalarGains,
            refinements = refinements,
        )
    }

    /**
     * Raw block luma per frame per block, in a flat `[frame][row][column]`
     * layout, sampled from each frame's downsampled working copy through the
     * block centre's direction. Gain-free by construction: the solve adds the
     * scalar gains itself.
     */
    private fun meanLuma(
        frames: List<PreparedFrame>,
        sampled: List<SeamFinder.WorkingFrame>,
        gridColumns: Int,
        gridRows: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
    ): DoubleArray {
        val out = DoubleArray(frames.size * gridRows * gridColumns)
        val scratch = DoubleArray(5)
        for (row in 0 until gridRows) {
            val centerRow = min(
                row * canvasHeight / gridRows + canvasHeight / (2 * gridRows),
                canvasHeight - 1,
            )
            val latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(
                    centerRow, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees,
                )
            )
            val cosLatitude = cos(latitude)
            val sinLatitude = sin(latitude)
            for (column in 0 until gridColumns) {
                val centerColumn = min(
                    column * canvasWidth / gridColumns + canvasWidth / (2 * gridColumns),
                    canvasWidth - 1,
                )
                val longitude = Math.toRadians(
                    Equirectangular.longitudeDegrees(
                        centerColumn, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees,
                    )
                )
                val dirX = sin(longitude) * cosLatitude
                val dirY = cos(longitude) * cosLatitude
                val dirZ = sinLatitude
                for (f in frames.indices) {
                    val working = sampled[f]
                    if (!SeamFinder.sampleInto(
                            frame = frames[f],
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
                    val luma = 0.2126 * scratch[0] + 0.7152 * scratch[1] + 0.0722 * scratch[2]
                    out[(f * gridRows + row) * gridColumns + column] = luma
                }
            }
        }
        return out
    }

    /**
     * Packs the block equations: the returned arrays are the flat offsets into
     * the edges of every frame-block node, the neighbour node index relative
     * to the frame's own block numbering, and the log delta along each edge.
     */
    private fun buildEquations(
        frameCount: Int,
        gridRows: Int,
        gridColumns: Int,
        scalarGains: FloatArray,
        samples: DoubleArray,
        usable: (Int, Int) -> Boolean,
    ): Triple<IntArray, IntArray, DoubleArray> {
        val blocks = gridRows * gridColumns
        val totals = IntArray(frameCount * blocks)
        for (block in 0 until blocks) {
            for (a in 0 until frameCount) {
                if (!usable(a, block)) continue
                var degree = 0
                for (b in 0 until frameCount) {
                    if (b != a && usable(b, block)) degree++
                }
                totals[a * blocks + block] = degree
            }
        }
        val offsets = IntArray(frameCount * blocks + 1)
        for (node in 0 until frameCount * blocks) {
            offsets[node + 1] = offsets[node] + totals[node]
        }
        val neighbours = IntArray(offsets.last())
        val deltas = DoubleArray(offsets.last())
        val cursor = offsets.copyOf()
        for (block in 0 until blocks) {
            for (a in 0 until frameCount) {
                if (!usable(a, block)) continue
                val lumaA = samples[a * blocks + block]
                val gainA = scalarGains.getOrNull(a) ?: 1f
                for (b in 0 until frameCount) {
                    if (b == a || !usable(b, block)) continue
                    val lumaB = samples[b * blocks + block]
                    val gainB = scalarGains.getOrNull(b) ?: 1f
                    val index = cursor[a * blocks + block]
                    val ratio = gainB.toDouble() * lumaB / (gainA.toDouble() * lumaA)
                    neighbours[index] = b * blocks + block
                    deltas[index] = if (ratio > 0.0) ln(ratio) else 0.0
                    cursor[a * blocks + block] = index + 1
                }
            }
        }
        return Triple(offsets, neighbours, deltas)
    }

    /**
     * One frame's refinement grid, ready for the renderer per-pixel lookup.
     *
     * Bilinear over the linear refinement factors, wrapped across the seam
     * columns when the render's canvas wraps in longitude, times the frame's
     * scalar gain.
     */
    internal class FrameField(
        private val gridColumns: Int,
        private val gridRows: Int,
        private val scalarGain: Float,
        private val refinements: FloatArray,
        private val wrapColumns: Boolean,
    ) {
        /** Multiplier for a canvas pixel at normalised positions ([u], [v]). */
        fun combinedAt(u: Float, v: Float): Float {
            val x = u * gridColumns - 0.5f
            val y = v * gridRows - 0.5f
            var x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            val x1: Int
            if (wrapColumns && gridColumns > 1) {
                x0 = ((x0 % gridColumns) + gridColumns) % gridColumns
                x1 = (x0 + 1) % gridColumns
            } else {
                x0 = min(max(x0, 0), gridColumns - 1)
                x1 = min(max(x0 + 1, 0), gridColumns - 1)
            }
            val rowA = max(0, min(y0, gridRows - 1))
            val rowB = max(0, min(y0 + 1, gridRows - 1))
            val baseA = rowA * gridColumns
            val baseB = rowB * gridColumns
            val w00 = (1f - fx) * (1f - fy)
            val w01 = fx * (1f - fy)
            val w10 = (1f - fx) * fy
            val w11 = fx * fy
            // solve() clamps every connected node's coefficients inside
            // MAX_REFINEMENT and leaves disconnected ones exactly neutral, so
            // a plain bilinear read is safe here.
            var refine = refinements[baseA + x0] * w00
            refine += refinements[baseA + x1] * w01
            refine += refinements[baseB + x0] * w10
            refine += refinements[baseB + x1] * w11
            return scalarGain * refine
        }
    }

    /**
     * Everything the renderer needs to turn a scalar gain into a per-pixel
     * one. [frame] returns null for an index past the solved frames, and the
     * renderer then falls back to the plain scalar path.
     */
    class Fields(
        val gridColumns: Int,
        val gridRows: Int,
        private val wrapColumns: Boolean,
        private val scalarGains: FloatArray,
        private val refinements: Array<FloatArray>,
    ) {
        private val frames = refinements.indices

        fun frame(index: Int): FrameField? =
            if (index in frames) {
                FrameField(
                    gridColumns = gridColumns,
                    gridRows = gridRows,
                    scalarGain = scalarGains.getOrNull(index) ?: 1f,
                    refinements = refinements[index],
                    wrapColumns = wrapColumns,
                )
            } else {
                null
            }
    }
}
