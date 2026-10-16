package com.n30dyn4m1c.photosphere.orbit

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions

/**
 * Phase of the subject relative to the camera.
 */
enum class OrbitPhase {
    FRONT,
    PROFILE,
    BACK,
    UNKNOWN,
}

/**
 * One subject reading as fractions of the frame.
 *
 * @param bodyCenterXFrac center X in 0..1 of frame width.
 * @param bodyCenterYFrac center Y in 0..1 of frame height.
 * @param bodyHeightFrac nose to ankle midpoint height as fraction of frame height, or null.
 * @param phase facing phase derived from landmark likelihoods.
 */
data class OrbitSubjectReading(
    val bodyCenterXFrac: Float,
    val bodyCenterYFrac: Float,
    val bodyHeightFrac: Float?,
    val phase: OrbitPhase,
)

/**
 * CameraX analyzer that runs ML Kit pose detection in streaming mode.
 *
 * Not bound to any screen yet. A DEBUG-only entry will wire it later.
 * Caller sets backpressure to STRATEGY_KEEP_ONLY_LATEST.
 *
 * @param onReading called with a reading or null when no pose is found.
 */
class OrbitMlKitAnalyzer(
    private val onReading: (OrbitSubjectReading?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = try {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        } catch (_: Exception) {
            imageProxy.close()
            return
        }
        detector.process(input)
            .addOnSuccessListener { pose ->
                try {
                    onReading(mapPose(pose, imageProxy.width, imageProxy.height))
                } catch (_: Exception) {
                    onReading(null)
                } finally {
                    imageProxy.close()
                }
            }
            .addOnFailureListener {
                try {
                    onReading(null)
                } finally {
                    imageProxy.close()
                }
            }
    }

    private fun mapPose(
        pose: com.google.mlkit.vision.pose.Pose,
        width: Int,
        height: Int,
    ): OrbitSubjectReading? {
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) return null
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()

        val xs = landmarks.map { it.position.x / w }
        val ys = landmarks.map { it.position.y / h }
        val cx = xs.average().toFloat().coerceIn(0f, 1f)
        val cy = ys.average().toFloat().coerceIn(0f, 1f)

        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        val bodyHeightFrac = if (nose != null && (leftAnkle != null || rightAnkle != null)) {
            val ankleY = listOfNotNull(leftAnkle, rightAnkle)
                .map { it.position.y }.average().toFloat()
            ((ankleY - nose.position.y) / h).takeIf { it >= 0f }
        } else {
            null
        }

        return OrbitSubjectReading(
            bodyCenterXFrac = cx,
            bodyCenterYFrac = cy,
            bodyHeightFrac = bodyHeightFrac,
            phase = classifyPhase(pose),
        )
    }

    private fun classifyPhase(pose: com.google.mlkit.vision.pose.Pose): OrbitPhase {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
        if (nose == null && leftEar == null && rightEar == null) return OrbitPhase.UNKNOWN
        val noseLikely = (nose?.inFrameLikelihood ?: 0f) > 0.5f
        val leftLikely = (leftEar?.inFrameLikelihood ?: 0f) > 0.5f
        val rightLikely = (rightEar?.inFrameLikelihood ?: 0f) > 0.5f
        return when {
            noseLikely && leftLikely && rightLikely -> OrbitPhase.FRONT
            leftLikely xor rightLikely -> OrbitPhase.PROFILE
            !noseLikely && !leftLikely && !rightLikely -> OrbitPhase.BACK
            else -> OrbitPhase.UNKNOWN
        }
    }
}
