package com.n30dyn4m1c.photosphere.extendedcapture

import android.content.Context
import android.graphics.Bitmap
import com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile
import com.n30dyn4m1c.photosphere.camera.SphereOptics
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.stitching.CameraBasis
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.DecodedFrame
import com.n30dyn4m1c.photosphere.stitching.FrameIntrinsics
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.PivotModel
import com.n30dyn4m1c.photosphere.stitching.PoseRefiner
import com.n30dyn4m1c.photosphere.stitching.SeamMode
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.stitching.StitchStage
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stitches a finished extended capture run on the phone, with the same stitcher
 * the normal camera uses, so the run can be judged without a laptop in the loop.
 *
 * Two runs mean two different things, and each is stitched the way the thing it
 * stands in for is stitched:
 *
 * - [ExtendedStitch.Mode.Reference] walks the 62 point grid proven by
 *   `tools/pano_proto/stitch_closed.py`. The script had exact coordinates for
 *   every frame; a phone only has the grid the user aimed at, plus a sensor
 *   that drifts over a full turn. So the reference run is placed on the grid
 *   coordinates themselves, then refined against image content, which is what
 *   closes the loop the way the script's exact coordinates did. It keeps the
 *   script's pure-geometry pipeline: no lens distortion correction, no
 *   sharpening, no hole filling, the script's output width and its half-size
 *   decode. Only the grid itself and those settings are the reference's; the
 *   warping and seam blending are the app stitcher's.
 * - [ExtendedStitch.Mode.Adaptive] walks the plan production lays out from the
 *   bound lens's field of view. Production pairs that plan with pose
 *   refinement, so this does too; measuring the plan without the refiner would
 *   be testing a combination production never runs.
 *
 * The pose comes from the `.pose` sidecar and nowhere else. The frames are
 * written untouched by design, so unlike the main camera there is no EXIF
 * comment to fall back on, and a frame with no sidecar is left out rather than
 * guessed at.
 *
 * Debug only: only referenced from `app/src/debug`.
 */
object ExtendedStitch {

    /** How a run is stitched, mirroring what the run stands in for. */
    enum class Mode {
        /** The proven grid: planned coordinates, refined, the prototype's pivot. */
        Reference,

        /** The production plan shape: refinement on, the profile's pivot. */
        Adaptive,
    }

    /**
     * Reads [directory] and renders it into one sphere.
     *
     * [plannedPoses] holds the grid coordinates each frame was aimed at,
     * indexed the way the sidecars are. Only the reference mode uses them: they
     * replace the sensor yaw and pitch, which drift over a full turn, while the
     * measured roll is kept. Adaptive runs are production's and stay on the
     * measured poses.
     *
     * [onProgress] is called from the stitching thread, not the main one; the
     * caller must post it rather than writing Compose state from it.
     */
    suspend fun stitch(
        context: Context,
        directory: File,
        optics: SphereOptics,
        mode: Mode,
        outputWidth: Int = DEFAULT_REFERENCE_WIDTH,
        plannedPoses: Map<Int, CameraPose> = emptyMap(),
        onProgress: (StitchProgress) -> Unit = {},
    ): SphereImageStore.StitchedSphere {
        val measured = readFrames(directory)
        Logging.i(
            "Extended stitch: ${measured.size} posed frames in ${directory.name} mode=$mode",
        )
        check(measured.isNotEmpty()) { "No posed frames in ${directory.name}" }
        val frames = if (mode == Mode.Reference) {
            applyPlannedPoses(measured, plannedPoses)
        } else {
            measured
        }

        val bitmap = try {
            when (mode) {
                Mode.Reference -> renderReference(frames, directory, optics, outputWidth, onProgress)
                Mode.Adaptive -> renderAdaptive(frames, optics, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        }

        try {
            val sphere = withContext(Dispatchers.IO) {
                SphereImageStore.writeStitchedSphere(
                    context = context,
                    bitmap = bitmap,
                    gpano = GPanoMetadata.forFullPano(bitmap.width, bitmap.height),
                )
            }
            Logging.i(
                "Extended stitch wrote ${sphere.file.name} " +
                    "${sphere.width}x${sphere.height} mode=$mode",
            )
            // Diagnostics stay in the log file. The result screen is the
            // panorama, as on the normal capture path, not a debug readout.
            return sphere.copy(diagnostics = null)
        } catch (e: CancellationException) {
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Renders the reference grid the way `tools/pano_proto/stitch_closed.py`
     * does, through [PrototypeRenderer]. The frames are decoded and warped one
     * at a time there, so a 62-frame run does not have to fit in memory at once.
     *
     * A raw copy is written beside the run, encoded by OpenCV exactly as the
     * script's `imwrite` encodes it (quality 95, no metadata), so the run can be
     * byte-compared against `stairs_perfect.jpg` once the frames are pulled.
     */
    private suspend fun renderReference(
        frames: List<SphereFrame>,
        directory: File,
        optics: SphereOptics,
        outputWidth: Int,
        onProgress: (StitchProgress) -> Unit,
    ): Bitmap {
        val size = ExtendedStore.uprightFrameSize(frames.first().file.absolutePath)
            ?: error("could not read the first frame's size")
        val (width, height) = size
        val pivot = pivotFor(Mode.Reference)
        val raw = File(directory, REFERENCE_RAW_NAME)
        Logging.i("Extended reference: native=${width}x${height} raw=${raw.name}")
        return withContext(Dispatchers.Default) {
            val placed = refineReference(frames, optics, pivot, onProgress)
            PrototypeRenderer.render(
                frames = placed,
                nativeWidth = width,
                nativeHeight = height,
                horizontalFovDegrees = optics.fieldOfView.horizontalDegrees.toDouble(),
                verticalFovDegrees = optics.fieldOfView.verticalDegrees.toDouble(),
                outputWidth = outputWidth,
                leverMeters = pivot.leverArmMeters.toDouble(),
                sceneMeters = pivot.sceneDistanceMeters.toDouble(),
                rawJpegFile = raw,
                onFrame = { done, total ->
                    onProgress(StitchProgress(StitchStage.Stitching, done, total))
                },
            )
        }
    }

    /**
     * Aligns the planned reference coordinates with what the frames actually
     * show.
     *
     * A grid target is where the walker was asked to point, not where the lens
     * was when the shutter fired: the dwell gate allows a few degrees, and at
     * 4096 px that is a visible double edge. The production [PoseRefiner] is
     * exactly the tool for it, so the reference run uses the same one the
     * adaptive run does, seeded with the planned coordinates instead of the
     * drifting sensor ones. Decoded at [REFINE_LONG_EDGE] only; the renderer
     * re-reads the full frames one at a time afterwards.
     *
     * If anything cannot be read or no overlap matches, the planned poses are
     * used unchanged. That is still a closed grid, just without the last
     * fraction of a degree.
     */
    private fun refineReference(
        frames: List<SphereFrame>,
        optics: SphereOptics,
        pivot: PivotModel,
        onProgress: (StitchProgress) -> Unit,
    ): List<SphereFrame> {
        val hfov = optics.fieldOfView.horizontalDegrees
        val vfov = optics.fieldOfView.verticalDegrees
        val decoded = ArrayList<DecodedFrame>(frames.size)
        try {
            for (frame in frames) {
                val image = PrototypeRenderer.decodeUpright(frame.file, REFINE_LONG_EDGE)
                    ?: return frames
                decoded += DecodedFrame(
                    image = image,
                    // The prototype renderer is a pinhole model, so refine
                    // against a pinhole model too: a distortion correction here
                    // would be undone by the renderer and the frames would end
                    // up rotated back out of alignment.
                    intrinsics = FrameIntrinsics.forLens(
                        widthPx = image.cols(),
                        heightPx = image.rows(),
                        horizontalFovDegrees = hfov,
                        verticalFovDegrees = vfov,
                        distortion = null,
                    ),
                    sensorBasis = CameraBasis.of(frame.pose),
                )
            }

            val result = try {
                PoseRefiner.refine(
                    frames = decoded,
                    horizontalFovDegrees = hfov,
                    verticalFovDegrees = vfov,
                    pivotRatio = pivot.ratio,
                    onProgress = { done, total ->
                        onProgress(StitchProgress(StitchStage.Refining, done, total))
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logging.w("Extended reference refinement failed, using grid poses", error)
                return frames
            }

            Logging.i(
                "Extended reference refinement: ${result.matchedEdges} matched edges, " +
                    "${decoded.size} frames",
            )
            if (result.matchedEdges == 0) return frames

            return frames.mapIndexed { index, frame ->
                val basis = result.bases.getOrNull(index) ?: return@mapIndexed frame
                val pose = basis.toPose()
                frame.copy(
                    pose = frame.pose.copy(
                        yawDegrees = pose.yawDegrees,
                        pitchDegrees = pose.pitchDegrees,
                        rollDegrees = pose.rollDegrees,
                        matrix = null,
                    ),
                )
            }
        } finally {
            decoded.forEach { it.image.release() }
        }
    }

    /**
     * Swaps the drifting sensor yaw and pitch for the coordinates the grid
     * asked for. Roll is left alone: the grid says nothing about it and the
     * sensor measures it well.
     */
    private fun applyPlannedPoses(
        frames: List<SphereFrame>,
        planned: Map<Int, CameraPose>,
    ): List<SphereFrame> {
        if (planned.isEmpty()) return frames
        return frames.map { frame ->
            val index = frameIndex(frame.file.name) ?: return@map frame
            val target = planned[index] ?: return@map frame
            frame.copy(
                pose = frame.pose.copy(
                    yawDegrees = target.yawDegrees,
                    pitchDegrees = target.pitchDegrees,
                ),
            )
        }
    }

    /** The index of an `extended_NN.jpg`, or null for any other file. */
    private fun frameIndex(name: String): Int? =
        FRAME_NAME.matchEntire(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Renders the adaptive plan the way production does: the normal stitcher,
     * pose refinement on, the profile's settings.
     */
    private suspend fun renderAdaptive(
        frames: List<SphereFrame>,
        optics: SphereOptics,
        onProgress: (StitchProgress) -> Unit,
    ): Bitmap {
        val profile = SphereDeviceProfile.forDevice()
        return PhotoSphereStitcher.stitchPhotos(
            frames = frames,
            horizontalFovDegrees = optics.fieldOfView.horizontalDegrees,
            verticalFovDegrees = optics.fieldOfView.verticalDegrees,
            radialDistortion = optics.radialDistortion,
            maxInputDimension = MAX_INPUT_DIMENSION,
            maxOutputWidth = profile.stitchMaxOutputWidth,
            unsharpAmount = profile.unsharpAmount,
            pivot = pivotFor(Mode.Adaptive),
            portraitRotationDegrees = optics.portraitRotationDegrees,
            useRefinement = true,
            seamMode = SeamMode.WinnerTakeAll,
            longitudeSpanDegrees = 360f,
            // The sidecar yaw is measured from where the walk started, so
            // zero is already the bearing the user began facing: it lands
            // in the middle of the photo, as every 360 viewer expects.
            centerLongitudeDegrees = 0f,
            latitudeSpanDegrees = 180f,
            centerLatitudeDegrees = 0f,
            onProgress = onProgress,
        ).getOrThrow()
    }

    /**
     * Every `extended_NN.jpg` that has a `.pose` sibling, in file order. A
     * frame whose sidecar is missing or unreadable is dropped, not defaulted: a
     * wrong pose is worse than a missing frame.
     */
    private fun readFrames(directory: File): List<SphereFrame> =
        directory.listFiles()
            ?.filter { it.isFile && frameIndex(it.name) != null }
            ?.sortedBy { it.name }
            .orEmpty()
            .mapNotNull { jpeg ->
                val sidecar = File(jpeg.parentFile, jpeg.nameWithoutExtension + POSE_SUFFIX)
                val pose = readPose(sidecar) ?: return@mapNotNull null
                SphereFrame(file = jpeg, pose = pose)
            }

    /** Parses the `yaw=..;pitch=..;roll=..` sidecar `ExtendedStore` writes. */
    private fun readPose(file: File): CameraPose? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val match = POSE_RE.find(text) ?: return null
        val (yaw, pitch, roll) = match.destructured
        return runCatching {
            CameraPose(yawDegrees = yaw.toFloat(), pitchDegrees = pitch.toFloat(), rollDegrees = roll.toFloat())
        }.getOrNull()
    }

    private val POSE_RE =
        Regex("""yaw=(-?\d+(?:\.\d+)?);pitch=(-?\d+(?:\.\d+)?);roll=(-?\d+(?:\.\d+)?)""")

    /**
     * The pivot a run is stitched with, which follows what the run stands in
     * for.
     *
     * The reference grid was proven with [REFERENCE_PIVOT]: a phone held out in
     * front of a standing person, in a scene taken to sit 3.5 m away. The
     * adaptive plan is production's shape, so it uses production's pivot
     * ([SphereDeviceProfile.pivot]), which assumes the scene sits at 10 m and is
     * free to under correct a close one because the pose refiner absorbs it.
     */
    fun pivotFor(mode: Mode): PivotModel =
        if (mode == Mode.Reference) {
            REFERENCE_PIVOT
        } else {
            SphereDeviceProfile.forDevice().pivot
        }

    /**
     * The pivot the reference grid was proven with.
     *
     * Not [PivotModel.HandheldBodySwivel], which production uses and which
     * assumes the scene sits at 10 m. 3.5 m is both what the prototype used and
     * what a room actually is, and it is recorded in `optics.txt` so the
     * offline replay can match. The refinement pass then absorbs the residual
     * aim error, as it does on the production path.
     */
    private val REFERENCE_PIVOT = PivotModel(leverArmMeters = 0.35f, sceneDistanceMeters = 3.5f)

    /**
     * Long edge the adaptive plan decodes each frame to. Deliberately smaller
     * than the device profile's 2000.
     *
     * The stitcher holds every decoded frame at once, and a grid run is up to 62
     * frames where the normal capture is around 33, so the profile's 2000 would
     * be roughly twice the production footprint (about 560 MB of pixels against
     * 140 MB here) for no visible gain: the ten frames of a ring still carry
     * about 7500 px of width around the 360 degrees that render into a canvas
     * of about 5500 px. The full size stays the production path's job. The
     * reference grid does not use this: it is decoded and stitched by
     * [PrototypeRenderer], one frame at a time.
     */
    internal const val MAX_INPUT_DIMENSION = 1024

    /**
     * The width the first reference stitch uses: quick and light. The screen can
     * ask for [PrototypeRenderer.HIGH_RESOLUTION_OUTPUT_WIDTH] afterwards, which
     * matches the golden but needs far more RAM and minutes of compute.
     */
    internal const val DEFAULT_REFERENCE_WIDTH = 2048

    /** `extended_NN.jpg` exactly: burst scratch files must never be stitched. */
    private val FRAME_NAME = Regex("""extended_(\d{2})\.jpg""")

    private const val POSE_SUFFIX = ".pose"

    /**
     * Long edge the reference refinement decodes to. ORB only needs enough
     * pixels to recognise an overlap; the renderer then reads the untouched
     * frame at half resolution again. Kept small so 62 decoded frames stay
     * comfortably inside the debug heap.
     */
    private const val REFINE_LONG_EDGE = 640

    /** Raw OpenCV-encoded reference copy written beside a run. */
    private const val REFERENCE_RAW_NAME = "reference_raw.jpg"
}
