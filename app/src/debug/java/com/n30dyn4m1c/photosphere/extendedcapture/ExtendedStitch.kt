package com.n30dyn4m1c.photosphere.extendedcapture

import android.content.Context
import com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile
import com.n30dyn4m1c.photosphere.camera.SphereOptics
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.PivotModel
import com.n30dyn4m1c.photosphere.stitching.SeamMode
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
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
 *   `tools/pano_proto/stitch_closed.py`. That script trusts the measured poses
 *   and paints winner takes all, and this keeps its choices on purpose, because
 *   they are the ones the grid was proven with. A grid that only stitches when
 *   the poses are refined is a grid that still needs work.
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
        /** The proven grid: measured poses, no refinement, the prototype's pivot. */
        Reference,

        /** The production plan shape: refinement on, the profile's pivot. */
        Adaptive,
    }

    /**
     * Reads [directory] and renders it into one sphere.
     *
     * [onProgress] is called from the stitching thread, not the main one; the
     * caller must post it rather than writing Compose state from it.
     */
    suspend fun stitch(
        context: Context,
        directory: File,
        optics: SphereOptics,
        mode: Mode,
        onProgress: (StitchProgress) -> Unit = {},
    ): SphereImageStore.StitchedSphere {
        val frames = readFrames(directory)
        Logging.i(
            "Extended stitch: ${frames.size} posed frames in ${directory.name} mode=$mode",
        )
        check(frames.isNotEmpty()) { "No posed frames in ${directory.name}" }

        val profile = SphereDeviceProfile.forDevice()
        val bitmap = try {
            PhotoSphereStitcher.stitchPhotos(
                frames = frames,
                horizontalFovDegrees = optics.fieldOfView.horizontalDegrees,
                verticalFovDegrees = optics.fieldOfView.verticalDegrees,
                radialDistortion = optics.radialDistortion,
                maxInputDimension = MAX_INPUT_DIMENSION,
                maxOutputWidth = profile.stitchMaxOutputWidth,
                unsharpAmount = profile.unsharpAmount,
                pivot = pivotFor(mode),
                portraitRotationDegrees = optics.portraitRotationDegrees,
                useRefinement = mode == Mode.Adaptive,
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
        } catch (e: CancellationException) {
            throw e
        }

        try {
            return withContext(Dispatchers.IO) {
                SphereImageStore.writeStitchedSphere(
                    context = context,
                    bitmap = bitmap,
                    gpano = GPanoMetadata.forFullPano(bitmap.width, bitmap.height),
                ).copy(
                    diagnostics = "Extended capture ($mode): ${frames.size} frames\n" +
                        "Run: ${directory.name}\n",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Every `extended_NN.jpg` that has a `.pose` sibling, in file order. A
     * frame whose sidecar is missing or unreadable is dropped, not defaulted: a
     * wrong pose is worse than a missing frame.
     */
    private fun readFrames(directory: File): List<SphereFrame> =
        directory.listFiles()
            ?.filter { it.isFile && FRAME_NAME.matches(it.name) }
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
     * assumes the scene sits at 10 m. That ratio is deliberately cautious, and
     * it is cautious because the production pipeline is free to refine the
     * poses afterwards: the class docs are explicit that the under correction
     * of a close scene stays "comfortably inside what PoseRefiner absorbs".
     * This stitch turns the refinement off on purpose, so nothing absorbs it,
     * and a room shot with the cautious ratio would bend at the seams for a
     * reason that has nothing to do with the grid. 3.5 m is both what the
     * prototype used and what a room actually is, and it is recorded in
     * `optics.txt` so the offline replay can match.
     */
    private val REFERENCE_PIVOT = PivotModel(leverArmMeters = 0.35f, sceneDistanceMeters = 3.5f)

    /**
     * Long edge each frame is decoded to before stitching, deliberately smaller
     * than the device profile's 2000.
     *
     * The stitcher holds every decoded frame at once, and a grid run is up to 62
     * frames where the normal capture is around 33, so the profile's 2000 would
     * be roughly twice the production footprint (about 560 MB of pixels against
     * 140 MB here) for no visible gain: the ten frames of a ring still carry
     * about 7500 px of width around the 360 degrees that render into a canvas
     * of about 5500 px. The full size stays the production path's job.
     */
    internal const val MAX_INPUT_DIMENSION = 1024

    /** `extended_NN.jpg` exactly: burst scratch files must never be stitched. */
    private val FRAME_NAME = Regex("""extended_\d{2}\.jpg""")

    private const val POSE_SUFFIX = ".pose"
}
