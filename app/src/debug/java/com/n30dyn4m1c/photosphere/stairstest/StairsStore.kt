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
}
