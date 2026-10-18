package com.n30dyn4m1c.photosphere.extendedcapture

import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.n30dyn4m1c.photosphere.camera.SharpnessSelection
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Attitude of one shot, measured from where the walk started. */
data class ExtendedShotPose(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
)

/** The shot of a burst that was kept, and the attitude it was shot at. */
data class ExtendedBurstWinner(
    val file: File,
    val pose: ExtendedShotPose,
    val shots: Int,
)

/**
 * Fires [shots] stills at one target and keeps the sharpest.
 *
 * Every frame of a sphere is stitched at its measured pose, so a frame blurred
 * by handheld shake is either a soft patch in the output or a match the
 * stitcher cannot use. Shooting the target a few times and keeping the
 * sharpest is what the normal path does, and the extended walk wants the same
 * thing: the point of the run is to judge the plan against real frames, and
 * soft ones would be judged instead.
 *
 * The candidates are written as `extended_NN_tK.jpg` and never survive: the
 * winner is renamed onto the canonical `extended_NN.jpg` and the rest are
 * deleted, so the directory still holds exactly one file per target and the
 * sidecar naming does not change.
 *
 * [poseAt] is called immediately before each shot, because a burst of
 * full-resolution stills takes long enough that the phone has moved by the
 * last one: the pose that belongs to the winner is the one at its own shutter.
 *
 * Debug only: only referenced from `app/src/debug`.
 */
suspend fun captureSharpestBurst(
    capture: ImageCapture,
    directory: File,
    index: Int,
    shots: Int,
    executor: Executor,
    poseAt: () -> ExtendedShotPose,
): ExtendedBurstWinner {
    val files = ArrayList<File>(shots)
    val poses = ArrayList<ExtendedShotPose>(shots)
    repeat(shots) { shot ->
        val file = ExtendedStore.burstFrameFile(directory, index, shot)
        poses += poseAt()
        capture.takeShotTo(file, executor)
        files += file
    }

    // Scoring decodes every candidate, which is cheap next to the captures that
    // just ran but still real work; keep it off the main thread.
    val winner = withContext(Dispatchers.Default) {
        runCatching { SharpnessSelection.sharpestIndex(files) }.getOrDefault(0)
    }
    val kept = ExtendedStore.frameFile(directory, index)
    files.forEachIndexed { shot, file ->
        if (shot == winner) promote(file, kept) else file.delete()
    }
    Logging.i("Extended burst $index: kept shot $winner of $shots as ${kept.name}")
    return ExtendedBurstWinner(file = kept, pose = poses[winner], shots = shots)
}

/** Deletes every candidate of a burst that never produced a winner. */
fun discardBurst(directory: File, index: Int, shots: Int) {
    repeat(shots) { shot ->
        ExtendedStore.burstFrameFile(directory, index, shot).delete()
    }
}

/** Suspending [ImageCapture.takePicture] into [file], the callback form fits nothing here. */
private suspend fun ImageCapture.takeShotTo(file: File, executor: Executor) {
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    suspendCancellableCoroutine { continuation ->
        takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(error: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
    }
}

/**
 * Renames a burst winner onto its canonical name.
 *
 * Falls back to a copy when the rename is refused (a different mount, or a file
 * still held open), so a winner is never lost to a filesystem quirk.
 */
private fun promote(from: File, to: File) {
    to.delete()
    if (from.renameTo(to)) return
    runCatching {
        from.copyTo(to, overwrite = true)
        from.delete()
    }.onFailure { error ->
        Logging.w("Could not promote ${from.name} to ${to.name}", error)
    }
}

/**
 * Aims and locks autofocus at a normalised point in the preview, awaiting
 * convergence.
 *
 * This is tap-to-focus. After the trigger completes in `AF_MODE_AUTO` the lens
 * stays where it is until another trigger comes along, which is what keeps a
 * sphere's frames on one focal plane: a lens that hunts between frames leaves
 * each one focused at a different distance, and the stitcher cannot match
 * features that are not there.
 *
 * @return false when the preview is not laid out yet and no sweep could run.
 */
suspend fun Camera.focusAtPoint(
    nx: Float,
    ny: Float,
    previewView: PreviewView,
): Boolean {
    if (previewView.width <= 0 || previewView.height <= 0) return false
    val point = previewView.meteringPointFactory.createPoint(
        previewView.width * nx.coerceIn(0f, 1f),
        previewView.height * ny.coerceIn(0f, 1f),
    )
    val future = cameraControl.startFocusAndMetering(
        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build(),
    )
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                // A failed focus sweep is not worth failing the session over; the
                // lens just stays where auto left it.
                runCatching { future.get() }
                if (continuation.isActive) continuation.resume(Unit)
            },
            ContextCompat.getMainExecutor(previewView.context),
        )
    }
    return true
}
