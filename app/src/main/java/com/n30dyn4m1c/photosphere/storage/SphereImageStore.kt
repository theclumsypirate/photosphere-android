package com.n30dyn4m1c.photosphere.storage
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.metadata.GPanoXmpInjector
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where captured frames go.
 *
 * Three destinations, for three different kinds of image:
 *
 * - **Cache sessions** ([sessionDirectory], [newTempFrameOptions]) hold the raw
 *   frames of a capture run. They are intermediate data, they are large, and
 *   they are cleared when the next run starts.
 * - **The sphere cache** ([writeStitchedSphere]) holds the one finished
 *   equirectangular JPEG a run produces, complete with its GPano metadata. It
 *   lives there while the user looks at it on the result screen, and goes no
 *   further unless they ask.
 * - **MediaStore** is for images the user keeps, and is
 *   [MediaExporter]'s job — "Export to gallery" on the result screen is the only
 *   thing that puts a sphere in front of the rest of the system.
 *
 * Lifecycle: sessions and finished spheres are intermediate data, not keepsakes.
 * They are deleted when the user discards or starts over, and whatever survives
 * a process death is wiped shortly after the app was last backgrounded (see
 * [deleteImageCache]). The diagnostics logs live elsewhere and are never
 * touched here.
 */
object SphereImageStore {

    private const val TAG = Logging.TAG
    private const val ALBUM = "PhotoSphere"
    private const val MIME_TYPE = "image/jpeg"

    /** File-name prefix of finished spheres, for the stale sweep. */
    private const val SPHERE_FILE_PREFIX = "sphere_"

    /** Cache subdirectory holding one directory per capture session. */
    private const val SESSIONS_DIRECTORY = "sphere_sessions"

    /**
     * Cache subdirectory holding finished spheres awaiting a decision.
     *
     * Shared out through a `FileProvider`, so it is named in
     * `res/xml/file_paths.xml` too — the two must stay in step or sharing fails
     * with an `IllegalArgumentException` at the moment the user taps Share.
     */
    private const val SPHERES_DIRECTORY = "spheres"

    /** Public name of the spheres cache directory, for the application-level prune. */
    const val SPHERES_DIRECTORY_NAME = SPHERES_DIRECTORY

    /**
     * Encode quality for the finished sphere. High, because this is the only
     * image of the run that is kept and it has already been through one
     * generation of JPEG on the way in.
     */
    private const val SPHERE_JPEG_QUALITY = 95

    /** Output options plus the cache file they will write to. */
    data class TempFrameRequest(
        val outputOptions: ImageCapture.OutputFileOptions,
        val file: File,
    )

    /**
     * A stitched sphere on its way to the user: a GPano-tagged JPEG in the cache,
     * and the dimensions written into that metadata.
     *
     * Held as a file rather than a `Bitmap` because it is what both destinations
     * want — [MediaExporter] copies its bytes into the gallery and the share
     * sheet hands out a URI to it — and because a 4096×2048 bitmap is 32 MB of
     * heap to carry across a screen change for no reason.
     */
    data class StitchedSphere(
        val file: File,
        val width: Int,
        val height: Int,
        /** Debug-only text describing the frames and geometry that made it. */
        val diagnostics: String? = null,
        /** True when stitched with solid debug colours instead of pixels. */
        val debugColors: Boolean = false,
    )

    /** Identifier for one run of guided capture. Sorts chronologically. */
    fun newSessionId(): String {
        // Millisecond precision: the manager is recreated on configuration
        // changes, and two sessions born in the same second would share a
        // directory and silently mix their frames.
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    /**
     * Directory holding the frames of [sessionId], created if needed.
     *
     * Guided capture writes to the cache rather than the gallery: these frames
     * are stitcher input, and a user who asked for one sphere has not asked for
     * forty-four photos in their camera roll. Only the finished equirectangular
     * image belongs in MediaStore.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun sessionDirectory(context: Context, sessionId: String): File {
        val directory = File(File(context.cacheDir, SESSIONS_DIRECTORY), sessionId)
        if (!directory.exists() && !directory.mkdirs()) {
            // A failure here surfaces later as a baffling CameraX "no such file"
            // write error; an early, specific one is worth more.
            throw IOException("Could not create session directory ${directory.path}")
        }
        return directory
    }

    /** Path frame [index] of a session occupies. Zero-padded so it sorts. */
    fun frameFile(directory: File, index: Int): File =
        File(directory, "frame_%03d.jpg".format(index))

    /** Where frame [index] of a session should be written. */
    fun newTempFrameOptions(directory: File, index: Int): TempFrameRequest {
        val file = frameFile(directory, index)
        return TempFrameRequest(
            outputOptions = ImageCapture.OutputFileOptions.Builder(file).build(),
            file = file,
        )
    }

    /**
     * Path one candidate of frame [index]'s burst occupies.
     *
     * Burst shots sit alongside the canonical `frame_%03d.jpg` name as
     * `frame_%03d_t{k}.jpg` until a winner is chosen and promoted — the plain
     * name is left for the frame that is actually kept, so the session holds
     * exactly one file per index.
     */
    fun burstFrameFile(directory: File, index: Int, burst: Int): File =
        File(directory, "frame_%03d_t%d.jpg".format(index, burst))

    /** Prefix shared by every candidate of frame [index]'s burst. */
    fun burstPrefix(index: Int): String = "frame_%03d_t".format(index)

    /**
     * Removes one session's directory and everything in it.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun deleteSession(context: Context, sessionId: String) {
        val session = File(File(context.cacheDir, SESSIONS_DIRECTORY), sessionId)
        if (session.exists() && !session.deleteRecursively()) {
            Logging.w("Could not clear session $sessionId")
        }
    }

    /**
     * Deletes every session directory except [keepSessionId].
     *
     * A sphere's worth of full-resolution JPEGs is on the order of a hundred
     * megabytes. The cache is reclaimable by the system, but only under
     * pressure, so an abandoned run is cleared at the start of the next one
     * instead of being left for Android to notice.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun pruneSessions(context: Context, keepSessionId: String) {
        val root = File(context.cacheDir, SESSIONS_DIRECTORY)
        val sessions = root.listFiles() ?: return
        sessions.forEach { session ->
            if (session.name != keepSessionId) {
                if (!session.deleteRecursively()) {
                    Logging.w("Could not clear stale session ${session.name}")
                }
            }
        }
    }

    /**
     * Records the device attitude a frame was shot at, in EXIF.
     *
     * The stitcher gets a starting guess at each frame's place on the sphere
     * from this, which is the difference between searching for correspondences
     * and merely confirming them. Written as a `UserComment` because EXIF has no
     * standard tag for camera attitude, in a fixed `key=value;` form so it can be
     * parsed back without ambiguity.
     *
     * Note: this is only for frames an external tool inspects. The stitch reads
     * the attitude out of the in-memory [com.n30dyn4m1c.photosphere.storage.BufferedFrame]
     * instead, and it must never be applied through [ExifInterface.saveAttributes] —
     * that rewrite re-encodes the whole JPEG and has dropped CameraX's
     * `ORIENTATION` tag on some files, which is what `decodeUpright` rotates
     * the frame by. The rotation tag is the one thing the file cannot lose.
     */
    fun stampCaptureOrientation(
        file: File,
        index: Int,
        yawDegrees: Float,
        pitchDegrees: Float,
        rollDegrees: Float,
    ) {
        try {
            ExifInterface(file).apply {
                // Read the rotation CameraX recorded first and write it back
                // explicitly: saveAttributes() must not silently reset it to
                // NORMAL, or every frame of the session decodes landscape.
                val rotation = getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
                setAttribute(ExifInterface.TAG_ORIENTATION, rotation.toString())
                setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere")
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "$ALBUM frame $index")
                setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    "index=%d;yaw=%.3f;pitch=%.3f;roll=%.3f"
                        .format(Locale.US, index, yawDegrees, pitchDegrees, rollDegrees),
                )
                saveAttributes()
            }
        } catch (e: Exception) {
            // Metadata is a nice-to-have; never lose the frame over it.
            Logging.w("Could not write EXIF for ${file.name}", e)
        }
    }

    /**
     * Encodes a finished equirectangular sphere into the cache as a 360 photo.
     *
     * This is the handover between stitching and the result screen: the pixels
     * come off the heap into a JPEG, the JPEG is tagged, and everything
     * downstream — preview, gallery export, share sheet — works from that one
     * file.
     *
     * [gpano] is the metadata a viewer reads to treat the file as a pano. It
     * defaults to a full sphere ([GPanoMetadata.forFullPano]); a ring capture
     * passes a region ([GPanoMetadata.forSphereRegion]) so the band the frames
     * covered is what viewers render.
     *
     * The order of the two metadata passes matters. EXIF goes on first, because
     * [ExifInterface.saveAttributes] rewrites the whole JPEG and makes no promise
     * about carrying unrecognised segments across; GPano goes on second, so the
     * marker that makes viewers treat this as a sphere is written by the last
     * thing to touch the file. Neither pass re-encodes the image.
     *
     * The new sphere is built up under a temporary name and moved onto its final
     * name only once it is complete. The previous sphere is cleared *after* that
     * swap, so a failed encode or metadata pass never destroys the last good
     * output — the user keeps the older sphere instead of being left with
     * nothing. Only one sphere is ever in play, and each is several megabytes.
     *
     * Blocking I/O and a full-size compress — call off the main thread. Throws
     * [IOException] if the encode fails.
     */
    fun writeStitchedSphere(
        context: Context,
        bitmap: Bitmap,
        quality: Int = SPHERE_JPEG_QUALITY,
        gpano: GPanoMetadata = GPanoMetadata.forFullPano(bitmap.width, bitmap.height),
    ): StitchedSphere {
        val directory = spheresDirectory(context)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "sphere_%s.jpg".format(timestamp))
        val temp = File(directory, file.name + ".tmp")

        try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw IOException("Could not encode the stitched sphere")
                }
            }
            stampSphereDescription(temp)
            GPanoXmpInjector.inject(temp, gpano)
                .onFailure { error ->
                    // Recoverable: the file is a valid 2:1 JPEG either way, it
                    // will simply open flat instead of as a sphere.
                    Logging.w("Could not write GPano metadata for ${file.name}", error)
                }
            // The new sphere is complete; move it into place (atomically — the
            // name is fresh, but REPLACE_EXISTING also absorbs a same-second
            // collision) and only then drop the previous one.
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                Logging.w("Could not finalise ${file.name}", e)
                throw e
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }

        // The previous sphere is cleared now the new one is complete. Only the
        // final-name pattern is swept: a `.tmp` beside the new file belongs to
        // whatever is still writing it (or a crashed run, which the cache
        // reclaims), and deleting it mid-write would corrupt that run.
        directory.listFiles()?.forEach { stale ->
            if (stale != file &&
                stale.name.startsWith(SPHERE_FILE_PREFIX) &&
                stale.name.endsWith(".jpg") &&
                !stale.delete()
            ) {
                Logging.w("Could not clear stale sphere ${stale.name}")
            }
        }

        Logging.i("Sphere written: ${file.name} ${bitmap.width}x${bitmap.height}")
        return StitchedSphere(file = file, width = bitmap.width, height = bitmap.height)
    }

    /** Directory holding the finished sphere of the current run, created if needed. */
    private fun spheresDirectory(context: Context): File =
        File(context.cacheDir, SPHERES_DIRECTORY).apply { mkdirs() }

    /**
     * A URI another app can read [file] through.
     *
     * The share sheet cannot be handed a `file://` URI — since API 24 that
     * throws `FileUriExposedException` — so the cached sphere goes out through
     * the app's `FileProvider`. The authority is derived from the running
     * package rather than hardcoded, because the debug build carries a
     * `.debug` suffix.
     */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Discards a cached sphere the user has finished with. */
    fun deleteCachedSphere(file: File) {
        if (file.exists() && !file.delete()) {
            Logging.w("Could not delete cached sphere ${file.name}")
        }
    }

    /**
     * Wipes every cached image: all capture sessions and all finished spheres.
     *
     * Called after the app was backgrounded and never came back (swiped out of
     * recents, then relaunched): after a process death no screen still
     * references these files, so they are garbage. The diagnostics logs
     * (`cache/logs`) and the saved crash reports (`filesDir/crashes`) are
     * deliberately left alone. Only pictures go.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun deleteImageCache(context: Context) {
        val sessions = File(context.cacheDir, SESSIONS_DIRECTORY)
        if (sessions.exists() && !sessions.deleteRecursively()) {
            Logging.w("Could not clear image session cache")
        }
        val spheres = File(context.cacheDir, SPHERES_DIRECTORY)
        if (spheres.exists() && !spheres.deleteRecursively()) {
            Logging.w("Could not clear sphere cache")
        }
    }

    /**
     * Marks a finished sphere as this app's output, in EXIF.
     *
     * Note: the "this is a 360 photo" marker that Google Photos and other
     * viewers look for is XMP GPano, not EXIF, and [ExifInterface] cannot write
     * XMP — that marker is [GPanoXmpInjector]'s job, on the same file, after
     * this pass.
     */
    private fun stampSphereDescription(file: File) {
        try {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere")
                setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "$ALBUM equirectangular panorama",
                )
                saveAttributes()
            }
        } catch (e: Exception) {
            // Metadata is a nice-to-have; never lose the sphere over it.
            Logging.w("Could not write EXIF for ${file.name}", e)
        }
    }
}
