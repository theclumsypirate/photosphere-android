package com.n30dyn4m1c.photosphere.orbit

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stitches an orbit walk into one photo with the same pipeline as the
 * main camera: stamped EXIF poses when the walk has them, otherwise an
 * even horizon ring in file order.
 *
 * Honest limitation, stated once: the stitcher models rotation from one
 * spot, while a walk translates around the subject. Close subjects show
 * parallax seams no blend can hide. The result is still one continuous
 * turntable photo, like the reference sets.
 */
object OrbitStitch {

    suspend fun stitch(
        context: Context,
        files: List<File>,
        onProgress: (StitchProgress) -> Unit = {},
    ): StitchedSphere {
        Logging.i("Orbit stitch started with ${files.size} photos")
        val stamped = files.map { readStampedPose(it) }
        val poses = if (stamped.all { it != null }) {
            Logging.i("Orbit poses: stamped EXIF")
            stamped.requireNoNulls()
        } else {
            Logging.i("Orbit poses: even ring")
            fallbackRingPoses(files.size, HFOV_DEGREES)
        }
        val frames = files.mapIndexed { index, file ->
            SphereFrame(file = file, pose = poses[index])
        }
        val bitmap = PhotoSphereStitcher.stitchPhotos(
            frames = frames,
            horizontalFovDegrees = HFOV_DEGREES,
            verticalFovDegrees = VFOV_DEGREES,
            onProgress = onProgress,
        ).getOrThrow()
        try {
            return withContext(Dispatchers.IO) {
                SphereImageStore.writeStitchedSphere(
                    context = context,
                    bitmap = bitmap,
                    gpano = GPanoMetadata.forFullPano(bitmap.width, bitmap.height),
                ).copy(
                    diagnostics = "Orbit: ${files.size} photos\n" +
                        "Poses: ${if (stamped.all { it != null }) "stamped EXIF" else "even ring"}\n",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logging.e("Could not write the orbit sphere", e)
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    private fun readStampedPose(file: File): CameraPose? {
        val comment = runCatching {
            ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT)
        }.getOrNull() ?: return null
        val match = ATTITUDE_RE.find(comment) ?: return null
        val (yaw, pitch, roll) = match.destructured
        return runCatching {
            CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
        }.getOrNull()
    }

    private fun fallbackRingPoses(count: Int, hfov: Float): List<CameraPose> {
        val step = hfov * 0.65f
        return List(count) { index ->
            CameraPose(
                yawDegrees = (index - (count - 1) / 2f) * step,
                pitchDegrees = 0f,
                rollDegrees = 0f,
            )
        }
    }

    private val ATTITUDE_RE =
        Regex("""yaw=(-?\d+(?:\.\d+)?);pitch=(-?\d+(?:\.\d+)?);roll=(-?\d+(?:\.\d+)?)""")

    private const val HFOV_DEGREES = 62f
    private const val VFOV_DEGREES = 48f
}
