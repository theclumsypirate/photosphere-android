package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the camera was pointing when a frame was shot.
 *
 * Angles follow the conventions the sensor layer publishes: [yawDegrees] is the
 * camera's compass bearing and [pitchDegrees] is **negative above the horizon**.
 * [elevationDegrees] flips the pitch into the sign the geometry here works in,
 * so the rest of this file can read "up is positive" throughout.
 */
data class CameraPose(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    /**
     * The camera's basis as a rotation matrix, when the pose was measured as
     * one — in the [CameraBasis.toRotationMatrix] layout (the `[right, −up,
     * forward]` columns in the world frame). The sensor layer provides it for
     * every captured frame: the dwell is averaged as *rotations*, because the
     * Euler components collapse into each other at the zenith (pitch ±90°),
     * where yaw alone cannot say which way is up in the frame.
     * [CameraBasis.of] prefers it when present; the angles are kept for EXIF,
     * diagnostics and the viewfinder.
     */
    val matrix: DoubleArray? = null,
) {
    /** Height above the horizon, positive up. */
    val elevationDegrees: Float get() = -pitchDegrees
}

/**
 * The camera's axes expressed in the world frame (X east, Y north, Z up).
 *
 * This is the same triple `SphereProjection` builds to place markers on the
 * viewfinder, kept in its own type here because the stitcher needs it per frame
 * and evaluates it against millions of directions — the components are held as
 * flat doubles so the projection loop allocates nothing.
 *
 * The three vectors are orthonormal and left-handed: `up = right × forward`,
 * so `right × up = −forward`.
 */
class CameraBasis private constructor(
    val forwardX: Double,
    val forwardY: Double,
    val forwardZ: Double,
    val rightX: Double,
    val rightY: Double,
    val rightZ: Double,
    val upX: Double,
    val upY: Double,
    val upZ: Double,
) {
    /** Component of a world direction along the optical axis — its depth. */
    fun depthOf(x: Double, y: Double, z: Double): Double =
        x * forwardX + y * forwardY + z * forwardZ

    /** Component across the frame, positive to the right of the image. */
    fun lateralOf(x: Double, y: Double, z: Double): Double =
        x * rightX + y * rightY + z * rightZ

    /** Component up the frame, positive towards the top of the image. */
    fun verticalOf(x: Double, y: Double, z: Double): Double =
        x * upX + y * upY + z * upZ

    /**
     * Turns a direction in the camera's own frame back into a world direction.
     *
     * The inverse of the three projections above. Used to walk a frame's border
     * out onto the sphere when working out which part of the canvas it covers.
     */
    fun toWorld(cameraX: Double, cameraY: Double, cameraZ: Double): DoubleArray = doubleArrayOf(
        cameraX * rightX + cameraY * upX + cameraZ * forwardX,
        cameraX * rightY + cameraY * upY + cameraZ * forwardY,
        cameraX * rightZ + cameraY * upZ + cameraZ * forwardZ,
    )

    /**
     * This basis as a row-major 3×3 rotation matrix.
     *
     * The camera's own (right, up, forward) triple is left-handed — `up` is
     * built as `right × forward` — so those columns would form a reflection,
     * and the rotation algebra downstream (quaternion means, pose refinement)
     * assumes proper rotations: an improper matrix smuggles a 90° rotation
     * through the quaternion conversion. The exchange format therefore mirrors
     * the up axis: the columns are `[right, −up, forward]`, the right-handed
     * counterpart of the camera frame. [fromRotationMatrix] is the exact
     * inverse, and the sensor layer writes the same layout in
     * `cameraBasisMatrix`.
     */
    fun toRotationMatrix(): DoubleArray = doubleArrayOf(
        rightX, -upX, forwardX,
        rightY, -upY, forwardY,
        rightZ, -upZ, forwardZ,
    )

    /** The [yawDegrees]/[pitchDegrees]/[rollDegrees] this basis was built from. */
    fun toPose(): CameraPose {
        val yaw = Math.toDegrees(atan2(forwardX, forwardY)).toFloat()
        val elevation = Math.toDegrees(asin(forwardZ.coerceIn(-1.0, 1.0))).toFloat()
        // Rebuild the unrolled pair for this yaw/elevation and measure how far
        // the actual up/right pair has rolled about forward.
        val yawR = Math.toRadians(yaw.toDouble())
        val elevationR = Math.toRadians(elevation.toDouble())
        val sinYaw = sin(yawR)
        val cosYaw = cos(yawR)
        val sinElevation = sin(elevationR)
        val up0X = -sinYaw * sinElevation
        val up0Y = -cosYaw * sinElevation
        val up0Z = cos(elevationR)
        val roll = Math.toDegrees(
            atan2(
                -(rightX * up0X + rightY * up0Y + rightZ * up0Z),
                upX * up0X + upY * up0Y + upZ * up0Z,
            )
        ).toFloat()
        return CameraPose(yawDegrees = yaw, pitchDegrees = -elevation, rollDegrees = roll)
    }

    companion object {
        /**
         * Builds the basis for [pose].
         *
         * Forward is where the lens points. "Right" is the bearing a quarter
         * turn clockwise from the aim and stays horizontal for an unrolled
         * device; "up" completes the right-handed triple. Roll then turns that
         * pair about the forward axis — both are perpendicular to it, so
         * Rodrigues' formula collapses to a plain rotation within their plane.
         */
        fun of(pose: CameraPose): CameraBasis {
            // A measured basis is exact where the angles are not: the sensor
            // averaged the dwell as a rotation, and at the zenith the angles
            // alone cannot say which way is up in the frame. It wins when
            // present.
            pose.matrix?.let { return fromRotationMatrix(it) }

            val yaw = Math.toRadians(pose.yawDegrees.toDouble())
            val elevation = Math.toRadians(pose.elevationDegrees.toDouble())
            val roll = Math.toRadians(pose.rollDegrees.toDouble())

            val sinYaw = sin(yaw)
            val cosYaw = cos(yaw)
            val cosElevation = cos(elevation)

            val forwardX = sinYaw * cosElevation
            val forwardY = cosYaw * cosElevation
            val forwardZ = sin(elevation)

            val right0X = cosYaw
            val right0Y = -sinYaw
            // right0 × forward, with right0.z == 0 folded through.
            val up0X = right0Y * forwardZ
            val up0Y = -right0X * forwardZ
            val up0Z = right0X * forwardY - right0Y * forwardX

            val cosRoll = cos(roll)
            val sinRoll = sin(roll)
            return CameraBasis(
                forwardX = forwardX,
                forwardY = forwardY,
                forwardZ = forwardZ,
                rightX = right0X * cosRoll - up0X * sinRoll,
                rightY = right0Y * cosRoll - up0Y * sinRoll,
                rightZ = -up0Z * sinRoll,
                upX = up0X * cosRoll + right0X * sinRoll,
                upY = up0Y * cosRoll + right0Y * sinRoll,
                upZ = up0Z * cosRoll,
            )
        }

        /**
         * Builds the basis a row-major rotation matrix describes — the inverse
         * of [toRotationMatrix]. [matrix] is expected to be orthonormal and
         * right-handed, as the refinement pipeline produces.
         *
         * The vectors live in the matrix's *columns*, matching [toRotationMatrix]:
         * the storage is `[right, −up, forward]` laid out one component-row at a
         * time, so a column is read at stride 3 — and the up column is mirrored
         * back to the camera's own left-handed triple. Reading the rows instead
         * would transpose the basis — which for a level frame collapses every
         * forward onto due north and stacks all the frames of a capture on one
         * longitude.
         */
        fun fromRotationMatrix(matrix: DoubleArray): CameraBasis = CameraBasis(
            rightX = matrix[0],
            rightY = matrix[3],
            rightZ = matrix[6],
            upX = -matrix[1],
            upY = -matrix[4],
            upZ = -matrix[7],
            forwardX = matrix[2],
            forwardY = matrix[5],
            forwardZ = matrix[8],
        )
    }
}

/**
 * The pinhole model of one captured frame.
 *
 * Focal length is kept per axis rather than as one number. Both are derived from
 * the same physical sensor so they ought to agree, but the field of view is an
 * estimate read from optics the camera may describe loosely, and splitting the
 * axes guarantees the frame's full width maps to the full horizontal field of
 * view and its full height to the vertical one. A single focal length would let
 * a small inconsistency between the two show up as a systematic gap or overlap
 * between neighbouring frames.
 */
data class FrameIntrinsics(
    val widthPx: Int,
    val heightPx: Int,
    val focalXPx: Double,
    val focalYPx: Double,
    /**
     * Brown-Conrady radial coefficients `[k1, k2, k3]`, already rescaled to this
     * frame's focal length (see [RadialDistortion.pixelCoefficientsFor]), or
     * null when the camera does not describe its distortion.
     */
    val radial: DoubleArray? = null,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "frame must have positive extent" }
        require(focalXPx > 0.0 && focalYPx > 0.0) { "focal length must be positive" }
    }

    val centerXPx: Double get() = widthPx / 2.0
    val centerYPx: Double get() = heightPx / 2.0

    /**
     * The single focal length the radial model is normalized against.
     *
     * The distortion polynomial takes one scalar `r²`, while the projection
     * keeps a focal length per axis. Square pixels make the two agree, so the
     * geometric mean is exact for any real sensor and degrades gracefully if a
     * loosely-described lens makes them differ slightly.
     */
    val focalPx: Double get() = sqrt(focalXPx * focalYPx)

    /**
     * The same lens with its focal length multiplied by [focalScale].
     *
     * This is what pose refinement's focal correction hands down to the
     * renderer: the per-frame scale that the feature correspondences said the
     * reported field of view was off by. Both axes are scaled together, because
     * the correction is a single scale on a single physical lens, and the
     * radial coefficients are re-normalised against the *new* focal length —
     * a pixel offset `p` is the focal-normalized offset `p / f`, so the
     * polynomial in `r²` gains a factor `1 / f^(2·order)` per term
     * (`k1` by `s²`, `k2` by `s⁴`, `k3` by `s⁶`). Without that the lens would
     * be described by one focal length and corrected by another, which is
     * exactly the inconsistency the refinement exists to remove.
     */
    fun scaledBy(focalScale: Double): FrameIntrinsics {
        if (focalScale <= 0.0 || abs(focalScale - 1.0) < 1e-6) return this
        val scaledRadial = radial?.let { coefficients ->
            var divisor = focalScale * focalScale
            val out = DoubleArray(coefficients.size)
            for (index in coefficients.indices) {
                out[index] = coefficients[index] / divisor
                divisor *= focalScale * focalScale
            }
            out
        }
        return copy(
            focalXPx = focalXPx * focalScale,
            focalYPx = focalYPx * focalScale,
            radial = scaledRadial,
        )
    }

    companion object {
        /**
         * Intrinsics of a [widthPx] × [heightPx] frame covering the given
         * angles, with [radial] already in this frame's pixel space.
         *
         * Prefer [forLens] when the coefficients come from the camera — it does
         * the conversion from the unitless polynomial for you, against the
         * focal length these very intrinsics imply.
         */
        fun fromFieldOfView(
            widthPx: Int,
            heightPx: Int,
            horizontalFovDegrees: Float,
            verticalFovDegrees: Float,
            radial: DoubleArray? = null,
        ): FrameIntrinsics {
            require(horizontalFovDegrees in 1f..179f) { "horizontal fov: $horizontalFovDegrees" }
            require(verticalFovDegrees in 1f..179f) { "vertical fov: $verticalFovDegrees" }
            return FrameIntrinsics(
                widthPx = widthPx,
                heightPx = heightPx,
                focalXPx = (widthPx / 2.0) / tanHalf(horizontalFovDegrees),
                focalYPx = (heightPx / 2.0) / tanHalf(verticalFovDegrees),
                radial = radial,
            )
        }

        /**
         * Intrinsics for a frame shot through [distortion].
         *
         * The lens's unitless coefficients are converted against the focal
         * length this frame implies, which is what makes the conversion correct
         * at whatever size the frame happened to be decoded to.
         */
        fun forLens(
            widthPx: Int,
            heightPx: Int,
            horizontalFovDegrees: Float,
            verticalFovDegrees: Float,
            distortion: RadialDistortion?,
        ): FrameIntrinsics {
            val pinhole = fromFieldOfView(
                widthPx = widthPx,
                heightPx = heightPx,
                horizontalFovDegrees = horizontalFovDegrees,
                verticalFovDegrees = verticalFovDegrees,
            )
            val radial = distortion?.pixelCoefficientsFor(pinhole.focalPx) ?: return pinhole
            return pinhole.copy(radial = radial)
        }

        private fun tanHalf(degrees: Float): Double = tan(Math.toRadians(degrees / 2.0))
    }
}

/**
 * Blend weight for a pixel that landed at ([idealColumn], [idealRow]) in a
 * frame, 0 at the border and 1 on the optical axis.
 *
 * This is the feather that turns an overlap into a cross-fade. It is measured
 * against the *ideal* (pinhole) pixel rather than the distorted one because the
 * ideal offset is proportional to the angle off the optical axis, which is what
 * "how squarely did this frame see that direction" actually means.
 *
 * Both factors are clamped at zero before they meet: radial distortion can pull
 * a pixel whose ideal position is outside the frame back inside the distorted
 * bounds, and two negative factors would otherwise multiply into a *positive*
 * weight for a direction the frame never really saw. Raised to a power so the
 * frame that sees a shared direction most head-on wins the blend rather than
 * smearing it across a wide linear cross-fade.
 */
internal fun featherWeight(
    idealColumn: Double,
    idealRow: Double,
    centreX: Double,
    centreY: Double,
): Double {
    val across = 1.0 - abs(idealColumn - centreX) / centreX
    if (across <= 0.0) return 0.0
    val down = 1.0 - abs(idealRow - centreY) / centreY
    if (down <= 0.0) return 0.0
    val linear = across * down
    // Cubed by hand: this runs once per canvas pixel per frame, where a
    // `Math.pow` call is several times the cost of two multiplies.
    return linear * linear * linear
}

/**
 * The equirectangular canvas: longitude across, latitude down.
 *
 * The canvas is always drawn with square pixels — a row maps to the same number
 * of degrees as a column — so its shape follows the extent of sphere it covers.
 * A full sphere spans 360° of longitude and 180° of latitude, which is the 2:1
 * canvas everything else assumes by default. A ring capture spans the same 360°
 * of longitude but only the band of latitude the frames cover, and a hemisphere
 * would span 180° about its own centre. The four helpers here all take that
 * window ([longitudeSpanDegrees]/[centerLongitudeDegrees] and
 * [latitudeSpanDegrees]/[centerLatitudeDegrees]) with the full-sphere values as
 * defaults, and the renderer, the footprints and the stitcher thread the same
 * four numbers through.
 *
 * Longitude runs `center - span/2`..`center + span/2` left to right and matches
 * the yaw convention used everywhere else, so a frame shot facing north lands
 * in the middle of a full-sphere canvas. Pixel centres are sampled at the
 * half-pixel.
 */
object Equirectangular {

    /** Longitude sampled at the centre of column [col]. */
    fun longitudeDegrees(
        col: Int,
        width: Int,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
    ): Double = centerLongitudeDegrees +
        (col + 0.5) / width * longitudeSpanDegrees - longitudeSpanDegrees / 2

    /** Latitude sampled at the centre of row [row]. */
    fun latitudeDegrees(
        row: Int,
        height: Int,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
    ): Double = centerLatitudeDegrees + latitudeSpanDegrees / 2 -
        (row + 0.5) / height * latitudeSpanDegrees

    /** Column a longitude falls in. May sit outside `0..width-1` before wrapping. */
    fun columnFor(
        longitudeDegrees: Double,
        width: Int,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
    ): Double =
        (longitudeDegrees - centerLongitudeDegrees + longitudeSpanDegrees / 2) /
            longitudeSpanDegrees * width - 0.5

    /** Row a latitude falls in, clamped to the canvas. */
    fun rowFor(
        latitudeDegrees: Double,
        height: Int,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
    ): Double =
        (centerLatitudeDegrees + latitudeSpanDegrees / 2 - latitudeDegrees) /
            latitudeSpanDegrees * height - 0.5

    /** Unit world direction at a longitude/latitude, in the X east, Y north, Z up frame. */
    fun direction(longitudeDegrees: Double, latitudeDegrees: Double): DoubleArray {
        val lon = Math.toRadians(longitudeDegrees)
        val lat = Math.toRadians(latitudeDegrees)
        val cosLat = cos(lat)
        return doubleArrayOf(sin(lon) * cosLat, cos(lon) * cosLat, sin(lat))
    }

    /** Longitude of a world direction, -180°..180°. */
    fun longitudeOf(x: Double, y: Double): Double = Math.toDegrees(atan2(x, y))

    /** Latitude of a *unit* world direction, -90°..90°. */
    fun latitudeOf(z: Double): Double = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
}

/**
 * The block of canvas one frame can contribute to.
 *
 * [startColumn] may be negative and `startColumn + columnSpan` may run past the
 * canvas width: a frame straddling the ±180° seam covers a contiguous band of
 * longitude that is split in the canvas, and it is cheaper to describe that as
 * one unwrapped range than to force every caller to reason about two. Rows never
 * wrap — latitude is clamped at the poles — so [startRow] and [rowSpan] are
 * always within the canvas.
 */
data class CanvasFootprint(
    val startColumn: Int,
    val columnSpan: Int,
    val startRow: Int,
    val rowSpan: Int,
) {
    val isEmpty: Boolean get() = columnSpan <= 0 || rowSpan <= 0

    /** True when the footprint runs off the right edge and resumes on the left. */
    fun wrapsSeam(canvasWidth: Int): Boolean =
        startColumn < 0 || startColumn + columnSpan > canvasWidth
}

/**
 * Works out which part of the canvas a frame can paint.
 *
 * A frame covers a curved quadrilateral on the sphere, so the bounds are found
 * by walking its border rather than by transforming its four corners: at wide
 * fields of view the mid-edge of a frame reaches a higher latitude than either
 * corner does, and a corners-only box would clip the top of every frame.
 *
 * Longitude is unwrapped relative to the frame's own centre, which is what lets
 * a frame spanning the ±180° seam come back as one contiguous range.
 *
 * A frame that contains a pole is the special case: every longitude is inside
 * it, so no bounding range in longitude is meaningful and the footprint opens
 * out to the full width of the canvas.
 */
object FrameFootprint {

    /** Border samples per edge. Enough to catch the bulge at a 70° field of view. */
    private const val SAMPLES_PER_EDGE = 24

    /**
     * Bounds of what [basis]/[intrinsics] can paint on a [canvasWidth] canvas.
     *
     * [marginPx] pads the result, covering the difference between the sampled
     * border and the true one between samples. The four span/centre numbers
     * describe the region of the sphere the canvas holds (see
     * [Equirectangular]); they default to the full sphere.
     */
    fun compute(
        basis: CameraBasis,
        intrinsics: FrameIntrinsics,
        canvasWidth: Int,
        canvasHeight: Int,
        marginPx: Int = 2,
        pivotRatio: Double = 0.0,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
    ): CanvasFootprint {
        val centreLongitude = Equirectangular.longitudeOf(basis.forwardX, basis.forwardY)

        var minLongitude = Double.MAX_VALUE
        var maxLongitude = -Double.MAX_VALUE
        var minLatitude = Double.MAX_VALUE
        var maxLatitude = -Double.MAX_VALUE

        forEachBorderDirection(intrinsics) { cameraX, cameraY ->
            val ray = basis.toWorld(cameraX, cameraY, 1.0)
            val length = length(ray)
            // The canvas is indexed by directions from the *pivot*, so a border
            // ray has to be followed out to the scene and looked back along from
            // there. With no lever arm the two are the same direction.
            val world = pivotDirection(
                basis = basis,
                pivotRatio = pivotRatio,
                x = ray[0] / length,
                y = ray[1] / length,
                z = ray[2] / length,
            )
            val latitude = Equirectangular.latitudeOf(world[2])
            // Unwrap onto the branch nearest the frame centre, so a border point
            // just past +180° reads as slightly more than the centre rather than
            // as nearly a full turn less.
            val longitude = unwrapNear(
                Equirectangular.longitudeOf(world[0], world[1]),
                centreLongitude,
            )

            minLongitude = min(minLongitude, longitude)
            maxLongitude = max(maxLongitude, longitude)
            minLatitude = min(minLatitude, latitude)
            maxLatitude = max(maxLatitude, latitude)
        }

        // A frame looking over a pole sees every longitude; the border walk finds
        // only the longitudes its edges happen to cross, which would cut the
        // frame in half. Test the poles directly instead.
        val coversNorthPole = containsDirection(basis, intrinsics, 0.0, 0.0, 1.0, pivotRatio)
        val coversSouthPole = containsDirection(basis, intrinsics, 0.0, 0.0, -1.0, pivotRatio)
        if (coversNorthPole) maxLatitude = 90.0
        if (coversSouthPole) minLatitude = -90.0

        val startRow = floorToInt(
            Equirectangular.rowFor(
                maxLatitude, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees,
            )
        ) - marginPx
        val endRow = ceilToInt(
            Equirectangular.rowFor(
                minLatitude, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees,
            )
        ) + marginPx
        val clampedStartRow = startRow.coerceIn(0, canvasHeight)
        val clampedEndRow = endRow.coerceIn(0, canvasHeight)

        if (coversNorthPole || coversSouthPole) {
            return CanvasFootprint(
                startColumn = 0,
                columnSpan = canvasWidth,
                startRow = clampedStartRow,
                rowSpan = clampedEndRow - clampedStartRow,
            )
        }

        val startColumn = floorToInt(
            Equirectangular.columnFor(
                minLongitude, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees,
            )
        ) - marginPx
        val endColumn = ceilToInt(
            Equirectangular.columnFor(
                maxLongitude, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees,
            )
        ) + marginPx
        val columnSpan = min(endColumn - startColumn, canvasWidth)

        return CanvasFootprint(
            startColumn = startColumn,
            columnSpan = columnSpan,
            startRow = clampedStartRow,
            rowSpan = clampedEndRow - clampedStartRow,
        )
    }

    /** True when a world direction projects inside the frame. */
    private fun containsDirection(
        basis: CameraBasis,
        intrinsics: FrameIntrinsics,
        x: Double,
        y: Double,
        z: Double,
        pivotRatio: Double,
    ): Boolean = projectDirection(basis, intrinsics, x, y, z, pivotRatio) != null

    /** Calls [block] with the camera-frame ray of each sampled border pixel. */
    private fun forEachBorderDirection(
        intrinsics: FrameIntrinsics,
        block: (cameraX: Double, cameraY: Double) -> Unit,
    ) {
        val lastColumn = intrinsics.widthPx - 1.0
        val lastRow = intrinsics.heightPx - 1.0
        val radial = intrinsics.radial

        // The border walked is the captured image's, whose pixels have passed
        // through the lens. Before a pixel can be turned into a ray it has to
        // be un-distorted back to where the pinhole model put it, or a
        // barrel-distorted frame would report a footprint narrower than the
        // true extent it reaches — exactly the kind of cropped edge that
        // becomes a seam gap.
        val border = { column: Double, row: Double ->
            val ideal = if (radial != null) {
                undistortPixel(column, row, intrinsics.centerXPx, intrinsics.centerYPx, radial)
            } else {
                doubleArrayOf(column, row)
            }
            block(cameraXOf(ideal[0], intrinsics), cameraYOf(ideal[1], intrinsics))
        }

        for (step in 0..SAMPLES_PER_EDGE) {
            val fraction = step.toDouble() / SAMPLES_PER_EDGE
            val alongWidth = fraction * lastColumn
            val alongHeight = fraction * lastRow
            border(alongWidth, 0.0)
            border(alongWidth, lastRow)
            border(0.0, alongHeight)
            border(lastColumn, alongHeight)
        }
    }

    private fun cameraXOf(column: Double, intrinsics: FrameIntrinsics): Double =
        (column - intrinsics.centerXPx) / intrinsics.focalXPx

    private fun cameraYOf(row: Double, intrinsics: FrameIntrinsics): Double =
        -(row - intrinsics.centerYPx) / intrinsics.focalYPx

    private fun length(vector: DoubleArray): Double =
        Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])

    private fun floorToInt(value: Double): Int = Math.floor(value).toInt()

    private fun ceilToInt(value: Double): Int = Math.ceil(value).toInt()
}

/**
 * Directions closer to the image plane than this count as behind the camera:
 * their projection races off to infinity and is useless as a pixel position.
 */
internal const val MIN_DEPTH = 1e-6

/**
 * The direction *from the pivot* to the scene point a camera ray runs into.
 *
 * [x], [y], [z] are a unit world direction leaving the lens, and the lens sits
 * at `pivotRatio × forward` on a unit sphere of scene (see [PivotModel]). The
 * scene point is where that ray meets the sphere, and what comes back is the
 * unit direction to it from the pivot — the frame the canvas is indexed in.
 *
 * With [pivotRatio] at zero the lens *is* the pivot and the direction is handed
 * straight back.
 */
internal fun pivotDirection(
    basis: CameraBasis,
    pivotRatio: Double,
    x: Double,
    y: Double,
    z: Double,
): DoubleArray {
    if (pivotRatio <= 0.0) return doubleArrayOf(x, y, z)

    // |k·f + t·d| = 1 with d a unit ray, solved for the positive root. The
    // discriminant cannot go negative for k < 1, but the floor costs nothing.
    val alongAxis = basis.depthOf(x, y, z)
    val half = pivotRatio * alongAxis
    val distance = -half + Math.sqrt(max(half * half - pivotRatio * pivotRatio + 1.0, 0.0))

    val px = pivotRatio * basis.forwardX + distance * x
    val py = pivotRatio * basis.forwardY + distance * y
    val pz = pivotRatio * basis.forwardZ + distance * z
    val length = Math.sqrt(px * px + py * py + pz * pz)
    if (length <= 0.0) return doubleArrayOf(x, y, z)
    return doubleArrayOf(px / length, py / length, pz / length)
}

/**
 * The pixel of a frame that looked in a given world direction, or null when the
 * direction falls outside the frame or behind the camera.
 *
 * This is the whole of the camera model in five lines: rotate into the camera's
 * axes, divide through by depth, scale by the focal length, then push the ideal
 * pixel through the lens's radial distortion to find where it was captured. The
 * renderer inlines it over millions of pixels rather than calling it, so any
 * change here has to be made in both places — which is why the footprint code
 * and the tests share this one, keeping an independent statement of the same
 * geometry.
 *
 * [x], [y], [z] is a direction from the *pivot*, which is the lens only when
 * [pivotRatio] is zero. Everywhere else the lens sits `pivotRatio` of the way
 * out towards the scene along its own optical axis, and correcting for that
 * costs exactly one subtraction: the lever arm is parallel to the forward axis,
 * so it cancels out of the lateral and vertical components and shortens the
 * depth alone. The direction must be a *unit* vector for that to hold.
 *
 * The returned array is `[column, row]` in the frame's pixel coordinates, where
 * rows increase downwards while the camera's "up" axis points the other way.
 */
internal fun projectDirection(
    basis: CameraBasis,
    intrinsics: FrameIntrinsics,
    x: Double,
    y: Double,
    z: Double,
    pivotRatio: Double = 0.0,
): DoubleArray? {
    val depth = basis.depthOf(x, y, z) - pivotRatio
    if (depth <= MIN_DEPTH) return null
    val centreX = intrinsics.centerXPx
    val centreY = intrinsics.centerYPx
    val idealColumn = centreX + intrinsics.focalXPx * basis.lateralOf(x, y, z) / depth
    val idealRow = centreY - intrinsics.focalYPx * basis.verticalOf(x, y, z) / depth

    val radial = intrinsics.radial
    val column: Double
    val row: Double
    if (radial != null) {
        val distorted = distortPixel(idealColumn, idealRow, centreX, centreY, radial)
        column = distorted[0]
        row = distorted[1]
    } else {
        column = idealColumn
        row = idealRow
    }
    if (column < 0.0 || column > intrinsics.widthPx - 1.0) return null
    if (row < 0.0 || row > intrinsics.heightPx - 1.0) return null
    return doubleArrayOf(column, row)
}

/** Shifts [degrees] by whole turns until it lands within 180° of [reference]. */
internal fun unwrapNear(degrees: Double, reference: Double): Double {
    var result = degrees
    while (result - reference > 180.0) result -= 360.0
    while (result - reference < -180.0) result += 360.0
    return result
}

/** Wraps a canvas column onto `0..width-1`, for footprints that cross the seam. */
internal fun wrapColumn(column: Int, width: Int): Int {
    val remainder = column % width
    return if (remainder < 0) remainder + width else remainder
}

/**
 * How much two aims overlap, or null when they do not.
 *
 * The second camera's aim is projected into the first's camera frame and must
 * land within one field of view on both axes — the condition for two
 * axis-aligned field-of-view windows to intersect, since two frames each
 * reaching half a field of view either side of their aim meet exactly when
 * their aims are less than one full field of view apart. In tangent space that
 * bound is `2·tan(fov/2)`: each window spans `tan(fov/2)` either side of its
 * aim, and the windows touch when the aims are `2·tan(fov/2)` apart. (Not
 * `tan(fov)` — the two only agree at small angles, and at a 52° field of view
 * `tan(52°)` is ~31% looser than the true bound, admitting pairs whose frames
 * do not actually overlap into the pose graph and the exposure tie.) The
 * returned value is the squared angular separation (tangent-space), so a
 * smaller value is a stronger shared view, which is how the pose graph orders
 * its edges.
 */
internal fun angularOverlap(
    a: CameraBasis,
    b: CameraBasis,
    horizontalFovDegrees: Float,
    verticalFovDegrees: Float,
): Double? {
    val depth = a.depthOf(b.forwardX, b.forwardY, b.forwardZ)
    if (depth <= MIN_DEPTH) return null
    val horizontalSeparation = abs(a.lateralOf(b.forwardX, b.forwardY, b.forwardZ) / depth)
    val verticalSeparation = abs(a.verticalOf(b.forwardX, b.forwardY, b.forwardZ) / depth)
    if (horizontalSeparation >= overlapBound(horizontalFovDegrees)) return null
    if (verticalSeparation >= overlapBound(verticalFovDegrees)) return null
    return horizontalSeparation * horizontalSeparation + verticalSeparation * verticalSeparation
}

/**
 * `2·tan(degrees / 2)`, the tangent-space reach of one full field of view,
 * saturating at a right angle.
 *
 * A field of view of 90° or more spans the whole half-space in that axis, where
 * the tangent flips sign and would reject every pair; the overlap test wants
 * "no bound at all" instead.
 */
private fun overlapBound(degrees: Float): Double =
    if (degrees >= 90f) Double.MAX_VALUE else 2.0 * tan(Math.toRadians(degrees / 2.0))
