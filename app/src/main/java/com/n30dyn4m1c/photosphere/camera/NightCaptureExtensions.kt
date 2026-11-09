package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.storage.ImageBufferManager
import com.n30dyn4m1c.photosphere.storage.BufferedFrame
import com.n30dyn4m1c.photosphere.stitching.NightFrameMerger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "NightCapture"

/**
 * Night mode burst capture extension for ImageCapture.
 *
 * Captures N frames at the same pose, merges them on-device via NightFrameMerger,
 * and commits the single merged frame to the buffer.
 */
suspend fun ImageCapture.saveNightBurst(
    context: Context,
    buffer: ImageBufferManager,
    index: Int,
    dwellOrientation: OrientationData,
    currentOrientation: () -> OrientationData,
    nightConfig: NightFrameMerger.Config,
): Result<BufferedFrame> = withContext(Dispatchers.Main) {
    // 1. Reserve burst candidate files
    val burstRequests = buffer.reserveBurstFrames(index, nightConfig.framesPerTarget)
    val captureErrors = mutableListOf<String>()

    val futures = mutableListOf<CompletableDeferred<Result<Unit>>>()

    // 2. Fire all burst captures rapidly
    for (request in burstRequests) {
        val deferred = CompletableDeferred<Result<Unit>>()
        futures.add(deferred)

        takePicture(
            request.outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    deferred.complete(Result.success(Unit))
                }
                override fun onError(exception: ImageCaptureException) {
                    captureErrors.add("Frame ${burstRequests.indexOf(request)}: ${exception.message}")
                    deferred.complete(Result.failure(exception))
                }
            }
        )
    }

    // 3. Wait for all captures
    val results = futures.map { it.await() }
    val successful = results.count { it.isSuccess }

    if (successful < nightConfig.minFramesForMerge) {
        Log.w(TAG, "Night burst: only $successful/${nightConfig.framesPerTarget} frames captured")
        burstRequests.forEach { it.file.delete() }
        return@withContext Result.failure(
            RuntimeException(
                "Insufficient frames: $successful/${nightConfig.framesPerTarget}. Errors: ${captureErrors.joinToString()}"
            )
        )
    }

    // 4. Merge frames on background thread
    val mergedFile = withContext(Dispatchers.IO) {
        NightFrameMerger.mergeBurst(
            files = burstRequests.map { it.file },
            config = nightConfig,
        )
    }

    if (mergedFile == null) {
        burstRequests.forEach { it.file.delete() }
        return@withContext Result.failure(RuntimeException("NightFrameMerger returned null"))
    }

    // 5. Commit merged frame as canonical frame for this index
    val committedFrame = buffer.commitBestFrame(mergedFile, index, dwellOrientation)
    if (committedFrame != null) {
        Result.success(committedFrame)
    } else {
        Result.failure(RuntimeException("Commit failed after merge"))
    }
}
