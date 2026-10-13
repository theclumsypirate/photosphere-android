package com.n30dyn4m1c.photosphere.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MediaExporter"

/**
 * Publishes a finished 360 photo to the device's public gallery.
 *
 * The stitched sphere lives in the app's cache until the user asks to keep it —
 * this is the step that asks. It writes into `Pictures/360Panoramas`, so the
 * spheres land in their own album rather than scattered through the camera roll,
 * and every gallery app, backup service and share target on the device can see
 * them.
 *
 * **The JPEG is copied byte for byte.** It is not decoded, re-compressed or
 * re-encoded, which is what keeps [com.n30dyn4m1c.photosphere.metadata.GPanoXmpInjector]'s
 * XMP packet intact — a re-encode would drop the metadata and the gallery would
 * show a wide flat picture instead of a pannable sphere.
 *
 * ### Two paths, one album
 *
 * From API 29 the insert is made **pending** (`IS_PENDING = 1`), the pixels are
 * streamed into the URI MediaStore hands back, and the row is published
 * (`IS_PENDING = 0`) only once the copy has finished. Nothing half-written is
 * ever visible in the gallery, and the app needs no storage permission for it.
 *
 * On API 26–28 there is no `RELATIVE_PATH` and no pending flag: the file is
 * written into the public Pictures directory directly and the row points at it
 * with `DATA`. That path needs `WRITE_EXTERNAL_STORAGE`, which
 * `MainActivity.REQUIRED_PERMISSIONS` requests on exactly those versions.
 */
object MediaExporter {

    /** Album the spheres are filed under, inside the public Pictures directory. */
    const val ALBUM = "360Panoramas"

    /**
     * The album as MediaStore's `RELATIVE_PATH` wants it: relative to the shared
     * storage root, with no leading slash.
     */
    const val RELATIVE_PATH = "Pictures/$ALBUM"

    private const val MIME_TYPE = "image/jpeg"

    private const val FILE_NAME_PREFIX = "panorama"

    /** A sphere as it now exists in the gallery. */
    data class ExportedPanorama(
        /** MediaStore row for the published image. Shareable, openable, deletable. */
        val uri: Uri,
        /** File name it was filed under, which may differ from the one requested. */
        val displayName: String,
        /** Album path it landed in, for telling the user where to look. */
        val relativePath: String = RELATIVE_PATH,
    )

    /** A gallery file name for a sphere exported now. Sorts chronologically. */
    fun newDisplayName(timestamp: Date = Date()): String =
        "%s_%s.jpg".format(
            FILE_NAME_PREFIX,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(timestamp),
        )

    /**
     * Copies [source] into `Pictures/360Panoramas`.
     *
     * [width] and [height] are stored on the row so gallery apps can lay the
     * image out without decoding it first; pass the sphere's real dimensions or
     * null.
     *
     * Runs on [dispatcher] and is [NonCancellable] once the row exists: a copy
     * abandoned halfway would leave a pending row that nothing will ever publish
     * or clean up. Failures come back as a failed [Result] — a gallery that
     * refuses the write is something to tell the user about, not to crash over.
     */
    suspend fun export(
        context: Context,
        source: File,
        displayName: String = newDisplayName(),
        width: Int? = null,
        height: Int? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Result<ExportedPanorama> = withContext(dispatcher + NonCancellable) {
        runCatching {
            if (!source.isFile) throw IOException("${source.name} is not there to export")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportPending(context, source, displayName, width, height)
            } else {
                exportLegacy(context, source, displayName, width, height)
            }
        }.onFailure { error ->
            Log.e(TAG, "Could not export ${source.name} to $RELATIVE_PATH", error)
        }
        .onSuccess { exported ->
            Log.i(TAG, "Exported ${exported.displayName} to $RELATIVE_PATH")
        }
    }

    /**
     * Copies every frame in [sources] into `Pictures/360Panoramas` as its own
     * photo — the "save what I shot" escape hatch.
     *
     * Names carry the session id and the position, so re-exporting the same run
     * does not collide. The attitude EXIF stamped at capture travels along
     * untouched. Returns how many landed; any failure fails the whole batch.
     */
    suspend fun exportFrames(
        context: Context,
        sources: List<File>,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Result<Int> = withContext(dispatcher + NonCancellable) {
        runCatching {
            var saved = 0
            sources.forEachIndexed { position, source ->
                export(context, source, displayName = frameDisplayName(source, position))
                    .getOrThrow()
                saved++
            }
            saved
        }.onFailure { error ->
            Log.e(TAG, "Could not export ${sources.size} frames to $RELATIVE_PATH", error)
        }
    }

    /** Gallery name for one frame: session + position, sorts chronologically. */
    fun frameDisplayName(source: File, position: Int): String =
        "frame_%s_%02d.jpg".format(source.parentFile?.name ?: "session", position)

    /** API 29+: insert pending, stream the bytes in, then publish. */
    private fun exportPending(
        context: Context,
        source: File,
        displayName: String,
        width: Int?,
        height: Int?,
    ): ExportedPanorama {
        val resolver = context.contentResolver
        val values = baseValues(displayName, width, height).apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore would not accept $displayName")

        try {
            val sink = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open $uri for writing")
            // A straight byte copy: no decode, no re-encode, so the GPano XMP
            // and every EXIF tag arrive in the gallery exactly as written.
            sink.use { out -> source.inputStream().use { bytes -> bytes.copyTo(out) } }
        } catch (e: Exception) {
            // A pending row nobody publishes is invisible but not free; drop it
            // rather than leaving a zero-byte entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        ).takeIf { it > 0 } ?: run {
            // The publish was refused or the row vanished under the copy; either
            // way the user must not be told "Saved to gallery" for a row that
            // stays pending and invisible. Drop the row and fail loudly.
            runCatching { resolver.delete(uri, null, null) }
            throw IOException("MediaStore would not publish $displayName")
        }

        return ExportedPanorama(uri = uri, displayName = displayName)
    }

    /**
     * API 26–28: write the file into the public album, then index it.
     *
     * Pre-scoped-storage MediaStore has no `RELATIVE_PATH` to place a file by and
     * no pending state to hide it behind, so the app owns the write and the row
     * is inserted afterwards, pointing at what is already on disk. Inserting
     * first would leave a row referring to a file that may never appear.
     */
    private fun exportLegacy(
        context: Context,
        source: File,
        displayName: String,
        width: Int?,
        height: Int?,
    ): ExportedPanorama {
        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM,
        )
        if (!album.isDirectory && !album.mkdirs()) {
            throw IOException("Could not create $RELATIVE_PATH")
        }

        val target = uniqueFile(album, displayName)
        try {
            source.inputStream().use { bytes ->
                target.outputStream().use { sink -> bytes.copyTo(sink) }
            }
        } catch (e: Exception) {
            // A copy that fails halfway leaves a partial JPEG in the user's
            // public Pictures directory; delete it rather than leaving clutter.
            target.delete()
            throw e
        }

        val values = baseValues(target.name, width, height).apply {
            put(MediaStore.Images.Media.DATA, target.absolutePath)
        }
        val uri = try {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore would not index ${target.name}")
        } catch (e: Exception) {
            // The row is what makes the file visible; without one the copy is
            // just clutter in the user's Pictures directory.
            target.delete()
            throw e
        }

        return ExportedPanorama(uri = uri, displayName = target.name)
    }

    /** The columns both paths set. */
    private fun baseValues(displayName: String, width: Int?, height: Int?): ContentValues =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            width?.let { put(MediaStore.Images.Media.WIDTH, it) }
            height?.let { put(MediaStore.Images.Media.HEIGHT, it) }
        }

    /**
     * [name] in [directory], or the first `name (n)` variant that is free.
     *
     * MediaStore does this itself from API 29; below it a second export in the
     * same second would silently overwrite the first.
     */
    private fun uniqueFile(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var attempt = 1
        while (true) {
            val next = File(directory, "$base ($attempt)$suffix")
            if (!next.exists()) return next
            attempt++
        }
    }
}
