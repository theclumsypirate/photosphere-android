package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.max
import kotlin.math.min

/**
 * Debug-only local alignment mesh: the APAP idea, in the shape this pipeline
 * can carry.
 *
 * The pose solve is global: one rotation per frame. On a close scene that is
 * the piano-and-TV situation: the rotation the far wall supports cannot also
 * be the rotation the piano two metres nearer demands, because the frames saw
 * the two from slightly translated positions (see [PivotModel]). The residual
 * those parallax-shifted features leave after the global solve is a *local*
 * misalignment, and a single rotation per frame cannot express it. APAP's
 * answer is a local subgrid: estimate the correction neighbourhood by
 * neighbourhood instead of globally.
 *
 * This carries the same idea on this pipeline's terms. The render already maps
 * every canvas pixel into a frame pixel through the frame's own pose, so the
 * mesh is a per-frame grid of small source-pixel corrections, solved from the
 * RANSAC inliers the refinement accepted. Each inlier pair produces a
 * reprojection residual: the pixel of frame A's feature carried into frame B
 * through the refined poses, minus the pixel B actually recorded. Those
 * residuals, averaged over a cell grid, smoothed and clamped, are what the
 * renderer adds to the sampled ideal pixel before the lens model pushes it to
 * the distorted one.
 *
 * The correction is intentionally small: it absorbs parallax, not pose. Cells
 * with no inliers stay at zero apart from what the smoothing bled in from
 * their measured neighbours, and the bilinear lookup keeps the warp
 * continuous.
 *
 * Pure Kotlin, no OpenCV, so the grid maths is unit-testable. Debug only: it
 * is wired behind a capture-screen debug switch and never runs in a release
 * build (see AGENTS.md on the frozen normal capture path).
 */
object LocalWarp {

    /** Rough block long edge, in the frame's own pixels. */
    private const val CELL_LONG_EDGE_PX = 220.0

    /** A correction bigger than this fraction of the long edge is a bad solve. */
    private const val MAX_CORRECTION_SHARE = 0.02

    /** Mean-smoothing passes over the cell grid before the mesh is built. */
    private const val SMOOTH_PASSES = 2

    /**
     * One frame's correction grid, in ideal source pixels.
     *
     * [corrections] holds `2 * columns * rows` floats: for each cell, the
     * delta column and then the delta row.
     */
    class Mesh(
        val columns: Int,
        val rows: Int,
        val corrections: FloatArray,
    ) {
        /**
         * The bilinear correction at an ideal pixel, as `dx, dy` in source
         * pixels. Outside the frame it degenerates to the nearest cell, which
         * stays at or near zero by construction.
         */
        fun correctionAt(
            columnPx: Double,
            rowPx: Double,
            widthPx: Double,
            heightPx: Double,
        ): FloatArray {
            val u = columnPx / max(1.0, widthPx) * columns - 0.5
            val v = rowPx / max(1.0, heightPx) * rows - 0.5
            var x0 = Math.floor(u).toInt()
            val fx = u - x0
            val y0 = Math.floor(v).toInt()
            val fy = v - y0
            x0 = min(max(x0, 0), columns - 1)
            val x1 = min(max(x0 + 1, 0), columns - 1)
            val rowA = min(max(y0, 0), rows - 1)
            val rowB = min(max(y0 + 1, 0), rows - 1)
            val a = rowA * columns
            val b = rowB * columns
            val w00 = (1.0 - fx) * (1.0 - fy)
            val w01 = fx * (1.0 - fy)
            val w10 = (1.0 - fx) * fy
            val w11 = fx * fy
            val i00 = (a + x0) * 2
            val i01 = (a + x1) * 2
            val i10 = (b + x0) * 2
            val i11 = (b + x1) * 2
            val dx = corrections[i00] * w00 + corrections[i01] * w01 +
                corrections[i10] * w10 + corrections[i11] * w11
            val dy = corrections[i00 + 1] * w00 + corrections[i01 + 1] * w01 +
                corrections[i10 + 1] * w10 + corrections[i11 + 1] * w11
            return floatArrayOf(dx.toFloat(), dy.toFloat())
        }
    }

    /**
     * The accumulation grid of one frame: residuals added at ideal pixels,
     * averaged per cell, smoothed and clamped into a [Mesh] on [build].
     */
    class FrameMeshGrid(
        val columns: Int,
        val rows: Int,
        private val widthPx: Double,
        private val heightPx: Double,
    ) {
        private val sums = DoubleArray(columns * rows * 2)
        private val counts = IntArray(columns * rows)

        /**
         * Accumulates one inlier residual at an ideal source pixel. Residuals
         * landing fractionally outside the frame are clamped onto the border
         * instead of dropped: a feature at the very edge is exactly where the
         * parallax correction matters most.
         */
        fun add(columnPx: Double, rowPx: Double, dx: Double, dy: Double) {
            val clampedColumn = min(max(columnPx, 0.0), widthPx - 1.0)
            val clampedRow = min(max(rowPx, 0.0), heightPx - 1.0)
            val cell = clampedCell(clampedColumn, clampedRow)
            sums[cell * 2] += dx
            sums[cell * 2 + 1] += dy
            counts[cell]++
        }

        /** Whether any inlier landed anywhere on this frame's grid. */
        fun isEmpty(): Boolean = counts.sum() == 0

        private fun clampedCell(columnPx: Double, rowPx: Double): Int {
            val cellColumn = min(
                (columnPx * columns / widthPx).toInt(),
                columns - 1,
            )
            val cellRow = min(
                (rowPx * rows / heightPx).toInt(),
                rows - 1,
            )
            return cellRow * columns + cellColumn
        }

        /**
         * Builds the mesh: per-cell mean residual, smoothed [SMOOTH_PASSES]
         * times so a lone noisy inlier cannot pull one cell, and clamped so a
         * bad solve can never re-warp a frame by more than
         * [MAX_CORRECTION_SHARE] of its long edge.
         */
        fun build(): Mesh {
            val cells = columns * rows
            val mean = DoubleArray(cells * 2)
            for (cell in 0 until cells) {
                val count = counts[cell]
                if (count > 0) {
                    mean[cell * 2] = sums[cell * 2] / count
                    mean[cell * 2 + 1] = sums[cell * 2 + 1] / count
                }
            }
            var field = mean
            repeat(SMOOTH_PASSES) {
                field = smoothPass(field)
            }
            // The clamp is on the long edge, the user-visible warp: a 2000 px
            // frame may be corrected by at most 40 px, which is far more than
            // the parallax residual two frames a metre apart produce.
            val longEdge = max(widthPx, heightPx)
            val limit = MAX_CORRECTION_SHARE * longEdge
            return Mesh(
                columns = columns,
                rows = rows,
                corrections = FloatArray(cells * 2) { index ->
                    min(max(field[index], -limit), limit).toFloat()
                },
            )
        }

        /** One 3x3 mean pass over a two-channel cell field. */
        private fun smoothPass(field: DoubleArray): DoubleArray {
            val out = DoubleArray(field.size)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    var sumX = 0.0
                    var sumY = 0.0
                    var weight = 0.0
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val r = row + dr
                            val c = column + dc
                            if (r < 0 || r >= rows || c < 0 || c >= columns) continue
                            val index = (r * columns + c) * 2
                            sumX += field[index]
                            sumY += field[index + 1]
                            weight += 1.0
                        }
                    }
                    val cell = (row * columns + column) * 2
                    out[cell] = sumX / weight
                    out[cell + 1] = sumY / weight
                }
            }
            return out
        }
    }

    /**
     * Cell counts for a [widthPx] x [heightPx] frame: square-ish cells of
     * about [CELL_LONG_EDGE_PX], never fewer than 2 along either axis.
     */
    internal fun cellCounts(widthPx: Double, heightPx: Double): Pair<Int, Int> {
        var columns = (widthPx / CELL_LONG_EDGE_PX).toInt().coerceAtLeast(2)
        var rows = (heightPx / CELL_LONG_EDGE_PX).toInt().coerceAtLeast(2)
        // A cell grid of hundreds would be a per-pixel warp in disguise; a
        // sparse grid is the point.
        columns = min(columns, 24)
        rows = min(rows, 24)
        return columns to rows
    }
}
