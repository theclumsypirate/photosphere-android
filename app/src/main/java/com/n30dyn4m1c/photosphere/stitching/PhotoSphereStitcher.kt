package com.n30dyn4m1c.photosphere.stitching

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.util.Logging
import com.n30dyn4m1c.photosphere.PhotoSphereApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

private const val TAG = "PhotoSphereStitcher"

/** Debug palette for [PhotoSphereStitcher.stitchPhotos]' colour-frames mode. */
private val DEBUG_FRAME_COLORS = listOf(
    Scalar(90.0, 30.0, 230.0),   // blue
    Scalar(40.0, 210.0, 80.0),   // green
    Scalar(40.0, 40.0, 230.0),   // red
    Scalar(220.0, 210.0, 30.0),  // yellow
    Scalar(220.0, 40.0, 230.0),  // magenta
    Scalar(40.0, 220.0, 230.0),  // cyan
)

/**
 * Why a stitch ended the way it did.
 *
 * The codes are this pipeline's contract with its own logs and bug reports, not
 * anyone else's: 0–3 are the outcomes a registration pass can have, and
 * everything from 100 up is a failure of the machinery around it.
 */
enum class StitchStatus(val code: Int) {
    /** A panorama came back. */
    Ok(0),

    /**
     * Too little of the sphere was covered to be worth calling a photo sphere.
     * Usually a run that stopped early, or a pan that skipped a chunk.
     */
    NeedMoreImages(1),

    /**
     * The frames could not be reconciled into one view. The classic cause is the
     * camera *translating* between frames — walking, or pivoting around the body
     * rather than the lens — which breaks the pure-rotation assumption a
     * panorama is built on.
     */
    AlignmentFailed(2),

    /** No consistent camera could explain the frames that were handed in. */
    CameraEstimationFailed(3),

    /** The native library never loaded, so there is no stitcher to run. */
    OpenCvUnavailable(100),

    /** Nothing was handed in. */
    NoInputImages(101),

    /** A buffered frame could not be decoded — deleted, or truncated mid-write. */
    UnreadableInput(102),

    /** The render completed but reached none of the sphere. */
    EmptyResult(103),

    /** Ran out of memory holding the frames or the panorama. */
    OutOfMemory(104),

    /** Anything else, including native exceptions from inside OpenCV. */
    Unknown(199),
}

/** A stitch that did not produce a panorama, carrying the [status] that says why. */
class StitchException(
    val status: StitchStatus,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Which part of the pipeline is running. */
enum class StitchStage {
    /** Checking inputs and the native library. */
    Preparing,

    /** Decoding buffered frames into matrices. */
    Reading,

    /** Matching features between overlapping frames and correcting the poses. */
    Refining,

    /** Finding the seam lines the overlaps will be cut along. */
    Seaming,

    /** Projecting frames onto the sphere and blending the overlaps. */
    Stitching,

    /** Turning the finished canvas into a bitmap. */
    Projecting,
}

/**
 * How far along a stitch is.
 *
     * [StitchStage.Reading], [StitchStage.Refining], [StitchStage.Seaming] and
     * [StitchStage.Stitching] all report real progress — one frame, one edge,
     * one seam and one band of canvas at a time. The short stages either side
     * leave [fraction] null, and the UI shows an indeterminate spinner for
     * those rather than a bar that lies.
     */
data class StitchProgress(
    val stage: StitchStage,
    val completed: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float?
        get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        val Preparing = StitchProgress(StitchStage.Preparing)
    }
}

/**
 * One frame on its way into a sphere: the pixels, and where the camera was.
 *
 * The pose is what makes this pipeline possible at all — see the class docs on
 * [PhotoSphereStitcher].
 */
data class SphereFrame(
    val file: File,
    val pose: CameraPose,
)

/**
 * Which overlap strategy the render uses.
 *
 * [Blend] is the wide multi-band cross-fade of every overlap. [Carve] and
 * [WinnerTakeAll] both paint each pixel from one frame with only a few pixels
 * of crossfade across each cut — the former assigns frames by graph-cut
 * agreement ([SeamFinder.computeSeams]), the latter by optical-centre
 * closeness ([SeamFinder.computeWinnerSeams], the prototype's scheme).
 */
enum class SeamMode {
    Blend,
    Carve,
    WinnerTakeAll,
}

/**
 * Turns a session's frames into one equirectangular sphere.
 *
 * **Why this is not OpenCV's `Stitcher`.** It cannot be: the stitching module is
 * absent from every prebuilt OpenCV for Android. `org.opencv:opencv` ships Java
 * bindings for core, imgproc, features2d, calib3d and the rest, but there is no
 * `org.opencv.stitching` package and `libopencv_java4.so` contains none of the
 * pipeline's symbols either. `cv::Stitcher` is, in practice, a desktop API.
 *
 * **What replaces it.** Guided capture already knows where the camera was
 * pointing for every frame, because the alignment gate only fires when the
 * device is held on a known target. That turns the hard half of stitching —
 * solving for each camera's rotation — into something already measured, and what
 * is left is a reprojection: every pixel of the output canvas is a direction on
 * the sphere, and for each frame that direction is rotated into the frame's axes
 * and divided through by depth to find the pixel that saw it. Overlaps are
 * resolved by multi-band blending (see [MultibandBlender]): every frame and its
 * feather mask are split into Laplacian/Gaussian pyramids and each band is
 * cross-faded with a mask sized to the band, so a seam is faded at every scale
 * by a transition narrower than the detail that scale carries.
 *
 * The measured poses are not the last word. [PoseRefiner] matches ORB features
 * between overlapping frames and solves for a small per-frame correction on top
 * of the sensor pose — the sensor stays the starting guess and the fallback, and
 * the image content decides the fine alignment. The same matches also refine
 * the field of view: a per-frame focal scale is solved from the matched
 * bearings, so the last bit of scale error a loosely-described lens leaves
 * behind is absorbed before rendering (see [PoseRefiner]). The lens's radial
 * distortion is carried through the whole model (see [RadialDistortion]) so
 * frame edges, where seams live, land where the lens really put them, and
 * [ExposureCompensation] equalises per-frame brightness so the blends do not
 * show seams of light.
 *
 * The result is equirectangular *by construction* rather than by cropping
 * something else into shape: a pixel's row *is* its latitude, so an incomplete
 * sphere is black exactly where it was not shot, at the right elevation.
 *
 * Everything runs on [Dispatchers.Default] — it is compute-bound — and failures
 * come back as a failed [Result] holding a [StitchException]. A sphere that was
 * captured too sparsely is an ordinary outcome of a hurried run, not a crash.
 */
object PhotoSphereStitcher {

    /**
     * Below this a stitch is not worth offering; also what the UI gates on.
     *
     * Three, because three overlapping frames already make a panorama worth
     * having — a corner of a room, a stretch of skyline — and holding the button
     * back until a third of a sphere is in the buffer only turns a usable
     * capture into a failed one. The pipeline places frames from their measured
     * pose rather than by searching for a chain of matches, so it has no
     * minimum-frames requirement of its own; what the extra frames buy is
     * coverage, and coverage is the user's call to make.
     */
    const val MIN_FRAMES = 3

    /** One frame is a photo, not a panorama. */
    const val MIN_STITCHABLE_FRAMES = 2

    /** Long-edge limit each frame is decoded down to. */
    const val DEFAULT_MAX_INPUT_DIMENSION = 1024

    /** Width cap for the finished sphere; the canvas is always half as tall. */
    const val DEFAULT_MAX_OUTPUT_WIDTH = 4096

    /** Never render a canvas narrower than this, however coarse the input. */
    private const val MIN_OUTPUT_WIDTH = 512

    /**
     * Fraction of the sphere that has to be covered for the result to be worth
     * showing.
     *
     * Deliberately near the floor. Three frames of a narrow lens reach under 2%
     * of the canvas, and a partial capture — a single ring, or a corner of a
     * room — is now a supported outcome rather than a failed sphere, so this is
     * only here to catch a render that reached essentially nothing. Anything
     * above it is the user's to judge on the result screen.
     */
    private const val MIN_COVERAGE = 0.002f

    /** Gaussian sigma for the unsharp mask, in output pixels. */
    private const val UNSHARP_RADIUS = 1.5

    /** Pixels brighter than this count as shot content rather than a gap. */
    private const val UNSHARP_CONTENT_THRESHOLD = 24.0

    /** Rows the unsharp mask sharpens at a time. */
    private const val UNSHARP_BAND_HEIGHT = 256

    /**
     * Rows of context carried either side of a band.
     *
     * The Gaussian reaches [UNSHARP_RADIUS]·3 pixels or so; a band blurred
     * without its neighbours would darken toward its own edges and leave a
     * horizontal line every [UNSHARP_BAND_HEIGHT] rows.
     */
    private const val UNSHARP_HALO = 16

    @Volatile
    private var isOpenCvReady: Boolean = false

    /**
     * Loads the native library if it is not up already.
     *
     * [PhotoSphereApplication] does this at process start; this is the belt to
     * that braces, for a stitch running in a process where the Application class
     * never ran. `initLocal` is idempotent, so calling it twice costs nothing.
     */
    @Synchronized
    fun ensureOpenCv(): Boolean {
        if (isOpenCvReady) return true
        isOpenCvReady = PhotoSphereApplication.isOpenCvAvailable || OpenCVLoader.initLocal()
        if (!isOpenCvReady) Logging.e("OpenCV native library unavailable; cannot stitch")
        return isOpenCvReady
    }

    /**
     * Stitches [frames] into an equirectangular [Bitmap].
     *
     * By default the canvas is the full sphere — 2:1, 360° of longitude by 180°
     * of latitude. A ring capture covers the same 360° of longitude but only a
     * band of latitude, so [latitudeSpanDegrees] (with [centerLatitudeDegrees])
     * narrows the canvas to that band and the output comes out as a wide strip
     * instead of a 2:1 image with black wedges at the poles.
     *
     * The two field of view angles describe the *decoded, upright* frame — the
     * same screen-frame angles `rememberSphereOptics` reports — and set how
     * much sphere each frame is taken to cover. Getting them wrong scales the
     * whole panorama: too narrow leaves gaps between frames, too wide overlaps
     * them.
     *
     * [radialDistortion], when the optics reported one, carries the lens's
     * Brown-Conrady coefficients through the projection so frame edges land
     * where the lens put them.
     *
     * [unsharpAmount] (0 = off) is the strength of a masked unsharp mask
     * applied to the finished canvas — the perceived crispness of the output.
     *
     * [pivot] says how far the lens sat from the axis the capture was turned
     * about. The default treats the two as the same point, which is what a
     * tripod gives; a hand-held sphere shot by swivelling on the spot wants
     * [PivotModel.HandheldBodySwivel].
     *
     * [portraitRotationDegrees] is the clockwise turn that makes a raw
     * (sensor-native) frame display-upright at the current display rotation —
     * the rotation CameraX records in each still's EXIF `ORIENTATION` tag. When
     * a frame's tag is missing or was lost in a metadata rewrite, the frame
     * decodes in the sensor's native orientation, transposed against the
     * portrait pose; it is rotated by this amount so its content still lands
     * upright on the sphere.
     *
     * [useRefinement] opts into feature-based pose refinement. It defaults to
     * off: the measured poses are accurate enough to stitch on their own, and
     * refinement can move frames off them when the scene has parallax — a
     * body-swivel capture of a close scene breaks the pure-rotation feature
     * model the refinement assumes. Pass true to let image content correct the
     * measured poses where the features agree.
     *
     * [seamMode] selects the overlap strategy (see [SeamMode]): [SeamMode.Carve]
     * assigns every pixel to the frame the graph-cut agrees on best,
     * [SeamMode.WinnerTakeAll] to the frame whose optical centre is nearest —
     * both sharper across overlaps than the wide cross-fade, at the price of a
     * little compute and a seam that is only as good as the frames agree.
     *
     * [onProgress] is called from the stitching thread, not the main one — hand
     * the value to a `StateFlow` or post it rather than writing Compose state
     * from it directly.
     *
     * The returned bitmap is the caller's to recycle.
     */
    suspend fun stitchPhotos(
        frames: List<SphereFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
        radialDistortion: RadialDistortion? = null,
        maxInputDimension: Int = DEFAULT_MAX_INPUT_DIMENSION,
        maxOutputWidth: Int = DEFAULT_MAX_OUTPUT_WIDTH,
        unsharpAmount: Float = 0f,
        pivot: PivotModel = PivotModel.None,
        portraitRotationDegrees: Int = 90,
        useRefinement: Boolean = false,
        seamMode: SeamMode = SeamMode.Blend,
        debugColorFrames: Boolean = false,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
        onProgress: (StitchProgress) -> Unit = {},
    ): Result<Bitmap> {
        // The span arguments size the canvas; degenerate values would otherwise
        // drive the allocation to Int.MAX_VALUE rows (or a zero-width canvas)
        // and surface as a confusing OutOfMemory instead of a clear error.
        require(longitudeSpanDegrees in 1f..360f) {
            "longitudeSpanDegrees must be in 1..360, was $longitudeSpanDegrees"
        }
        require(latitudeSpanDegrees in 1f..180f) {
            "latitudeSpanDegrees must be in 1..180, was $latitudeSpanDegrees"
        }
        require(centerLatitudeDegrees in -90f..90f) {
            "centerLatitudeDegrees must be in -90..90, was $centerLatitudeDegrees"
        }

        return withContext(Dispatchers.Default) {
        val decoded = ArrayList<DecodedFrame>(frames.size)
        try {
            onProgress(StitchProgress.Preparing)
            Logging.i("Stitch preparing: ${frames.size} frames")

            if (!ensureOpenCv()) {
                throw StitchException(
                    StitchStatus.OpenCvUnavailable,
                    "OpenCV is not loaded on this device",
                )
            }
            if (frames.isEmpty()) {
                throw StitchException(StitchStatus.NoInputImages, "No frames to stitch")
            }
            if (frames.size < MIN_STITCHABLE_FRAMES) {
                throw StitchException(
                    StitchStatus.NeedMoreImages,
                    "Got ${frames.size} frame(s), need at least $MIN_STITCHABLE_FRAMES",
                )
            }

            // The canvas is sized to the detail the frames actually carry: a
            // frame is worth `width / horizontal fov` pixels per degree, and 360°
            // of that is as wide as the sphere can be without inventing pixels.
            var canvasWidth = maxOutputWidth

            // The field of view describes the *upright* frame, as captured on
            // the portrait-locked display. It is checked once, against the first
            // decoded frame, and swapped if it evidently describes the other
            // axis instead: a swapped FOV makes the focal lengths the frame
            // implies disagree by roughly the square of its aspect ratio, which
            // no real lens does. See [correctFovOrientation].
            var horizontalFov = horizontalFovDegrees
            var verticalFov = verticalFovDegrees
            var fovChecked = false

            if (frames.isNotEmpty()) {
                Logging.i(
                    "Stitch input: ${frames.size} frames, reported FOV " +
                        "$horizontalFovDegrees°x$verticalFovDegrees°, " +
                        "distortion=${radialDistortion?.coefficients?.joinToString() ?: "none"}, " +
                        "pivot=$pivot",
                )
            }

            frames.forEachIndexed { position, frame ->
                ensureActive()
                onProgress(StitchProgress(StitchStage.Reading, position, frames.size))

                val read = readFrame(frame.file, maxInputDimension)
                var image = read.image

                // From here the frame is owned by this iteration: a failure
                // before it is handed to `decoded` — the FOV correction, the
                // intrinsics, the placement — must not leak its native buffer.
                // Mat.release is idempotent, so releasing one that
                // `rotateClockwise` already released on its way out is safe.
                try {
                    // A frame must land on the sphere the same way its pose
                    // describes it. The pose is display-upright, so a frame that
                    // decoded in the sensor's native (landscape) orientation —
                    // its EXIF rotation tag missing, or lost when the metadata
                    // rewrite re-encoded the JPEG — has to be turned upright
                    // *here*, or its content paints onto the sphere rotated 90°
                    // against the measured pose and no two frames line up.
                    // `portraitRotationDegrees` is exactly the turn CameraX
                    // would have recorded in the tag.
                    //
                    // The fallback is gated on the tag being genuinely absent.
                    // A frame whose tag is *present* (including NORMAL) is the
                    // camera's own statement about its orientation, and a
                    // landscape frame with NORMAL is a genuine landscape
                    // capture — rotating it would paint its content sideways
                    // against its pose, which is exactly the failure the
                    // fallback exists to prevent for portrait frames. Those
                    // frames go on to [correctFovOrientation] landscape, which
                    // adapts the field of view to their real shape.
                    if (image.cols() > image.rows() &&
                        !read.hasExifOrientation &&
                        portraitRotationDegrees % 180 == 90
                    ) {
                        Logging.w(
                            "Frame $position decoded ${image.cols()}x${image.rows()} with no " +
                                "EXIF orientation — rotating ${portraitRotationDegrees}° " +
                                "clockwise against the ${horizontalFov}°x${verticalFov}° FOV",
                        )
                        image = rotateClockwise(image, portraitRotationDegrees)
                    }
                    Logging.i(
                        "Frame $position decoded ${image.cols()}x${image.rows()} at pose " +
                            "yaw=${frame.pose.yawDegrees}° pitch=${frame.pose.pitchDegrees}° " +
                            "roll=${frame.pose.rollDegrees}°",
                    )
                    if (debugColorFrames) {
                        // Paint the frame a solid colour so the finished pano
                        // shows exactly where each frame was placed — the
                        // placement check.
                        image.setTo(DEBUG_FRAME_COLORS[position % DEBUG_FRAME_COLORS.size])
                    }
                    if (!fovChecked) {
                        val corrected = correctFovOrientation(
                            widthPx = image.cols(),
                            heightPx = image.rows(),
                            horizontalFovDegrees = horizontalFov,
                            verticalFovDegrees = verticalFov,
                        )
                        if (corrected != null) {
                            Logging.w(
                                "FOV ${horizontalFov}°x${verticalFov}° does not match the " +
                                    "${image.cols()}x${image.rows()} frame; using " +
                                    "${corrected.first}°x${corrected.second}°",
                            )
                            horizontalFov = corrected.first
                            verticalFov = corrected.second
                        }
                        fovChecked = true
                    }
                    // The lens's unitless coefficients are converted against the
                    // focal length *this* decoded frame implies, so a frame
                    // subsampled to any size still carries the same physical
                    // lens.
                    val intrinsics = FrameIntrinsics.forLens(
                        widthPx = image.cols(),
                        heightPx = image.rows(),
                        horizontalFovDegrees = horizontalFov,
                        verticalFovDegrees = verticalFov,
                        distortion = radialDistortion,
                    )
                    if (position == 0) {
                        canvasWidth = canvasWidthFor(
                            frameWidthPx = image.cols(),
                            horizontalFovDegrees = horizontalFov,
                            maxOutputWidth = maxOutputWidth,
                            longitudeSpanDegrees = longitudeSpanDegrees,
                        )
                    }
                    decoded += DecodedFrame(
                        image = image,
                        intrinsics = intrinsics,
                        sensorBasis = CameraBasis.of(frame.pose),
                    )
                } catch (e: Throwable) {
                    image.release()
                    throw e
                }
            }
            onProgress(StitchProgress(StitchStage.Reading, frames.size, frames.size))
            val canvasHeight = canvasHeightFor(canvasWidth, longitudeSpanDegrees, latitudeSpanDegrees)
            Logging.i(
                "Stitch geometry: corrected FOV ${horizontalFov}°x${verticalFov}°, " +
                    "canvas ${canvasWidth}x$canvasHeight " +
                    "($longitudeSpanDegrees° x $latitudeSpanDegrees°)",
            )

            ensureActive()
            // Match overlapping frames and correct the measured poses against the
            // content. Falls back to the sensor poses when nothing matches.
            // The *corrected* angles go in: the pose graph is built by asking
            // which frames overlap, and a transposed field of view answers that
            // question about the wrong axis on every pair.
            Logging.i("Stitch refinement: ${if (useRefinement) "on" else "off (sensor poses)"}")
            val refinement = if (useRefinement) {
                PoseRefiner.refine(
                    frames = decoded,
                    horizontalFovDegrees = horizontalFov,
                    verticalFovDegrees = verticalFov,
                    pivotRatio = pivot.ratio,
                    onProgress = { completed, total ->
                        onProgress(StitchProgress(StitchStage.Refining, completed, total))
                    },
                )
            } else {
                // Debug A/B: stitch straight from the measured poses. Refinement
                // is the only step that replaces them, so this isolates whether
                // a bad feature match is moving frames off the sensor's answer.
                val sensor = decoded.map { it.sensorBasis.toRotationMatrix() }
                RefinementResult(
                    bases = sensor.map { CameraBasis.fromRotationMatrix(it) },
                    gains = FloatArray(decoded.size) { 1f },
                    matchedEdges = 0,
                    candidateEdges = 0,
                    focalScales = DoubleArray(decoded.size) { 1.0 },
                )
            }

            if (BuildConfig.DEBUG) {
                val poses = refinement.bases.map { basis ->
                    val p = basis.toPose()
                    "y=${p.yawDegrees.roundToInt()}/p=${p.pitchDegrees.roundToInt()}/" +
                        "r=${p.rollDegrees.roundToInt()}"
                }
                val focals = refinement.focalScales.map { "%.4f".format(it) }
                Logging.i(
                    "Refinement matched ${refinement.matchedEdges} of " +
                        "${refinement.candidateEdges} candidate edges; " +
                        "refined poses: ${poses.joinToString(", ")}; " +
                        "focal scales: ${focals.joinToString(", ")}",
                )
            }

            val prepared = ArrayList<PreparedFrame>(decoded.size)
            decoded.indices.forEach { index ->
                ensureActive()
                val basis = refinement.bases[index]
                // The focal refinement may have decided the reported field of
                // view was off; the scale is applied to the frame's intrinsics
                // (and the radial model re-normalised against the new focal
                // length) so the footprint and the renderer both see the lens
                // the feature matches measured.
                val intrinsics = decoded[index].intrinsics.scaledBy(refinement.focalScales[index])
                prepared += PreparedFrame(
                    image = decoded[index].image,
                    basis = basis,
                    intrinsics = intrinsics,
                    footprint = FrameFootprint.compute(
                        basis = basis,
                        intrinsics = intrinsics,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        pivotRatio = pivot.ratio,
                        longitudeSpanDegrees = longitudeSpanDegrees,
                        centerLongitudeDegrees = centerLongitudeDegrees,
                        latitudeSpanDegrees = latitudeSpanDegrees,
                        centerLatitudeDegrees = centerLatitudeDegrees,
                    ),
                )
            }

            ensureActive()
            // Captured rather than called inside the lambdas below: the seam
            // finder and the renderer are not suspending functions, so the
            // context has to be carried in.
            val context = currentCoroutineContext()

            // Seams: decide which frame paints each pixel, then render with the
            // wide cross-fade replaced by a near-hard cut that fades only a
            // few pixels across it. Without them the render falls back to the
            // multi-band blend.
            Logging.i("Stitch seaming: $seamMode")
            val seams = when (seamMode) {
                SeamMode.Blend -> null
                SeamMode.Carve, SeamMode.WinnerTakeAll -> {
                    onProgress(StitchProgress(StitchStage.Seaming))
                    if (seamMode == SeamMode.WinnerTakeAll) {
                        SeamFinder.computeWinnerSeams(
                            frames = prepared,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            pivotRatio = pivot.ratio,
                            longitudeSpanDegrees = longitudeSpanDegrees,
                            centerLongitudeDegrees = centerLongitudeDegrees,
                            latitudeSpanDegrees = latitudeSpanDegrees,
                            centerLatitudeDegrees = centerLatitudeDegrees,
                            onProgress = { completed, total ->
                                onProgress(StitchProgress(StitchStage.Seaming, completed, total))
                            },
                            checkCancelled = { context.ensureActive() },
                        )
                    } else {
                        SeamFinder.computeSeams(
                            frames = prepared,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            gains = refinement.gains,
                            pivotRatio = pivot.ratio,
                            longitudeSpanDegrees = longitudeSpanDegrees,
                            centerLongitudeDegrees = centerLongitudeDegrees,
                            latitudeSpanDegrees = latitudeSpanDegrees,
                            centerLatitudeDegrees = centerLatitudeDegrees,
                            onProgress = { completed, total ->
                                onProgress(StitchProgress(StitchStage.Seaming, completed, total))
                            },
                            checkCancelled = { context.ensureActive() },
                        )
                    }
                }
            }
            if (BuildConfig.DEBUG && seams != null) {
                Logging.i(
                    "Seams ($seamMode): ${seams.gridWidth}x${seams.gridHeight} grid at scale " +
                        "${seams.scale} over ${prepared.size} frames",
                )
            }

            onProgress(StitchProgress(StitchStage.Stitching))
            Logging.i("Stitch rendering ${canvasWidth}x$canvasHeight")
            val rendered = EquirectangularRenderer.render(
                frames = prepared,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                gains = refinement.gains,
                seams = seams,
                pivotRatio = pivot.ratio,
                longitudeSpanDegrees = longitudeSpanDegrees,
                centerLongitudeDegrees = centerLongitudeDegrees,
                latitudeSpanDegrees = latitudeSpanDegrees,
                centerLatitudeDegrees = centerLatitudeDegrees,
                onBandComplete = { completed, total ->
                    onProgress(StitchProgress(StitchStage.Stitching, completed, total))
                },
                checkCancelled = { context.ensureActive() },
            )

            try {
                if (rendered.coverage <= 0f) {
                    throw StitchException(
                        StitchStatus.EmptyResult,
                        "The render reached none of the sphere",
                    )
                }
                if (rendered.coverage < MIN_COVERAGE) {
                    throw StitchException(
                        StitchStatus.NeedMoreImages,
                        "Only ${(rendered.coverage * 100).roundToInt()}% of the sphere was covered",
                    )
                }

                ensureActive()
                onProgress(StitchProgress(StitchStage.Projecting))
                applyUnsharpMask(rendered.canvas, unsharpAmount)
                val bitmap = toBitmap(rendered.canvas)
                Logging.i("Stitch done: ${bitmap.width}x${bitmap.height} " +
                    "(${(rendered.coverage * 100).roundToInt()}% covered)")
                Result.success(bitmap)
            } finally {
                rendered.canvas.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StitchException) {
            Logging.w("Stitch failed: ${e.status} (${e.status.code})", e)
            Result.failure<Bitmap>(e)
        } catch (e: OutOfMemoryError) {
            // Recoverable here: the frames are released in the finally below, and
            // the user can retry at a lower input resolution.
            Logging.e("Out of memory stitching ${frames.size} frames", e)
            Result.failure<Bitmap>(
                StitchException(StitchStatus.OutOfMemory, "Not enough memory to stitch", e)
            )
        } catch (e: Exception) {
            // Native OpenCV failures arrive as CvException, a RuntimeException.
            Logging.e("Stitch failed", e)
            Result.failure<Bitmap>(
                StitchException(StitchStatus.Unknown, e.message ?: "Stitch failed", e)
            )
        } finally {
            // `prepared` and `decoded` share the same Mats; releasing the decoded
            // set covers every path, including a failure mid-decode or mid-refine.
            decoded.forEach { it.image.release() }
        }
    }
    }

    /**
     * Canvas width that matches the detail in the frames.
     *
     * Rendering wider than this only interpolates: the frames hold
     * `frameWidthPx / fov` pixels per degree and the sphere is
     * [longitudeSpanDegrees] around (360° for a full sphere or a ring capture).
     * The width is forced even so a full-sphere canvas has an integer height.
     */
    internal fun canvasWidthFor(
        frameWidthPx: Int,
        horizontalFovDegrees: Float,
        maxOutputWidth: Int,
        longitudeSpanDegrees: Float = 360f,
    ): Int {
        val ideal = (longitudeSpanDegrees * frameWidthPx / horizontalFovDegrees).roundToInt()
        val width = minOf(ideal, maxOutputWidth).coerceAtLeast(MIN_OUTPUT_WIDTH)
        return width - (width % 2)
    }

    /**
     * Canvas height that keeps the pixels square for a canvas spanning
     * [longitudeSpanDegrees] of longitude and [latitudeSpanDegrees] of latitude.
     *
     * A full sphere is half as tall as wide (180° of latitude across 360° of
     * longitude); a ring capture is only as tall as its band is wide in angular
     * terms, so a 360° × 72° ring comes out at exactly one fifth of the width.
     */
    internal fun canvasHeightFor(
        canvasWidth: Int,
        longitudeSpanDegrees: Float,
        latitudeSpanDegrees: Float,
    ): Int {
        // Guarded in stitchPhotos too; this keeps the function safe on its own
        // (a zero span would otherwise compute Infinity -> Int.MAX_VALUE).
        require(longitudeSpanDegrees > 0f) { "longitude span must be positive" }
        val height = (canvasWidth * latitudeSpanDegrees / longitudeSpanDegrees).roundToInt()
        return height.coerceAtLeast(1)
    }

    /**
     * Checks that the two field-of-view angles describe the axes of a decoded
     * [widthPx]x[heightPx] frame, swapping them when they evidently do not.
     *
     * A lens has square pixels, so the focal length the horizontal field of
     * view implies for the frame's width must match the one the vertical field
     * of view implies for its height: a correctly-aligned pairing has a
     * min/max focal ratio of exactly 1. If the angles arrived transposed — the
     * axes of the sensor rather than of the upright frame — the ratio is the
     * square of the frame's aspect (0.56 for a 4:3 frame), far beyond any
     * field-of-view estimation error. The swap boundary sits halfway between
     * the two in log space — `sqrt(transposed ratio)` — so a pairing is
     * swapped only when it is closer to the transposed expectation than to the
     * aligned one, which keeps genuinely asymmetric FOVs (an extreme crop on
     * one axis) from being mis-swapped by an arbitrary constant.
     *
     * Returns the corrected pair, or null when the angles already match the
     * frame.
     */
    internal fun correctFovOrientation(
        widthPx: Int,
        heightPx: Int,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
    ): Pair<Float, Float>? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val horizontalTan = tan(Math.toRadians(horizontalFovDegrees / 2.0))
        val verticalTan = tan(Math.toRadians(verticalFovDegrees / 2.0))
        if (horizontalTan <= 0.0 || verticalTan <= 0.0) return null
        val horizontalFocal = widthPx / 2.0 / horizontalTan
        val verticalFocal = heightPx / 2.0 / verticalTan
        val ratio =
            if (horizontalFocal >= verticalFocal) verticalFocal / horizontalFocal
            else horizontalFocal / verticalFocal
        val aspect = widthPx.toDouble() / heightPx
        val transposedRatio = if (aspect >= 1.0) 1.0 / (aspect * aspect) else aspect * aspect
        if (ratio >= sqrt(transposedRatio)) return null
        return verticalFovDegrees to horizontalFovDegrees
    }

    /**
     * One decoded frame plus whether its EXIF orientation tag was present.
     *
     * The tag's *presence* is what lets the decode loop tell "the camera says
     * this frame is landscape" from "the tag was lost and this is a portrait
     * frame still in its sensor-native shape". See the rotation fallback in
     * [stitchPhotos].
     */
    private class ReadFrame(
        val image: Mat,
        val hasExifOrientation: Boolean,
    )

    /**
     * Decodes one frame into the 8-bit 3-channel matrix the renderer samples.
     *
     * Frames are decoded subsampled and rotated upright first. The rotation
     * matters more than it looks: CameraX records device rotation in EXIF rather
     * than rotating pixels, so a run that crossed a screen rotation would
     * otherwise hand the renderer a mix of portrait and landscape frames while
     * the field of view describes only one of them.
     */
    private fun readFrame(file: File, maxDimension: Int): ReadFrame {
        // Read the tag's presence before decoding: ExifInterface.getAttributeInt
        // cannot distinguish a present NORMAL tag from a missing one, and the
        // rotation fallback depends on that distinction. A tag reading
        // UNDEFINED (0, what some Motorola/HALs write instead of a real
        // orientation) counts as missing too: it carries no rotation, and
        // treating it as present would skip the portrait-rotation fallback and
        // leave a sensor-native landscape frame sideways against its pose.
        val hasExifOrientation = runCatching {
            val raw = ExifInterface(file).getAttribute(ExifInterface.TAG_ORIENTATION)
            val parsed = raw?.toIntOrNull()
                ?: ExifInterface(file).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            raw != null && parsed != ExifInterface.ORIENTATION_UNDEFINED
        }.getOrDefault(false)

        val bitmap = decodeUpright(file, maxDimension)
            ?: throw StitchException(
                StitchStatus.UnreadableInput,
                "Could not decode ${file.name}",
            )

        val rgba = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        } catch (e: Throwable) {
            rgb.release()
            throw e
        } finally {
            rgba.release()
            bitmap.recycle()
        }
        return ReadFrame(rgb, hasExifOrientation)
    }

    /**
     * Rotates [source] by [degrees] clockwise in place, releasing the original.
     *
     * The fallback for a frame whose EXIF rotation was lost: [degrees] is the
     * turn CameraX would have recorded, so the result is exactly what the
     * frame's own tag should have produced. 180° is a plain flip; 90° and 270°
     * swap the axes, which is the case this pipeline actually meets.
     */
    private fun rotateClockwise(source: Mat, degrees: Int): Mat {
        val rotated = Mat()
        try {
            when (((degrees % 360) + 360) % 360) {
                90 -> Core.rotate(source, rotated, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(source, rotated, Core.ROTATE_180)
                270 -> Core.rotate(source, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> return source
            }
        } catch (e: Throwable) {
            rotated.release()
            throw e
        } finally {
            source.release()
        }
        return rotated
    }

    /** Copies the finished canvas out into a bitmap the UI can show. */
    private fun toBitmap(canvas: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888)
        try {
            Utils.matToBitmap(canvas, bitmap)
        } catch (e: Throwable) {
            // A malformed canvas must not leak the bitmap it was about to fill.
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    /**
     * Applies a masked unsharp mask to the finished canvas, in place.
     *
     * `out = src + amount·(src − blur)` lifts the edge contrast that reads as
     * sharpness. The mask restricts it to pixels that were actually shot: the
     * black bands where the sphere was never captured must not grow a bright
     * rim, and near-black pixels (a night shot) are left alone so noise is not
     * sharpened along with the edges.
     *
     * **Memory.** Done whole, this is the peak of the entire pipeline: a blur, a
     * sharpened copy, a greyscale and a mask all at canvas size, which on the
     * 6144-wide profile is around 200 MB of native buffers on top of the canvas
     * itself. Working in bands holds one untouched copy of the canvas and four
     * band-sized temporaries instead. The copy is what keeps the result
     * identical to the whole-canvas version: every band reads its blur input
     * from the original pixels, so a band's halo cannot pick up the sharpening
     * its neighbour just wrote and sharpen it a second time.
     */
    private fun applyUnsharpMask(canvas: Mat, amount: Float) {
        if (amount <= 0f) return
        val height = canvas.rows()
        val width = canvas.cols()
        if (height <= 0 || width <= 0) return

        val source = canvas.clone()
        try {
            var bandTop = 0
            while (bandTop < height) {
                val bandHeight = min(UNSHARP_BAND_HEIGHT, height - bandTop)
                // The band plus the context the Gaussian needs, clipped to the
                // canvas at the top and bottom rows.
                val haloTop = max(0, bandTop - UNSHARP_HALO)
                val haloBottom = min(height, bandTop + bandHeight + UNSHARP_HALO)
                sharpenBand(
                    source = source,
                    canvas = canvas,
                    width = width,
                    haloTop = haloTop,
                    haloHeight = haloBottom - haloTop,
                    bandTop = bandTop,
                    bandHeight = bandHeight,
                    amount = amount,
                )
                bandTop += bandHeight
            }
        } finally {
            source.release()
        }
    }

    /** Sharpens one band of [canvas], reading its blur input from [source]. */
    private fun sharpenBand(
        source: Mat,
        canvas: Mat,
        width: Int,
        haloTop: Int,
        haloHeight: Int,
        bandTop: Int,
        bandHeight: Int,
        amount: Float,
    ) {
        val halo = source.submat(Rect(0, haloTop, width, haloHeight))
        val blurred = Mat()
        val sharpenedHalo = Mat()
        val gray = Mat()
        val mask = Mat()
        try {
            Imgproc.GaussianBlur(halo, blurred, Size(0.0, 0.0), UNSHARP_RADIUS)
            Core.addWeighted(halo, 1.0 + amount, blurred, -amount.toDouble(), 0.0, sharpenedHalo)

            // Only the band itself is written back; the halo was context.
            val bandInHalo = Rect(0, bandTop - haloTop, width, bandHeight)
            val sharpenedBand = sharpenedHalo.submat(bandInHalo)
            val sourceBand = source.submat(Rect(0, bandTop, width, bandHeight))
            val target = canvas.submat(Rect(0, bandTop, width, bandHeight))
            try {
                Imgproc.cvtColor(sourceBand, gray, Imgproc.COLOR_RGB2GRAY)
                Core.compare(gray, Scalar(UNSHARP_CONTENT_THRESHOLD), mask, Core.CMP_GT)
                sharpenedBand.copyTo(target, mask)
            } finally {
                sharpenedBand.release()
                sourceBand.release()
                target.release()
            }
        } finally {
            halo.release()
            blurred.release()
            sharpenedHalo.release()
            gray.release()
            mask.release()
        }
    }

    /** Decodes [file] no larger than [maxDimension], with EXIF rotation applied. */
    private fun decodeUpright(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            // Utils.bitmapToMat accepts ARGB_8888 and RGB_565 only.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        return applyExifRotation(decoded, file)
    }

    /** Rotates [bitmap] to match the orientation EXIF claims for [file]. */
    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }

        val rotated = try {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, /* filter = */ true,
            )
        } catch (e: Throwable) {
            // An allocation failure must not leak the decoded source bitmap.
            bitmap.recycle()
            throw e
        }
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}

/**
 * Power-of-two subsampling factor that brings the longer edge to [maxDimension]
 * or below.
 *
 * `BitmapFactory` only honours powers of two, so this returns one directly
 * rather than letting it round the value down and hand back something larger
 * than asked for.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    require(maxDimension > 0) { "maxDimension must be positive" }
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / sample > maxDimension) sample *= 2
    return sample
}
