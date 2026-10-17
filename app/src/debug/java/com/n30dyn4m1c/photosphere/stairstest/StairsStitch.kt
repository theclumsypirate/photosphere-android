package com.n30dyn4m1c.photosphere.stairstest

import android.content.Context
import com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile
import com.n30dyn4m1c.photosphere.camera.SphereOptics
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
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
 * Stitches a finished stairs grid run on the phone, with the same stitcher the
 * normal camera uses, so the grid can be judged without a laptop in the loop.
 *
 * This is the twin of `tools/pano_proto/stitch_closed.py`: that script proved
 * the 62 coordinates against a source panorama, and this does the same job
 * against a real capture. It keeps the script's choices on purpose, because
 * they are the ones the grid was proven with: the poses are the measured ones
 * (no image based refinement, so a bad grid cannot be quietly corrected), and
 * pixels are painted winner takes all rather than cross-faded.
 *
 * The pose comes from the `.pose` sidecar and nowhere else. The frames are
 * written untouched by design, so unlike the main camera there is no EXIF
 * comment to fall back on, and a frame with no sidecar is left out rather than
 * guessed at.
 *
 * Debug only: only referenced from `app/src/debug`.
 */
object StairsStitch {

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
        onProgress: (StitchProgress) -> Unit = {},
    ): SphereImageStore.StitchedSphere {
        val frames = readFrames(directory)
        Logging.i("Stairs stitch: ${frames.size} posed frames in ${directory.name}")
        check(frames.isNotEmpty()) { "No posed frames in ${directory.name}" }

        val profile = SphereDeviceProfile.forDevice()
        val bitmap = try {
            PhotoSphereStitcher.stitchPhotos(
                frames = frames,
                horizontalFovDegrees = optics.fieldOfView.horizontalDegrees,
                verticalFovDegrees = optics.fieldOfView.verticalDegrees,
                radialDistortion = optics.radialDistortion,
                maxInputDimension = STAIRS_MAX_INPUT_DIMENSION,
                maxOutputWidth = profile.stitchMaxOutputWidth,
                unsharpAmount = profile.unsharpAmount,
                pivot = profile.pivot,
                portraitRotationDegrees = optics.portraitRotationDegrees,
                // The prototype's scheme, and the sensor poses it was proven
                // with. A grid that only stitches when the poses are refined is
                // a grid that still needs work.
                useRefinement = false,
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
                    diagnostics = "Stairs grid: ${frames.size} frames\n" +
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
     * Every `stairs_NN.jpg` that has a `.pose` sibling, in file order. A frame
     * whose sidecar is missing or unreadable is dropped, not defaulted: a wrong
     * pose is worse than a missing frame.
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

    /** Parses the `yaw=..;pitch=..;roll=..` sidecar `StairsStore` writes. */
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
     * Long edge each frame is decoded to before stitching, deliberately smaller
     * than the device profile's 2000.
     *
     * The stitcher holds every decoded frame at once, and a grid run is 62
     * frames where the normal capture is around 33, so the profile's 2000 would
     * be roughly twice the production footprint (about 560 MB of pixels against
     * 140 MB here) for no visible gain: the ten frames of a ring still carry
     * about 7500 px of width around the 360 degrees that render into a canvas
     * of about 5500 px. The full size stays the production path's job.
     */
    private const val STAIRS_MAX_INPUT_DIMENSION = 1024

    /** `stairs_NN.jpg` exactly: burst scratch files must never be stitched. */
    private val FRAME_NAME = Regex("""stairs_\d{2}\.jpg""")

    private const val POSE_SUFFIX = ".pose"
}
