package com.n30dyn4m1c.photosphere.storage
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = Logging.TAG

/** Quality used when a caller hands over a [Bitmap] instead of a file. */
private const val DEFAULT_JPEG_QUALITY = 95

/** Key the manager is stored under in the activity's [ViewModelStore]. */
private const val BUFFER_HOLDER_KEY = "photosphere.ImageBufferManager"

/**
 * Thrown (as a failed [Result]) when a frame's session was cancelled while its
 * shutter was in flight.
 *
 * The caller reports a superseded session as a *silent* drop rather than as a
 * capture failure: the user asked for the restart, so the frame disappearing is
 * the expected outcome, not something to surface as an error.
 */
class SessionSupersededException :
    IllegalStateException("session superseded, frame not captured")

/**
 * One buffered frame: where the pixels live, and where the camera was pointing.
 *
 * The angles are copied from [OrientationData] and keep its conventions —
 * [yawDegrees] is the camera's compass bearing and [pitchDegrees] is **negative
 * above the horizon**. They are held in memory as well as stamped into the
 * file's EXIF so the stitcher can order and pre-align frames without paying to
 * re-read forty-odd JPEG headers.
 */
data class BufferedFrame(
    /** Position in the capture plan; also the file's sequence number. */
    val index: Int,
    /** Full-resolution JPEG in the session's cache directory. */
    val file: File,
    /** Compass bearing of the camera at capture, -180°..180°. */
    val yawDegrees: Float,
    /** Vertical aim at capture, -90°..90°, negative above the horizon. */
    val pitchDegrees: Float,
    /** Side tilt at capture, -180°..180°. */
    val rollDegrees: Float,
    /**
     * The camera's basis as a rotation matrix — the `[right, −up, forward]`
     * columns in the world frame, in the `CameraBasis.toRotationMatrix` layout.
     * Present for every frame the tracker captured, and what the stitcher
     * reconstructs the pose from: it carries the orientation exactly where the
     * Euler components collapse (a frame aimed at the zenith). Null for frames
     * recorded without a sensor sample, e.g. in tests.
     */
    val cameraBasis: FloatArray? = null,
    /** Wall clock at capture, for ordering frames of equal index. */
    val capturedAtMillis: Long,
) {
    /** Height above the horizon, positive up — the sign most people expect. */
    val elevationDegrees: Float get() = -pitchDegrees
}

/**
 * The frames of one capture session, held together with the attitude each was
 * shot at.
 *
 * Guided capture produces frames faster than anything can consume them, and the
 * stitcher needs the whole set at once. This is the thing in between: capture
 * appends to it, the "Finish & stitch" button reads [files] out of it, and the
 * session is thrown away afterwards.
 *
 * Pixels stay on disk — a sphere is forty-odd full-resolution JPEGs, and holding
 * them as decoded bitmaps would be several hundred megabytes of heap for no
 * gain. What is kept in memory is the index of [BufferedFrame]s: paths plus
 * sensor metadata. [record] accepts either a file that CameraX has already
 * written or a [Bitmap] the caller holds, which it persists first.
 *
 * All mutating calls are suspending and touch the filesystem, so they must be
 * called from a coroutine; the reads ([frames], [frameCount], [files]) are safe
 * from anywhere, including composition. A [Mutex] serialises mutations so two
 * captures completing at once cannot lose a frame.
 */
class ImageBufferManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // The manager outlives individual screens; holding an Activity here would
    // leak it for as long as the buffer is alive.
    private val appContext: Context = context.applicationContext

    private val mutex = Mutex()

    private val _sessionId = MutableStateFlow(SphereImageStore.newSessionId())

    /** Identifier of the run currently being captured. */
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _frames = MutableStateFlow<List<BufferedFrame>>(emptyList())

    /** Everything captured so far this session, ordered by capture index. */
    val frames: StateFlow<List<BufferedFrame>> = _frames.asStateFlow()

    /** Total frames captured this session. */
    val frameCount: Int get() = _frames.value.size

    /** True while the session has nothing worth stitching. */
    val isEmpty: Boolean get() = _frames.value.isEmpty()

    /** The buffered frames as stitcher input, in capture order. */
    fun files(): List<File> = _frames.value.map(BufferedFrame::file)

    /** The frame captured at [index], if one landed. */
    fun frameAt(index: Int): BufferedFrame? = _frames.value.firstOrNull { it.index == index }

    /**
     * Reserves the file frame [index] will be written to.
     *
     * Creating the session directory is a filesystem touch, which is why this
     * suspends; the returned options go straight to `ImageCapture.takePicture`.
     */
    suspend fun reserveFrame(index: Int): SphereImageStore.TempFrameRequest =
        withContext(ioDispatcher) {
            val directory = SphereImageStore.sessionDirectory(appContext, _sessionId.value)
            SphereImageStore.newTempFrameOptions(directory, index)
        }

    /**
     * Reserves [count] candidate files for frame [index], one per burst shot.
     *
     * The canonical `frame_%03d.jpg` name is left for the winner; the
     * candidates sit alongside it as `frame_%03d_t{k}.jpg` until
     * [commitBestFrame] promotes one. Callers that only want a single frame
     * should keep using [reserveFrame] and skip the burst machinery.
     */
    suspend fun reserveBurstFrames(index: Int, count: Int): List<SphereImageStore.TempFrameRequest> {
        require(count > 0) { "burst size must be positive, was $count" }
        return withContext(ioDispatcher) {
            val directory = SphereImageStore.sessionDirectory(appContext, _sessionId.value)
            List(count) { burst ->
                val file = SphereImageStore.burstFrameFile(directory, index, burst)
                SphereImageStore.TempFrameRequest(
                    outputOptions = ImageCapture.OutputFileOptions.Builder(file).build(),
                    file = file,
                )
            }
        }
    }

    /**
     * Keeps [best] as frame [index]'s canonical file and buffers it.
     *
     * [best] must be one of the files [reserveBurstFrames] handed back (or, for
     * a single-frame session, the [reserveFrame] file). It is promoted onto the
     * canonical `frame_%03d.jpg` name — within its own directory, so a frame
     * whose session was superseded mid-burst stays put and [record]'s
     * session check still rejects it — and the rest of the burst is deleted, so
     * the session holds exactly one file per index. The stitcher reads the
     * buffer's [frames], never the directory.
     */
    suspend fun commitBestFrame(
        best: File,
        index: Int,
        orientation: OrientationData,
    ): BufferedFrame? = withContext(ioDispatcher) {
        val directory = best.parentFile
        val canonical = directory?.let { SphereImageStore.frameFile(it, index) }

        var winner = best
        if (canonical != null && best != canonical) {
            // An atomic replace: the promotion either lands whole or leaves the
            // previous attempt in place. Deleting the canonical first would open
            // a window where both copies of the index are gone if the process
            // died between the two calls.
            try {
                Files.move(best.toPath(), canonical.toPath(), StandardCopyOption.REPLACE_EXISTING)
                winner = canonical
            } catch (e: IOException) {
                Logging.w("Could not promote ${best.name} to ${canonical.name}", e)
            }
        }

        // The burst candidates that lost. Deleting a sibling here never touches
        // the winner: burst names always carry the `_t{k}` suffix, the canonical
        // name never does.
        directory?.listFiles()?.forEach { sibling ->
            if (sibling != winner &&
                sibling.name.startsWith(SphereImageStore.burstPrefix(index))
            ) {
                if (!sibling.delete()) Logging.w("Could not delete burst sibling ${sibling.name}")
            }
        }

        winner
    }.let { record(it, index, orientation) }

    /**
     * Adds a frame that is already on disk, stamping its attitude into EXIF.
     *
     * Re-recording an index replaces the earlier entry rather than duplicating
     * it, so a retried capture leaves one frame in the buffer, not two.
     *
     * Returns null, having deleted the file, when it belongs to a session that
     * has since been cancelled: a shutter in flight when the user starts a new
     * sphere would otherwise drop one frame of the old one into the new set,
     * and a single frame from the wrong place is exactly what a stitcher cannot
     * cope with.
     */
    suspend fun record(
        file: File,
        index: Int,
        orientation: OrientationData,
    ): BufferedFrame? {
        // The session check and the append share one lock so a concurrent
        // cancelSession cannot land between them: without that, a frame from the
        // cancelled session could be appended to the new session's list — a
        // buffered entry whose file has just been deleted.
        mutex.withLock {
            if (file.parentFile?.name != _sessionId.value) {
                Logging.w("Dropping ${file.name} from superseded session")
                withContext(NonCancellable + ioDispatcher) { file.delete() }
                return null
            }

            withContext(ioDispatcher) {
                SphereImageStore.stampCaptureOrientation(
                    file = file,
                    index = index,
                    yawDegrees = orientation.yawDegrees,
                    pitchDegrees = orientation.pitchDegrees,
                    rollDegrees = orientation.rollDegrees,
                )
            }

            val frame = BufferedFrame(
                index = index,
                file = file,
                yawDegrees = orientation.yawDegrees,
                pitchDegrees = orientation.pitchDegrees,
                rollDegrees = orientation.rollDegrees,
                cameraBasis = orientation.cameraBasis,
                capturedAtMillis = System.currentTimeMillis(),
            )

            _frames.value = (_frames.value.filterNot { it.index == index } + frame)
                .sortedBy(BufferedFrame::index)
            return frame
        }
    }

    /**
     * Persists [bitmap] into the session directory and buffers it.
     *
     * For callers that hold pixels rather than a file — an `ImageAnalysis` frame,
     * or a test. The bitmap is not recycled here; that stays with its owner.
     */
    suspend fun record(
        bitmap: Bitmap,
        index: Int,
        orientation: OrientationData,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): BufferedFrame? {
        val file = withContext(ioDispatcher) {
            val directory = SphereImageStore.sessionDirectory(appContext, _sessionId.value)
            val target = SphereImageStore.frameFile(directory, index)
            FileOutputStream(target).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw IOException("Could not encode frame $index")
                }
            }
            target
        }
        return record(file, index, orientation)
    }

    /**
     * Drops the most recently captured frame, deleting its file, and returns it.
     *
     * Guided capture walks the plan one index at a time, so the newest frame is
     * always the highest buffered index — this is the "undo that shot" a
     * StreetView-style capture wants: a frame that came out blurred, or one shot
     * while something moved through the scene, is removed and its target becomes
     * the active one again so it can simply be re-shot.
     *
     * Returns null when the buffer is empty.
     */
    suspend fun undoLastFrame(): BufferedFrame? {
        val dropped = mutex.withLock {
            // The most recently *shot* frame, not the highest index: targets
            // can be captured in any order, and undo takes back the last shot.
            val last = _frames.value.maxByOrNull(BufferedFrame::capturedAtMillis) ?: return null
            _frames.value = _frames.value.filterNot { it == last }
            last
        }
        withContext(NonCancellable + ioDispatcher) {
            if (dropped.file.exists() && !dropped.file.delete()) {
                Logging.w("Could not delete undone frame ${dropped.file.name}")
            }
        }
        return dropped
    }

    /**
     * Empties the buffer, deleting the frames it was holding.
     *
     * The session id survives, so capture can carry on writing into the same
     * directory — this is the "those frames have been consumed" call, used after
     * a stitch succeeds. Pass `deleteFiles = false` to keep the JPEGs on disk.
     *
     * Deletion runs [NonCancellable]: a buffer cleared as the screen goes away
     * would otherwise leave a sphere's worth of JPEGs behind precisely when
     * nothing is left to clean them up.
     */
    suspend fun clear(deleteFiles: Boolean = true) {
        val dropped = mutex.withLock {
            _frames.value.also { _frames.value = emptyList() }
        }
        if (dropped.isNotEmpty()) Logging.d("Buffer cleared (${dropped.size} frames)")
        if (!deleteFiles || dropped.isEmpty()) return

        withContext(NonCancellable + ioDispatcher) {
            dropped.forEach { frame ->
                if (frame.file.exists() && !frame.file.delete()) {
                    Logging.w("Could not delete ${frame.file.name}")
                }
            }
        }
    }

    /**
     * Abandons this session and opens a fresh, empty one.
     *
     * The whole session directory goes, not just the buffered frames, so a
     * half-written capture cannot survive into the next run. Returns the new
     * session id.
     *
     * Only the manager's own state is reset: a capture already in flight belongs
     * to the caller's coroutine and has to be cancelled there, or its frame will
     * land in the new session's directory.
     */
    suspend fun cancelSession(): String {
        val previous = _sessionId.value
        mutex.withLock { _frames.value = emptyList() }
        Logging.i("Session restarted: $previous")

        withContext(NonCancellable + ioDispatcher) {
            SphereImageStore.deleteSession(appContext, previous)
        }

        // The id has millisecond precision (see SphereImageStore.newSessionId),
        // so a restart inside the same millisecond would reuse the name. That
        // is harmless now the directory itself is gone.
        val next = SphereImageStore.newSessionId()
        _sessionId.value = next
        return next
    }

    /** Deletes the leftovers of every session but this one. */
    suspend fun pruneStaleSessions() {
        withContext(ioDispatcher) {
            SphereImageStore.pruneSessions(appContext, _sessionId.value)
        }
    }
}

/** A [ViewModel] whose only job is to hold the buffer across configuration changes. */
private class BufferHolder : ViewModel() {
    var manager: ImageBufferManager? = null
}

/**
 * An [ImageBufferManager] that survives configuration changes.
 *
 * The manager is kept in the activity's [ViewModelStore], so a recreation —
 * font scale, locale, dark mode, a foldable resize, or an Android 16 large
 * screen being force-rotated — keeps the session and its frames instead of
 * starting a fresh (empty) session whose first act would be to prune the
 * previous run's files out from under the user. The store lives exactly as
 * long as the activity, which is the manager's natural lifetime: a session
 * abandoned because the activity was finished is cleared by
 * [ImageBufferManager.pruneStaleSessions] on the next run.
 *
 * Falls back to composition scope when there is no activity (a preview).
 */
@Composable
fun rememberImageBufferManager(): ImageBufferManager {
    val context = LocalContext.current
    val store = remember { context.findActivity()?.viewModelStore }
    if (store == null) {
        return remember(context) { ImageBufferManager(context) }
    }
    return remember(store) {
        // Through the public provider rather than ViewModelStore.put, which is
        // a library-internal API.
        val holder = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = BufferHolder() as T
            },
        )[BUFFER_HOLDER_KEY, BufferHolder::class.java]
        holder.manager ?: ImageBufferManager(context).also { holder.manager = it }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
