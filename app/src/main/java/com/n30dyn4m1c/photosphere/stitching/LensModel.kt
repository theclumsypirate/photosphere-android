package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.abs

/**
 * Lens projection model used by stitching and R&D pipelines.
 *
 * The default behavior for normal captures remains pinhole projection with
 * optional Brown-Conrady radial distortion. Debug/R&D code can provide other
 * projections (for example fisheye) through this interface, but release capture
 * and stitch defaults must keep the existing behavior.
 */
interface LensProjection {
    /** Projects a camera-frame direction to a distorted/source pixel, or null if invalid. */
    fun project(
        lateral: Double,
        vertical: Double,
        depth: Double,
        intrinsics: FrameIntrinsics,
    ): DoubleArray?

    /** Unprojects a source pixel to an ideal camera-frame direction. */
    fun unproject(
        column: Double,
        row: Double,
        intrinsics: FrameIntrinsics,
    ): DoubleArray

    /** Returns the same projection scaled for focal refinement. */
    fun scaledBy(focalScale: Double, intrinsics: FrameIntrinsics): LensProjection = this
}

/** Default pinhole projection with optional Brown-Conrady radial distortion. */
object PinholeLensProjection : LensProjection {
    override fun project(
        lateral: Double,
        vertical: Double,
        depth: Double,
        intrinsics: FrameIntrinsics,
    ): DoubleArray? {
        if (depth <= MIN_DEPTH) return null
        val centreX = intrinsics.centerXPx
        val centreY = intrinsics.centerYPx
        val idealColumn = centreX + intrinsics.focalXPx * lateral / depth
        val idealRow = centreY - intrinsics.focalYPx * vertical / depth
        val radial = intrinsics.radial
        var sourceColumn = idealColumn
        var sourceRow = idealRow
        if (radial != null) {
            val x = idealColumn - centreX
            val y = idealRow - centreY
            val factor = radialFactor(radial, x * x + y * y)
            sourceColumn = centreX + x * factor
            sourceRow = centreY + y * factor
        }
        return doubleArrayOf(sourceColumn, sourceRow, idealColumn, idealRow)
    }

    override fun unproject(column: Double, row: Double, intrinsics: FrameIntrinsics): DoubleArray {
        val cx = intrinsics.centerXPx
        val cy = intrinsics.centerYPx
        val radial = intrinsics.radial
        val ideal = if (radial != null) {
            undistortPixel(column, row, cx, cy, radial)
        } else {
            doubleArrayOf(column, row)
        }
        val cameraX = (ideal[0] - cx) / intrinsics.focalXPx
        val cameraY = (cy - ideal[1]) / intrinsics.focalYPx
        val invLen = 1.0 / sqrt(cameraX * cameraX + cameraY * cameraY + 1.0)
        return doubleArrayOf(cameraX * invLen, cameraY * invLen, invLen)
    }
}

/**
 * Debug/R&D equidistant fisheye projection.
 *
 * r = f * theta, where theta is the angle between optical axis and ray.
 * Intended for experimental fisheye imports/captures only.
 */
class EquidistantFisheyeProjection(
    val coefficients: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
) : LensProjection {
    override fun project(
        lateral: Double,
        vertical: Double,
        depth: Double,
        intrinsics: FrameIntrinsics,
    ): DoubleArray? {
        val norm = sqrt(lateral * lateral + vertical * vertical + depth * depth)
        if (norm <= 0.0) return null
        val x = lateral / norm
        val y = vertical / norm
        val z = depth / norm
        val theta = kotlin.math.acos(z.coerceIn(-1.0, 1.0))
        val maxTheta = Math.toRadians(intrinsics.maxFovDegrees / 2.0)
        if (theta > maxTheta) return null
        val f = intrinsics.focalPx
        val rIdeal = f * theta
        val phi = atan2(y, x)
        val idealColumn = intrinsics.centerXPx + cos(phi) * rIdeal
        val idealRow = intrinsics.centerYPx - sin(phi) * rIdeal
        val distortedR = applyDistortion(rIdeal / max(1e-6, f), coefficients) * f
        val sourceColumn = intrinsics.centerXPx + cos(phi) * distortedR
        val sourceRow = intrinsics.centerYPx - sin(phi) * distortedR
        return doubleArrayOf(sourceColumn, sourceRow, idealColumn, idealRow)
    }

    override fun unproject(column: Double, row: Double, intrinsics: FrameIntrinsics): DoubleArray {
        val dx = column - intrinsics.centerXPx
        val dy = intrinsics.centerYPx - row
        val rDist = sqrt(dx * dx + dy * dy)
        val f = max(1e-6, intrinsics.focalPx)
        val rIdeal = removeDistortion(rDist / f, coefficients) * f
        val theta = rIdeal / f
        val phi = atan2(dy, dx)
        val sinTheta = sin(theta)
        val x = sinTheta * cos(phi)
        val y = sinTheta * sin(phi)
        val z = cos(theta)
        return doubleArrayOf(x, y, z)
    }

    private fun applyDistortion(r: Double, k: DoubleArray): Double {
        val r2 = r * r
        val r4 = r2 * r2
        val r6 = r4 * r2
        val r8 = r4 * r4
        val k1 = k.getOrElse(0) { 0.0 }
        val k2 = k.getOrElse(1) { 0.0 }
        val k3 = k.getOrElse(2) { 0.0 }
        val k4 = k.getOrElse(3) { 0.0 }
        return r * (1.0 + k1 * r2 + k2 * r4 + k3 * r6 + k4 * r8)
    }

    private fun removeDistortion(rDist: Double, k: DoubleArray): Double {
        var r = rDist
        repeat(12) {
            val fn = applyDistortion(r, k) - rDist
            val dr = max(1e-6, r * 1e-4)
            val df = (applyDistortion(r + dr, k) - applyDistortion(r - dr, k)) / (2.0 * dr)
            if (abs(df) < 1e-9) return@repeat
            val next = r - fn / df
            if (abs(next - r) < 1e-6) { r = next; return@repeat }
            r = next
        }
        return max(0.0, r)
    }
}

/**
 * Brown-Conrady radial lens distortion, in the convention the camera reports it.
 *
 * The camera2 API describes a lens's distortion with the coefficients that map
 * an *undistorted* (ideal pinhole) image coordinate to the *distorted*
 * coordinate that must be sampled in the captured frame
 * (`CameraCharacteristics.LENS_DISTORTION`):
 *
 * ```
 * x_c = x_i * (1 + k1 r^2 + k2 r^4 + k3 r^6)
 * y_c = y_i * (1 + k1 r^2 + k2 r^4 + k3 r^6)
 * ```
 *
 * with `r^2 = x_i^2 + y_i^2`. The coefficients are unitless and the
 * coordinates they act on are normalized by the focal length: the origin is
 * the optical centre and `x_i = (column - c_x) / f_x`, with `f_x`/`c_x` taken
 * from `LENS_INTRINSIC_CALIBRATION`. This is the OpenCV convention.
 *
 * The tangential terms of the full model are dropped: they are tiny on phone
 * lenses, and the radial terms carry almost all of the seam error at frame
 * borders.
 */
class RadialDistortion(
    /** Unitless `[k1, k2, k3]`, applied to focal-length-normalized coordinates. */
    val coefficients: DoubleArray,
) {
    /**
     * True when the coefficients are worth applying at all.
     *
     * A camera that reports all-zero distortion is a pinhole, and carrying an
     * all-zero polynomial through the renderer's inner loop costs three
     * multiplies per pixel for nothing.
     */
    val isSignificant: Boolean
        get() = coefficients.size >= 3 && coefficients.any { abs(it) > 0.0 }

    /**
     * The same distortion re-expressed for a frame whose focal length is
     * [focalLengthPx] pixels.
     *
     * A pixel offset `p` is the normalized offset `p / f`, so the polynomial in
     * normalized `r²` becomes one in pixel `r²` with each term divided by
     * `f^(2·order)`: k1 by f², k2 by f⁴, k3 by f⁶. The result is what
     * [distortPixel] and [undistortPixel] expect: coefficients that take `r²`
     * in pixels.
     *
     * Returns null when there is nothing to correct, which is the signal the
     * rest of the pipeline reads as "treat this lens as a pinhole".
     */
    fun pixelCoefficientsFor(focalLengthPx: Double): DoubleArray? {
        if (!isSignificant || !focalLengthPx.isFinite() || focalLengthPx <= 0.0) return null
        val f2 = focalLengthPx * focalLengthPx
        val f4 = f2 * f2
        val f6 = f4 * f2
        return doubleArrayOf(
            coefficients[0] / f2,
            coefficients[1] / f4,
            coefficients[2] / f6,
        )
    }
}

/**
 * The radial scale factor applied to an offset `r^2` from the optical axis.
 *
 * `1 + k1 r^2 + k2 r^4 + k3 r^6`, folded as `r^2 (k1 + r^2 (k2 + r^2 k3))`.
 * Kept in one place because the renderer inlines it over millions of pixels and
 * the geometry helpers call it for footprints and keypoints — both must use the
 * identical polynomial.
 */
internal fun radialFactor(coefficients: DoubleArray, r2: Double): Double =
    1.0 + r2 * (coefficients[0] + r2 * (coefficients[1] + r2 * coefficients[2]))

/**
 * The distorted source pixel for an ideal pixel at ([column], [row]).
 *
 * The forward (ideal → distorted) direction of the model above: what the lens
 * turns the pinhole projection of a ray into.
 */
internal fun distortPixel(
    column: Double,
    row: Double,
    centreX: Double,
    centreY: Double,
    coefficients: DoubleArray,
): DoubleArray {
    val x = column - centreX
    val y = row - centreY
    val factor = radialFactor(coefficients, x * x + y * y)
    return doubleArrayOf(centreX + x * factor, centreY + y * factor)
}

/** Iterations [undistortPixel] is allowed before it settles for what it has. */
private const val UNDISTORT_ITERATIONS = 12

/** Pixel movement below which the fixed point is close enough to stop. */
private const val UNDISTORT_TOLERANCE_PX = 1e-4

/**
 * The ideal (undistorted) pixel whose [column]/[row] the lens captured.
 *
 * The inverse of [distortPixel]. There is no closed form, so it is inverted
 * with fixed-point iteration: `x_{n+1} = x_d / factor(x_n^2 + y_n^2)` converges
 * quickly because the factor is close to 1 for the modest distortion a phone
 * lens exhibits. The loop stops as soon as the estimate stops moving, which is
 * usually within three passes and matters because pose refinement calls this
 * once per keypoint.
 */
internal fun undistortPixel(
    column: Double,
    row: Double,
    centreX: Double,
    centreY: Double,
    coefficients: DoubleArray,
): DoubleArray {
    val targetX = column - centreX
    val targetY = row - centreY
    var x = targetX
    var y = targetY
    repeat(UNDISTORT_ITERATIONS) {
        val factor = radialFactor(coefficients, x * x + y * y)
        // A factor at or below zero means the polynomial has folded the frame
        // over on itself, which no phone lens does inside its own image circle.
        // Bail to the last good estimate rather than diverging.
        if (factor <= 0.0) return doubleArrayOf(centreX + x, centreY + y)
        val nextX = targetX / factor
        val nextY = targetY / factor
        val settled = abs(nextX - x) < UNDISTORT_TOLERANCE_PX &&
            abs(nextY - y) < UNDISTORT_TOLERANCE_PX
        x = nextX
        y = nextY
        if (settled) return doubleArrayOf(centreX + x, centreY + y)
    }
    return doubleArrayOf(centreX + x, centreY + y)
}
