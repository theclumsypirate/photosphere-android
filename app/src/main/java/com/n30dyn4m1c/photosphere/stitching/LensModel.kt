package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.abs

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
 * with `r^2 = x_i^2 + y_i^2`. The coefficients are **unitless** and the
 * coordinates they act on are normalized *by the focal length*: the origin is
 * the optical centre and `x_i = (column − c_x) / f_x`, with `f_x`/`c_x` taken
 * from `LENS_INTRINSIC_CALIBRATION`. This is the OpenCV convention, and it is
 * the whole difference between `LENS_DISTORTION` and the `LENS_RADIAL_DISTORTION`
 * key it replaced — the older key normalized so that the *farthest array edge*
 * sat at ±1, which Android itself describes as "inconsistently defined". The two
 * differ by `(f / halfEdge)^(2n)` per term, a factor of well over two on `k1`
 * for a typical phone lens and more than ten on `k3`, so reading one key through
 * the other's normalization does not merely blur the correction — it multiplies
 * it.
 *
 * The tangential terms of the full model are dropped: they are tiny on phone
 * lenses, and the radial terms carry almost all of the seam error at frame
 * borders.
 *
 * Every pipeline stage applies the same model in the same pixel space as the
 * frame it is looking at — the renderer samples through it, the footprint walks
 * the distorted border, and pose refinement undistorts keypoints — so a
 * direction, its ideal pixel and its distorted pixel always agree.
 * [pixelCoefficientsFor] is what converts the unitless polynomial into that
 * pixel space, and because the conversion is driven by the focal length *of the
 * frame being worked on*, it is automatically right for a frame decoded at any
 * subsampling factor.
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
