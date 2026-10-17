package com.n30dyn4m1c.photosphere.stairstest

import android.content.Context
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the stairs grid photos live, and their pose sidecars.
 *
 * Frames are written to the app's external files directory, which `adb pull`
 * can read on a debug build without root:
 *
 * ```
 * adb pull /sdcard/Android/data/theclumsypirate.com.photosphere.github.debug/files/stairs_grid
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
object StairsStore {

    /** Directory name under the app's external files dir. */
    const val GRID_DIRECTORY = "stairs_grid"

    /** Fresh directory for one sweep, created now. */
    fun newGridDirectory(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(root, GRID_DIRECTORY), "run_$stamp").apply {
            mkdirs()
            Logging.i("Stairs grid directory ${absolutePath}")
        }
    }

    /** Where frame [index] of a sweep is written. */
    fun frameFile(directory: File, index: Int): File =
        File(directory, "stairs_%02d.jpg".format(index))

    /** Where frame [index]'s pose sidecar is written. */
    fun poseFile(directory: File, index: Int): File =
        File(directory, "stairs_%02d.pose".format(index))

    /** Where one shot of a burst is written, before the sharpest is promoted. */
    fun burstFrameFile(directory: File, index: Int, burst: Int): File =
        File(directory, "stairs_%02d_t%d.jpg".format(index, burst))

    /**
     * Every file belonging to frame [index]: the frame, its pose, and any burst
     * candidate left behind by a run that was interrupted mid-burst.
     */
    fun frameFiles(directory: File, index: Int): List<File> {
        val prefix = "stairs_%02d".format(index)
        return directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) }
    }

    /**
     * Indices that already have both a frame and its pose on disk.
     *
     * The directory is the truth about how far a sweep got, so the screen reads
     * it back instead of trusting state that a recreation would drop: a run
     * interrupted halfway can be picked up where it stopped. Burst scratch files
     * are not counted, only the promoted `stairs_NN.jpg` of each target.
     */
    fun savedIndices(directory: File): Set<Int> =
        directory.listFiles().orEmpty()
            .mapNotNull { file ->
                val match = FRAME_NAME.matchEntire(file.name) ?: return@mapNotNull null
                val index = match.groupValues[1].toInt()
                index.takeIf { poseFile(directory, index).isFile }
            }
            .toSet()

    /** `stairs_NN.jpg` exactly, so burst scratch names never match. */
    private val FRAME_NAME = Regex("""stairs_(\d{2})\.jpg""")

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
     * Records one run's lens geometry and frame size, plus the exact stitch
     * command to replay it offline.
     *
     * The offline stitcher needs to know the field of view the frames were
     * shot at. Its defaults (49 by 63 degrees) belong to the virtual set cut
     * from a source panorama, not to any particular phone lens, so a real run
     * must carry its own. [hFovDegrees] and [vFovDegrees] are the sensor's
     * horizontal and vertical field of view, which is the landscape reading;
     * the file assigns them to whichever image axis is longer, because the
     * field of view grows with the physical axis, so a portrait frame is
     * simply the landscape one rotated.
     */
    fun writeOptics(
        directory: File,
        hFovDegrees: Float?,
        vFovDegrees: Float?,
        frameWidth: Int,
        frameHeight: Int,
        frameCount: Int,
    ) {
        runCatching {
            val portrait = frameHeight >= frameWidth
            val sensorLong = listOfNotNull(hFovDegrees, vFovDegrees).maxOrNull()
            val sensorShort = listOfNotNull(hFovDegrees, vFovDegrees).minOrNull()
            val widthFov = if (portrait) sensorShort else sensorLong
            val heightFov = if (portrait) sensorLong else sensorShort
            val command = buildString {
                append(
                    "python3 stitch_closed.py --dir %s --out stairs_real --fw %d --fh %d"
                        .format(Locale.US, directory.name, frameWidth, frameHeight),
                )
                if (widthFov != null && heightFov != null) {
                    append(
                        " --hfov %.2f --vfov %.2f".format(Locale.US, widthFov, heightFov),
                    )
                }
            }
            val text = buildString {
                appendLine("frames=$frameCount")
                appendLine("frame_width=$frameWidth")
                appendLine("frame_height=$frameHeight")
                appendLine("$PORTRAIT_KEY=${portrait}")
                appendLine("sensor_hfov_degrees=${hFovDegrees ?: "unknown"}")
                appendLine("sensor_vfov_degrees=${vFovDegrees ?: "unknown"}")
                appendLine("width_axis_fov_degrees=${widthFov ?: "unknown"}")
                appendLine("height_axis_fov_degrees=${heightFov ?: "unknown"}")
                appendLine()
                appendLine("# Stitch this run with the optics it was shot at.")
                appendLine(command)
            }
            opticsFile(directory).writeText(text)
            Logging.i("Stairs optics: $command")
        }.onFailure { error ->
            Logging.w("Could not write optics for ${directory.name}", error)
        }
    }

    /** Key marking whether the captured frames are taller than they are wide. */
    const val PORTRAIT_KEY = "portrait"
}
