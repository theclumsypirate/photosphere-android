package com.n30dyn4m1c.photosphere.camera
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.SizeF
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import com.n30dyn4m1c.photosphere.stitching.RadialDistortion
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

private const val TAG = Logging.TAG

/**
 * Field of view of a mid-range phone's main camera, in the sensor's own
 * (landscape) frame. Used when the camera refuses to describe itself.
 */
private val DEFAULT_SENSOR_FIELD_OF_VIEW = FieldOfView(
    horizontalDegrees = 66f,
    verticalDegrees = 52f,
)

/**
 * Diagonal field of view a phone's *main* rear camera has, near enough.
 *
 * Main cameras cluster hard around a 24 mm-equivalent lens; ultrawides are past
 * 100° on the diagonal and telephotos well under 50°. That separation is wide
 * enough to pick the main lens out of a list of focal lengths by nothing more
 * than which one lands closest to here.
 */
private const val MAIN_LENS_DIAGONAL_FOV_DEGREES = 78f

/**
 * How far the two independent field-of-view estimates may disagree before the
 * calibration is treated as untrustworthy. A quarter is far more than lens
 * variation and far less than the factor-of-two a crop mismatch produces.
 */
private const val MAX_ESTIMATE_DISAGREEMENT = 1.25f

/**
 * The optics of the rear camera, as this device reports them.
 *
 * Carries both halves the pipeline needs: the on-screen [FieldOfView] for
 * laying out targets and placing markers, and the lens's radial
 * [RadialDistortion] so the stitcher can correct frame edges. `null` distortion
 * means the camera gave no calibration — the stitcher then assumes a pinhole.
 */
data class SphereOptics(
    val fieldOfView: FieldOfView,
    val radialDistortion: RadialDistortion?,
    /**
     * Clockwise rotation, in degrees, that turns a raw (sensor-native) frame
     * into the display-upright portrait frame the [fieldOfView] describes —
     * the same rotation CameraX records in each still's EXIF `ORIENTATION`
     * tag. The stitcher uses it as the fallback when a frame's EXIF rotation
     * is missing or was lost in a metadata rewrite, so the frame still lands
     * upright against its pose.
     */
    val portraitRotationDegrees: Int,
)

/**
 * The full optics estimate for the rear camera, cached per configuration.
 *
 * [cameraId] is the camera2 id of the lens the session actually bound. Pass it:
 * everything downstream — the spacing of the capture plan, the rectangles drawn
 * on the viewfinder, the angle each stitched frame is taken to cover — is scaled
 * by these numbers, and a phone with three rear lenses will happily describe one
 * of them while streaming another. Left null, the id is guessed from the device
 * profile, which is only right until it isn't.
 *
 * [streamAspectRatio] is `width / height` of the buffer the camera is producing,
 * in the sensor's own frame. A 16:9 stream read off a 4:3 sensor is a crop, not
 * a squeeze, and it sees noticeably less of the world than the sensor array
 * does; passing the real ratio is what keeps the overlay honest about what the
 * frame will contain. Zero means "same shape as the sensor".
 */
@Composable
fun rememberSphereOptics(
    cameraId: String? = null,
    streamAspectRatio: Float = 0f,
): SphereOptics {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, cameraId, streamAspectRatio) {
        estimateSphereOptics(
            context = context,
            displayRotation = context.currentDisplayRotation(),
            profile = SphereDeviceProfile.forDevice(),
            cameraId = cameraId,
            streamAspectRatio = streamAspectRatio,
        )
    }
}

/**
 * Derives the on-screen field of view from the camera's optics.
 *
 * Two independent estimates are made and then reconciled, because either one
 * alone will quietly mislead on some phone:
 *
 * - **From the calibration.** `LENS_INTRINSIC_CALIBRATION` reports the focal
 *   length in pixels, which with the array it is quoted against gives the exact
 *   angle each edge subtends. It is quoted against the *pre-correction* active
 *   array, not the plain active array — using the wrong one is a small error,
 *   and using an array from a different sensor mode is a factor-of-two one.
 * - **From the geometry.** Physical sensor size over focal length. Coarser, but
 *   it cannot be quoted against the wrong crop, which makes it the tie-breaker.
 *
 * If they disagree by more than [MAX_ESTIMATE_DISAGREEMENT] the calibration is
 * discarded. That check is the whole point of doing the work twice: a field of
 * view overestimated by a third spaces the capture plan a third too wide, and
 * the first sign of it is a run that will not stitch.
 *
 * Picking the focal length is its own trap on a logical multi-camera, which
 * lists one per physical lens. Taking whichever comes first can hand back the
 * ultrawide's 2.2 mm while the session streams the main lens — a field of view
 * nearly twice the truth, targets spaced nearly twice too far apart, and frames
 * that do not overlap at all. [selectFocalLengthMm] picks by what the profile
 * asked for instead.
 *
 * Two corrections then land the result on the screen: the stream's aspect ratio
 * crops the sensor's angles down to what is really being read out, and the
 * sensor's mounting rotation relative to the display decides which of the two
 * runs across the viewport. The preview's `FILL_CENTER` scaling is handled
 * downstream by [SphereProjection.focalLengthPx], which takes the field of view
 * as the *uncropped* maximum.
 *
 * The same query also reads `LENS_DISTORTION` (API 28+), the lens's Brown-Conrady
 * coefficients at the active-array scale, so the stitcher can straighten frame
 * edges instead of treating the lens as a pinhole.
 */
internal fun estimateSphereOptics(
    context: Context,
    displayRotation: Int,
    profile: SphereDeviceProfile,
    cameraId: String? = null,
    streamAspectRatio: Float = 0f,
): SphereOptics {
    var sensorFieldOfView = DEFAULT_SENSOR_FIELD_OF_VIEW
    // Portrait-first phones mount the sensor rotated a quarter turn; assume that
    // when the camera cannot be queried, since it matches nearly all hardware.
    var sensorOrientation = 90
    var radialDistortion: RadialDistortion? = null

    val manager = context.getSystemService(CameraManager::class.java)
    if (manager != null) {
        try {
            // Defaults to the lens the profile would bind, so the estimate is
            // usable before the camera provider has resolved anything.
            val id = cameraId ?: captureBackCameraId(context, profile)
            if (id != null) {
                val characteristics = manager.getCameraCharacteristics(id)
                sensorOrientation =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                val array =
                    characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
                    ) ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val physicalSize =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focalLengthMm = selectFocalLengthMm(
                    focalLengthsMm =
                        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                    sensorDiagonalMm = physicalSize.diagonalMm(),
                    preferWidest = profile.preferWidestCamera,
                )

                val fromIntrinsics = array?.let { active ->
                    characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                        ?.takeIf { it.size >= 2 && it[0] > 0f && it[1] > 0f }
                        ?.let { intrinsic ->
                            FieldOfView(
                                horizontalDegrees =
                                    fieldOfViewDegrees(active.width().toFloat(), intrinsic[0]),
                                verticalDegrees =
                                    fieldOfViewDegrees(active.height().toFloat(), intrinsic[1]),
                            )
                        }
                }
                val fromGeometry = if (physicalSize != null && focalLengthMm != null) {
                    FieldOfView(
                        horizontalDegrees = fieldOfViewDegrees(physicalSize.width, focalLengthMm),
                        verticalDegrees = fieldOfViewDegrees(physicalSize.height, focalLengthMm),
                    )
                } else {
                    null
                }

                sensorFieldOfView = reconcileFieldOfView(fromIntrinsics, fromGeometry)
                if (fromIntrinsics != null && fromGeometry != null &&
                    sensorFieldOfView !== fromIntrinsics
                ) {
                    // Worth a warning of its own: this is the case that used to
                    // space the capture plan past any overlap at all, and it is
                    // the first thing to look for in a run that would not stitch.
                    Log.w(
                        TAG,
                        "Camera $id calibration claims ${fromIntrinsics.horizontalDegrees}° " +
                            "where its sensor geometry says ${fromGeometry.horizontalDegrees}°; " +
                            "trusting the geometry",
                    )
                }
                if (array != null) {
                    sensorFieldOfView = croppedToStreamAspect(
                        sensor = sensorFieldOfView,
                        sensorAspectRatio = array.width().toFloat() / array.height(),
                        streamAspectRatio = streamAspectRatio,
                    )
                }
                Log.i(
                    TAG,
                    "Camera $id: intrinsics=$fromIntrinsics geometry=$fromGeometry " +
                        "stream=$streamAspectRatio -> $sensorFieldOfView",
                )

                // LENS_DISTORTION's coefficients act on coordinates normalized
                // by the focal length, so they carry no resolution of their own
                // — the stitcher rescales them against whatever frame it is
                // looking at. Only the three radial terms are taken; the two
                // tangential ones that follow are negligible on a phone lens.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val kappa = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
                    if (kappa != null && kappa.size >= 3) {
                        radialDistortion = RadialDistortion(
                            coefficients = doubleArrayOf(
                                kappa[0].toDouble(),
                                kappa[1].toDouble(),
                                kappa[2].toDouble(),
                            ),
                        ).takeIf { it.isSignificant }
                    }
                }
            }
        } catch (e: Exception) {
            // A camera that will not describe itself still previews fine; the
            // markers just ride on the default optics.
            Log.w(TAG, "Falling back to default field of view", e)
        }
    }

    val relativeRotation = ((sensorOrientation - displayRotation.toDegrees()) % 360 + 360) % 360
    val fieldOfView =
        if (relativeRotation % 180 == 90) sensorFieldOfView.transposed() else sensorFieldOfView
    return SphereOptics(
        fieldOfView = fieldOfView,
        radialDistortion = radialDistortion,
        // The rotation CameraX records in each still's EXIF — the clockwise turn
        // that makes the sensor's native frame display-upright at the current
        // display rotation. The stitcher's fallback when a frame loses that tag.
        portraitRotationDegrees = relativeRotation,
    )
}

/**
 * The focal length of the lens that will actually be streaming.
 *
 * A physical camera lists one. A logical multi-camera lists one per lens it
 * fronts, in no useful order, and the pipeline has to pick the one matching the
 * lens the session bound:
 *
 * - A profile that asked for the widest lens gets the shortest focal length.
 * - Otherwise the main lens is wanted, and it is identified by its diagonal
 *   field of view landing nearest [MAIN_LENS_DIAGONAL_FOV_DEGREES].
 * - Without a sensor size ([sensorDiagonalMm] at zero) there is no field of view
 *   to compare, so the *longest* focal length wins by default. It is the
 *   asymmetry that decides it: guessing too narrow costs a few extra frames,
 *   guessing too wide spaces the plan past the overlap the stitcher needs and
 *   loses the whole run.
 */
internal fun selectFocalLengthMm(
    focalLengthsMm: FloatArray?,
    sensorDiagonalMm: Float,
    preferWidest: Boolean,
): Float? {
    val candidates = focalLengthsMm?.filter { it > 0f }?.distinct().orEmpty()
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return candidates.first()
    if (preferWidest) return candidates.min()
    if (sensorDiagonalMm <= 0f) return candidates.max()

    return candidates.minByOrNull { focal ->
        abs(fieldOfViewDegrees(sensorDiagonalMm, focal) - MAIN_LENS_DIAGONAL_FOV_DEGREES)
    }
}

/** Corner-to-corner extent of a sensor, or 0 when it will not say. */
private fun SizeF?.diagonalMm(): Float =
    this?.takeIf { it.width > 0f && it.height > 0f }
        ?.let { sqrt(it.width * it.width + it.height * it.height) }
        ?: 0f

/**
 * Settles the two field-of-view estimates against each other.
 *
 * The calibration is preferred when the geometry agrees with it, because it is
 * the more precise of the two. Past [MAX_ESTIMATE_DISAGREEMENT] the pair cannot
 * both be describing this lens, and the geometry is what survives: a focal
 * length in millimetres over a sensor size in millimetres has no crop to be
 * quoted against and therefore no way to be off by a factor.
 *
 * Deliberately free of any framework call, logging included, so the decision
 * can be exercised in a local unit test. The caller says what was discarded.
 */
internal fun reconcileFieldOfView(
    fromIntrinsics: FieldOfView?,
    fromGeometry: FieldOfView?,
): FieldOfView {
    if (fromIntrinsics == null) return fromGeometry ?: DEFAULT_SENSOR_FIELD_OF_VIEW
    if (fromGeometry == null) return fromIntrinsics

    val ratio = fromIntrinsics.horizontalDegrees / fromGeometry.horizontalDegrees
    val agrees = ratio <= MAX_ESTIMATE_DISAGREEMENT && ratio >= 1f / MAX_ESTIMATE_DISAGREEMENT
    return if (agrees) fromIntrinsics else fromGeometry
}

/**
 * Narrows a sensor-array field of view to the stream being read off it.
 *
 * Camera2 derives a stream of a different shape by cropping the array, not by
 * squeezing it — the focal length is unchanged and the axis that does not fit
 * simply loses its ends. A 16:9 preview off a 4:3 sensor therefore sees a good
 * 25% less across one axis than the array does, and an overlay drawn from the
 * array's angles would place every marker too close to the centre of a frame
 * that will not actually reach it.
 *
 * Both ratios are `width / height` in the sensor's own frame. A stream ratio of
 * zero (unknown) leaves the field of view alone.
 */
internal fun croppedToStreamAspect(
    sensor: FieldOfView,
    sensorAspectRatio: Float,
    streamAspectRatio: Float,
): FieldOfView {
    if (sensorAspectRatio <= 0f || streamAspectRatio <= 0f) return sensor
    val tanHorizontal = tanHalf(sensor.horizontalDegrees)
    val tanVertical = tanHalf(sensor.verticalDegrees)
    return if (streamAspectRatio >= sensorAspectRatio) {
        // Wider than the array: the full width is kept and the height is cut.
        FieldOfView(
            horizontalDegrees = sensor.horizontalDegrees,
            verticalDegrees = degreesFromTanHalf(tanHorizontal / streamAspectRatio),
        )
    } else {
        FieldOfView(
            horizontalDegrees = degreesFromTanHalf(tanVertical * streamAspectRatio),
            verticalDegrees = sensor.verticalDegrees,
        )
    }
}

/**
 * Angle subtended by an extent of [extent] (pixels or millimetres) behind a lens
 * of [focal] in the same units.
 */
private fun fieldOfViewDegrees(extent: Float, focal: Float): Float =
    degreesFromTanHalf(extent / (2f * focal))

private fun tanHalf(degrees: Float): Float = tan(Math.toRadians(degrees / 2.0)).toFloat()

private fun degreesFromTanHalf(tangent: Float): Float =
    Math.toDegrees(2.0 * atan(tangent.toDouble())).toFloat().coerceIn(1f, 179f)

/** `Surface.ROTATION_*` as the number of degrees the display is turned by. */
private fun Int.toDegrees(): Int = when (this) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
