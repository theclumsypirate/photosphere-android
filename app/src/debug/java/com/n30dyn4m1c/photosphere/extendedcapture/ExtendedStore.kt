package com.n30dyn4m1c.photosphere.extendedcapture

import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.stitching.PivotModel
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the extended capture photos live, and their pose sidecars.
 *
 * Frames are written to the app's external files directory, which `adb pull`
 * can read on a debug build without root:
 *
 * ```
 * adb pull /sdcard/Android/data/theclumsypirate.com.photosphere.github.debug/files/extended_capture
 * ```
 *
 * Each frame gets a sibling `.pose` text file holding the stamped attitude.
 * The pose is written to a sidecar rather than into the JPEG's EXIF on
 * purpose: `ExifInterface.saveAttributes` rewrites the file, and these frames
 * exist to be stitched at the highest quality the lens can give, byte for
 * byte as CameraX wrote them. `tools/pano_proto/stitch_closed.py` reads the
 * sidecar first and falls back to the EXIF comment.
 *
 * Debug only: only referenced from `app/src/debug`.
 */
object ExtendedStore {

    /** Directory name under the app's external files dir. */
    const val CAPTURE_DIRECTORY = "extended_capture"

    /** Fresh directory for one sweep, created now. */
    fun newCaptureDirectory(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(root, CAPTURE_DIRECTORY), "run_$stamp").apply {
            mkdirs()
            Logging.i("Extended capture directory ${absolutePath}")
        }
    }

    /** Where frame [index] of a sweep is written. */
    fun frameFile(directory: File, index: Int): File =
        File(directory, "extended_%02d.jpg".format(index))

    /** Where frame [index]'s pose sidecar is written. */
    fun poseFile(directory: File, index: Int): File =
        File(directory, "extended_%02d.pose".format(index))

    /** Where one shot of a burst is written, before the sharpest is promoted. */
    fun burstFrameFile(directory: File, index: Int, burst: Int): File =
        File(directory, "extended_%02d_t%d.jpg".format(index, burst))

    /**
     * Every file belonging to frame [index]: the frame, its pose, and any burst
     * candidate left behind by a run that was interrupted mid-burst.
     */
    fun frameFiles(directory: File, index: Int): List<File> {
        val prefix = "extended_%02d".format(index)
        return directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) }
    }

    /**
     * Indices that already have both a frame and its pose on disk.
     *
     * The directory is the truth about how far a sweep got, so the screen reads
     * it back instead of trusting state that a recreation would drop: a run
     * interrupted halfway can be picked up where it stopped. Burst scratch files
     * are not counted, only the promoted `extended_NN.jpg` of each target.
     */
    fun savedIndices(directory: File): Set<Int> =
        directory.listFiles().orEmpty()
            .mapNotNull { file ->
                val match = FRAME_NAME.matchEntire(file.name) ?: return@mapNotNull null
                val index = match.groupValues[1].toInt()
                index.takeIf { poseFile(directory, index).isFile }
            }
            .toSet()

    /** `extended_NN.jpg` exactly, so burst scratch names never match. */
    private val FRAME_NAME = Regex("""extended_(\d{2})\.jpg""")

    /** Where one run's optics note is written. */
    fun opticsFile(directory: File): File = File(directory, "optics.txt")

    /**
     * Writes the attitude beside frame [index] in the same
     * `yaw=..;pitch=..;roll=..` form the app stamps into EXIF, so the Python
     * side parses one format either way.
     */
    fun writePose(
        directory: File,
        index: Int,
        yawDegrees: Float,
        pitchDegrees: Float,
        rollDegrees: Float,
    ) {
        runCatching {
            poseFile(directory, index).writeText(
                "yaw=%.3f;pitch=%.3f;roll=%.3f".format(
                    Locale.US,
                    yawDegrees,
                    pitchDegrees,
                    rollDegrees,
                ),
            )
        }.onFailure { error ->
            Logging.w("Could not write pose for frame $index", error)
        }
    }

    /**
     * A frame's pixel size as it is displayed, with any EXIF rotation applied,
     * or null when the file will not describe itself.
     *
     * `stitch_closed.py` turns each frame upright on its EXIF orientation and
     * then resizes it to half of `--fw` by `--fh`, so the numbers in the note
     * have to be the upright ones. A portrait capture whose pixels CameraX left
     * landscape would otherwise be resized on the wrong axes and come out
     * sheared. The rotation read here is the same tag the Kotlin stitcher
     * applies through `portraitRotationDegrees`.
     */
    fun uprightFrameSize(framePath: String): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(framePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = runCatching {
            ExifInterface(framePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        return if (rotated) {
            bounds.outHeight to bounds.outWidth
        } else {
            bounds.outWidth to bounds.outHeight
        }
    }

    /**
     * Records one run's lens geometry, frame size and stitch settings, plus the
     * exact command to replay it offline.
     *
     * The offline stitcher needs the field of view the frames were shot at. Its
     * defaults (49 by 63 degrees) belong to the virtual set cut from a source
     * panorama, not to any particular phone lens, so a real run has to carry its
     * own. [widthFovDegrees] and [heightFovDegrees] are the two the on phone
     * stitch was handed, which are already the angles across the frame's width
     * and height as displayed, so they go into `--hfov` and `--vfov` as they
     * are. They describe the same frame as [frameWidth] by [frameHeight], which
     * is why that pair has to be the upright one.
     *
     * [pivot] is recorded because the offline replay has to use the same ratio
     * to be comparable, and [decodeMaxDimension] because it is a deliberate step
     * down from production that the replay shares. [refinement], [planName] and
     * [lensId] do not change the math; they make a run self describing, so a
     * sphere stitched weeks later can say which lens shot it, which plan it
     * walked, and whether the poses were refined.
     */
    fun writeOptics(
        directory: File,
        frameWidth: Int,
        frameHeight: Int,
        widthFovDegrees: Float,
        heightFovDegrees: Float,
        frameCount: Int,
        pivot: PivotModel,
        decodeMaxDimension: Int,
        refinement: Boolean,
        planName: String,
        lensId: String,
    ) {
        runCatching {
            val portrait = frameHeight > frameWidth
            val command = buildString {
                append(
                    "python3 stitch_closed.py --dir out/%s --out extended_real --fw %d --fh %d"
                        .format(Locale.US, directory.name, frameWidth, frameHeight),
                )
                append(
                    " --hfov %.2f --vfov %.2f".format(
                        Locale.US,
                        widthFovDegrees,
                        heightFovDegrees,
                    ),
                )
            }
            val text = buildString {
                appendLine("frames=$frameCount")
                appendLine("frame_width=$frameWidth")
                appendLine("frame_height=$frameHeight")
                appendLine("$PORTRAIT_KEY=$portrait")
                appendLine("width_axis_fov_degrees=%.3f".format(Locale.US, widthFovDegrees))
                appendLine("height_axis_fov_degrees=%.3f".format(Locale.US, heightFovDegrees))
                appendLine("pivot_lever_m=%.3f".format(Locale.US, pivot.leverArmMeters))
                appendLine("pivot_scene_m=%.3f".format(Locale.US, pivot.sceneDistanceMeters))
                appendLine("decode_max_dimension=$decodeMaxDimension")
                appendLine("refinement=$refinement")
                appendLine("plan=$planName")
                appendLine("lens=$lensId")
                appendLine()
                appendLine("# Pull this run into the prototype's output folder, then")
                appendLine("# stitch it with the optics it was shot at:")
                appendLine("#   adb pull <run dir on phone> tools/pano_proto/out/${directory.name}")
                appendLine("#   cd tools/pano_proto")
                appendLine(command)
            }
            opticsFile(directory).writeText(text)
            Logging.i("Extended optics: $command")
        }.onFailure { error ->
            Logging.w("Could not write optics for ${directory.name}", error)
        }
    }

    /** Key marking whether the captured frames are taller than they are wide. */
    const val PORTRAIT_KEY = "portrait"
}
