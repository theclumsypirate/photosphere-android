package com.n30dyn4m1c.photosphere.diagnostics

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Reads the capture attitude back out of every frame and writes it as a text
 * report.
 *
 * Each frame's attitude was stamped into the JPEG at capture time
 * (see [com.n30dyn4m1c.photosphere.storage.SphereImageStore.stampCaptureOrientation],
 * which wrote it as an EXIF `UserComment` in the fixed
 * `index=d;yaw=d;pitch=d;roll=d` form). This is the read side of that: the
 * values come out of the files themselves, one `ExifInterface` read per frame,
 * so the report reflects exactly what is on disk rather than what the stitcher
 * held in memory.
 *
 * The report is aimed at the stitching workbench outside the app: every pose
 * line carries the stamped string verbatim, in the `yaw=..;pitch=..;roll=..`
 * shape the CI pose reader searches for. A line copied into a sidecar text
 * file named after its frame becomes that frame's pose source for the CI
 * geometry solve, without any EXIF parsing on that side.
 *
 * Debug only, like the surface it is offered from. Nothing here touches the
 * capture path, the stitch or the normal UI: it reads files and writes one
 * text file into the spheres cache, which the share sheet already serves.
 */
object PoseExport {

    /**
     * One frame's attitude as the file on disk carries it.
     *
     * A frame without a readable stamp carries null values and is still listed,
     * so the report's line count always matches the frame count, and a missing
     * stamp is as informative as a present one.
     */
    data class FramePose(
        /** The frame this attitude came from. */
        val file: File,
        /** The capture order the stamp carries, or negative when unreadable. */
        val index: Int,
        /** Yaw in degrees, or null when the stamp could not be read. */
        val yawDegrees: Double?,
        /** Pitch in degrees, or null when the stamp could not be read. */
        val pitchDegrees: Double?,
        /** Roll in degrees, or null when the stamp could not be read. */
        val rollDegrees: Double?,
    )

    /**
     * The stamped string, back into its numbers. The same fixed form the
     * capture wrote: one `index`, then the three angles, separated by
     * semicolons, every angle signed decimal.
     */
    private val STAMP = Regex(
        "index=(-?\\d+);yaw=(-?[\\d.]+);pitch=(-?[\\d.]+);roll=(-?[\\d.]+)",
    )

    /**
     * Reads one frame's attitude out of its EXIF, or null when the file holds
     * no parseable stamp.
     */
    fun read(file: File): FramePose? = try {
        val stamp = ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT)
        val match = stamp?.let { STAMP.find(it) } ?: return null
        FramePose(
            file = file,
            index = match.groupValues[1].toInt(),
            yawDegrees = match.groupValues[2].toDouble(),
            pitchDegrees = match.groupValues[3].toDouble(),
            rollDegrees = match.groupValues[4].toDouble(),
        )
    } catch (e: Exception) {
        Logging.w("Could not read the pose of ${file.name}")
        null
    }

    /**
     * Writes the pose report into the spheres cache and returns it.
     *
     * One line per frame, in capture order of the files given: the frame name,
     * then the stamped `index=..;yaw=..;pitch=..;roll=..` string, or an explicit
     * "no pose stamped" when the frame carries nothing readable. A frame line
     * pasted into a sidecar file next to that frame is directly usable by the
     * CI pose reader, which searches every sidecar for the same
     * `yaw=..;pitch=..;roll=..` shape.
     *
     * Blocking I/O (an EXIF read per frame plus one small write) - call off the
     * main thread. Fails as a [Result], never by throwing: the caller is a
     * button, and a failed report is cheaper than a crashed screen.
     */
    fun export(context: Context, frames: List<File>): Result<File> = runCatching {
        if (frames.isEmpty()) {
            throw IllegalStateException("no frames to export poses for")
        }
        val directory = File(context.cacheDir, SphereImageStore.SPHERES_DIRECTORY_NAME)
            .apply { mkdirs() }
        // One fixed name: the report describes the sphere the user is holding,
        // so overwriting the last one cannot lose a previous run's truth.
        val file = File(directory, "poses.txt")
        val poses = frames.map { frame ->
            frame to (read(frame) ?: FramePose(frame, -1, null, null, null))
        }
        FileOutputStream(file).use { out ->
            out.write(("# PhotoSphere capture poses, read back from each frame's" +
                " EXIF UserComment\n").toByteArray())
            out.write(("# angle order is exactly what the capture stamped:" +
                " the app's yaw, pitch and roll\n").toByteArray())
            out.write(("# a line below, saved as a sidecar next to its frame," +
                " is directly readable by the CI pose tool\n").toByteArray())
            poses.forEach { (frame, pose) ->
                val line = if (pose.yawDegrees == null) {
                    "${frame.name}\tno pose stamped"
                } else {
                    "%s\tindex=%d;yaw=%.6f;pitch=%.6f;roll=%.6f".format(
                        Locale.US,
                        frame.name,
                        pose.index,
                        pose.yawDegrees,
                        pose.pitchDegrees,
                        pose.rollDegrees,
                    )
                }
                out.write((line + "\n").toByteArray())
            }
        }
        Logging.i("Pose report written: ${file.name}, ${frames.size} frames")
        file
    }
}
