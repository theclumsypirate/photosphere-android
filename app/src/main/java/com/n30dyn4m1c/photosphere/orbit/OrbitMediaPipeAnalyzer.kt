package com.n30dyn4m1c.photosphere.orbit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import com.n30dyn4m1c.photosphere.util.Logging
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.ByteArrayOutputStream

/**
 * CameraX analyzer that runs a MediaPipe pose landmarker in live stream mode.
 *
 * Debug lab only. Not bound to any screen yet and inert until wired.
 * Caller sets backpressure to STRATEGY_KEEP_ONLY_LATEST.
 *
 * @param context used to load the model asset.
 * @param onReading called with a reading or null when no pose is found.
 */
class OrbitMediaPipeAnalyzer(
    context: Context,
    private val onReading: (OrbitSubjectReading?) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    companion object {
        /** Asset path of the pose landmarker bundle. */
        const val MODEL_ASSET_PATH = "pose_landmarker_lite.task"

        /** Tag used for error logging. */

        /** Minimum normalized landmark visibility treated as present. */
        private const val VISIBILITY_THRESHOLD = 0.5f

        /** Normalized landmark index of the nose. */
        private const val INDEX_NOSE = 0

        /** Normalized landmark index of the left ear. */
        private const val INDEX_LEFT_EAR = 7

        /** Normalized landmark index of the right ear. */
        private const val INDEX_RIGHT_EAR = 8

        /** Normalized landmark index of the left ankle. */
        private const val INDEX_LEFT_ANKLE = 27

        /** Normalized landmark index of the right ankle. */
        private const val INDEX_RIGHT_ANKLE = 28
    }

    /** Asset manager holder to avoid holding the Activity context. */
    private val appContext: Context = context.applicationContext

    /**
     * True when the landmarker was created and can accept frames.
     * False when the model asset is missing, so analyze() is a no-op.
     */
    var ready: Boolean = false
        private set

    /** Backing pose landmarker, null when init failed. */
    private var landmarker: PoseLandmarker? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(VISIBILITY_THRESHOLD)
                .setMinPosePresenceConfidence(VISIBILITY_THRESHOLD)
                .setMinTrackingConfidence(VISIBILITY_THRESHOLD)
                .setResultListener { result, _ ->
                    try {
                        onReading(mapResult(result))
                    } catch (_: Exception) {
                        onReading(null)
                    }
                }
                .setErrorListener { error ->
                    Logging.e("Orbit MediaPipe Pose landmarker error: " + error.message)
                    try {
                        onReading(null)
                    } catch (_: Exception) {
                        // Ignore callback failures.
                    }
                }
                .build()
            landmarker = PoseLandmarker.createFromOptions(appContext, options)
            ready = true
            Logging.i("Orbit MediaPipe ready: " + MODEL_ASSET_PATH)
        } catch (e: Exception) {
            Logging.e("Orbit MediaPipe Pose landmarker init failed, analyzer inert: " + e.message)
            landmarker = null
            ready = false
        }
    }

    /**
     * Runs pose detection on one camera frame.
     *
     * Always closes [imageProxy] in all paths.
     *
     * @param imageProxy frame to analyze.
     */
    override fun analyze(imageProxy: ImageProxy) {
        val active = landmarker
        if (!ready || active == null) {
            imageProxy.close()
            return
        }
        val bitmap: Bitmap? = try {
            imageProxyToBitmap(imageProxy, imageProxy.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            Logging.e("Orbit MediaPipe Frame convert failed: " + e.message)
            null
        }
        if (bitmap == null) {
            imageProxy.close()
            return
        }
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            active.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Logging.e("Orbit MediaPipe detectAsync failed: " + e.message)
            try {
                onReading(null)
            } catch (_: Exception) {
                // Ignore callback failures.
            }
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Releases the landmarker.
     */
    override fun close() {
        try {
            landmarker?.close()
        } catch (e: Exception) {
            Logging.e("Orbit MediaPipe Landmarker close failed: " + e.message)
        } finally {
            landmarker = null
            ready = false
        }
    }

    /**
     * Maps one landmarker result to a subject reading.
     *
     * Uses normalized landmarks for center and height fractions.
     * World landmarks are unused for now.
     *
     * @param result landmarker result.
     * @return reading or null when no pose is present.
     */
    private fun mapResult(
        result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
    ): OrbitSubjectReading? {
        val landmarks = result.landmarks().firstOrNull() ?: return null
        if (landmarks.isEmpty()) return null
        var sumX = 0f
        var sumY = 0f
        for (point in landmarks) {
            sumX += point.x()
            sumY += point.y()
        }
        val count = landmarks.size.toFloat()
        val cx = (sumX / count).coerceIn(0f, 1f)
        val cy = (sumY / count).coerceIn(0f, 1f)
        val nose = landmarks.getOrNull(INDEX_NOSE)
        val leftAnkle = landmarks.getOrNull(INDEX_LEFT_ANKLE)
        val rightAnkle = landmarks.getOrNull(INDEX_RIGHT_ANKLE)
        val bodyHeightFrac = if (nose != null && (leftAnkle != null || rightAnkle != null)) {
            var ankleSum = 0f
            var ankleCount = 0
            if (leftAnkle != null) {
                ankleSum += leftAnkle.y()
                ankleCount += 1
            }
            if (rightAnkle != null) {
                ankleSum += rightAnkle.y()
                ankleCount += 1
            }
            val ankleY = ankleSum / ankleCount.toFloat()
            (ankleY - nose.y()).takeIf { it >= 0f }
        } else {
            null
        }
        return OrbitSubjectReading(
            bodyCenterXFrac = cx,
            bodyCenterYFrac = cy,
            bodyHeightFrac = bodyHeightFrac,
            phase = classifyPhase(landmarks),
        )
    }

    /**
     * Classifies facing phase from nose and ear visibility.
     *
     * Mirrors the ML Kit analyzer logic.
     *
     * @param landmarks normalized landmarks of one pose.
     * @return facing phase.
     */
    private fun classifyPhase(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): OrbitPhase {
        val nose = landmarks.getOrNull(INDEX_NOSE)
        val leftEar = landmarks.getOrNull(INDEX_LEFT_EAR)
        val rightEar = landmarks.getOrNull(INDEX_RIGHT_EAR)
        if (nose == null && leftEar == null && rightEar == null) return OrbitPhase.UNKNOWN
        val noseLikely = (nose?.visibility()?.orElse(0f) ?: 0f) > VISIBILITY_THRESHOLD
        val leftLikely = (leftEar?.visibility()?.orElse(0f) ?: 0f) > VISIBILITY_THRESHOLD
        val rightLikely = (rightEar?.visibility()?.orElse(0f) ?: 0f) > VISIBILITY_THRESHOLD
        return when {
            noseLikely && leftLikely && rightLikely -> OrbitPhase.FRONT
            leftLikely xor rightLikely -> OrbitPhase.PROFILE
            !noseLikely && !leftLikely && !rightLikely -> OrbitPhase.BACK
            else -> OrbitPhase.UNKNOWN
        }
    }

    /**
     * Converts YUV ImageProxy to a rotated bitmap.
     *
     * @param imageProxy source frame.
     * @param rotationDegrees rotation to apply.
     * @return bitmap or null on failure.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy, rotationDegrees: Int): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)) {
            return null
        }
        val bytes = out.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (rotationDegrees == 0) return raw
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }
}
