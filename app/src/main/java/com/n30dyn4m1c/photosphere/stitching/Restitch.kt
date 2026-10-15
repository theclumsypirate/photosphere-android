package com.n30dyn4m1c.photosphere.stitching

import android.content.Context
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.camera.FieldOfView
import com.n30dyn4m1c.photosphere.camera.SphereOptics
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.storage.BufferedFrame
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The stitch styles the result screen offers for a second try.
 *
 * Friendly names over the same [SeamMode] pipeline the capture screen
 * already uses: Balanced is the default wide blend, Crisp paints every
 * pixel from one frame (sharp detail, no ghosts) and Careful cuts seams
 * around objects with a graph cut (slower, better placement).
 */
enum class StitchStyle(val seamMode: SeamMode) {
    Balanced(SeamMode.Blend),
    Crisp(SeamMode.WinnerTakeAll),
    Careful(SeamMode.Carve),
    ;

    companion object {
        fun forSeam(mode: SeamMode): StitchStyle =
            entries.first { it.seamMode == mode }
    }
}

/**
 * Everything a re-stitch needs, snapshotted when the first stitch ran.
 *
 * Plain data so it outlives the capture screen: the frames come from the
 * buffer at tap time, everything else is frozen here.
 */
data class RestitchParams(
    val fieldOfView: FieldOfView,
    val optics: SphereOptics,
    val distortionEnabled: Boolean,
    val maxInputDimension: Int,
    val maxOutputWidth: Int,
    val unsharpAmount: Float,
    val pivot: PivotModel,
    val useRefinement: Boolean,
    val debugColors: Boolean,
    val latitudeSpanDegrees: Float,
    val centerLongitudeDegrees: Float,
)

/**
 * A finished sphere plus what it takes to stitch it again differently.
 *
 * [frames] is read at tap time so it always reflects the buffer as it is;
 * by the time the result screen is left the buffer is cleared and the
 * offer dies with it.
 */
data class RestitchOffer(
    val madeWith: StitchStyle,
    val params: RestitchParams,
    val frames: () -> List<SphereFrame>,
)

/** The stitched bitmap was fine but it would not fit on disk. */
class SphereWriteFailed(cause: Throwable) : Exception(cause)

/** A buffered capture frame as stitcher input, with its capture attitude. */
fun BufferedFrame.toSphereFrame(): SphereFrame =
    SphereFrame(
        file = file,
        pose = CameraPose(
            yawDegrees = yawDegrees,
            pitchDegrees = pitchDegrees,
            rollDegrees = rollDegrees,
            matrix = cameraBasis?.let { basis ->
                DoubleArray(basis.size) { basis[it].toDouble() }
            },
        ),
    )

/**
 * Stitches [frames] with [style] and stores the finished sphere in the
 * cache, exactly as the capture screen does on Finish.
 *
 * Throws the stitch failure as-is and wraps a disk failure in
 * [SphereWriteFailed] so the caller can tell "bad photos" from "no space".
 * The returned sphere replaces the previous one: [SphereImageStore]
 * clears the older file once the new one is complete.
 */
suspend fun restitchSphere(
    context: Context,
    frames: List<SphereFrame>,
    params: RestitchParams,
    style: StitchStyle,
    onProgress: (StitchProgress) -> Unit = {},
): StitchedSphere {
    Logging.i("Restitch started with ${frames.size} frames ($style)")
    val bitmap = PhotoSphereStitcher.stitchPhotos(
        frames = frames,
        horizontalFovDegrees = params.fieldOfView.horizontalDegrees,
        verticalFovDegrees = params.fieldOfView.verticalDegrees,
        radialDistortion = if (params.distortionEnabled) {
            params.optics.radialDistortion
        } else {
            null
        },
        maxInputDimension = params.maxInputDimension,
        maxOutputWidth = params.maxOutputWidth,
        unsharpAmount = params.unsharpAmount,
        pivot = params.pivot,
        portraitRotationDegrees = params.optics.portraitRotationDegrees,
        // Careful restitches from content-matched poses: the refinement
        // falls back to the sensor poses when nothing matches, so the only
        // change is better alignment where the features agree. The standard
        // capture path and the other styles keep sensor poses.
        useRefinement = params.useRefinement || style == StitchStyle.Careful,
        seamMode = style.seamMode,
        debugColorFrames = params.debugColors,
        longitudeSpanDegrees = 360f,
        latitudeSpanDegrees = params.latitudeSpanDegrees,
        centerLatitudeDegrees = 0f,
        centerLongitudeDegrees = params.centerLongitudeDegrees,
        onProgress = onProgress,
    ).getOrThrow()
    try {
        return withContext(Dispatchers.IO) {
            SphereImageStore.writeStitchedSphere(
                context = context,
                bitmap = bitmap,
                gpano = GPanoMetadata.forSphereRegion(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    longitudeSpanDegrees = 360f,
                    centerLongitudeDegrees = 0f,
                    latitudeSpanDegrees = params.latitudeSpanDegrees,
                    centerLatitudeDegrees = 0f,
                ),
            ).copy(
                diagnostics = stitchDiagnostics(
                    frames = frames,
                    fieldOfView = params.fieldOfView,
                    optics = params.optics,
                    distortionApplied = params.distortionEnabled,
                    refinementApplied = params.useRefinement || style == StitchStyle.Careful,
                    seamMode = style.seamMode,
                    debugColors = params.debugColors,
                ),
                debugColors = params.debugColors,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logging.e("Could not write the restitched sphere", e)
        throw SphereWriteFailed(e)
    } finally {
        bitmap.recycle()
    }
}

/**
 * Debug-only summary of what a stitch was built from, for the result screen.
 *
 * The pose column is what decides whether frames land where they were shot:
 * yaw should sweep across a capture, pitch should follow the rings, and roll
 * should stay small when the phone is held level. When a sphere comes out
 * scrambled this is the first place to look.
 *
 * The EXIF column is the other half of the puzzle: the stitch decodes each
 * JPEG upright via its `ORIENTATION` tag, and a frame that lost that tag
 * decodes landscape and lands rotated 90° against its pose.
 */
internal fun stitchDiagnostics(
    frames: List<SphereFrame>,
    fieldOfView: FieldOfView,
    optics: SphereOptics,
    distortionApplied: Boolean,
    refinementApplied: Boolean,
    seamMode: SeamMode,
    debugColors: Boolean = false,
): String {
    val poses = frames.mapIndexed { index, frame ->
        val exif = runCatching {
            ExifInterface(frame.file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        "  #$index yaw ${frame.pose.yawDegrees.roundToInt()}° " +
            "pitch ${frame.pose.pitchDegrees.roundToInt()}° roll ${frame.pose.rollDegrees.roundToInt()}°" +
            " exif=$exif"
    }.joinToString(separator = "\n")
    val device = listOfNotNull(
        Build.MANUFACTURER,
        Build.MODEL,
    ).joinToString(" ")
    val distortion = optics.radialDistortion
        ?.coefficients
        ?.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
        ?: "none"
    return "Device: $device\n" +
        "FOV: ${fieldOfView.horizontalDegrees.roundToInt()}° x " +
        "${fieldOfView.verticalDegrees.roundToInt()}° (screen) " +
        "rotation=${optics.portraitRotationDegrees}°\n" +
        "Distortion k: $distortion ${if (distortionApplied) "applied" else "OFF (pinhole)"}\n" +
        "Refinement: ${if (refinementApplied) "on" else "off (sensor poses)"}\n" +
        "Seams: ${when (seamMode) {
            SeamMode.Blend -> "off (blend)"
            SeamMode.Carve -> "on (carved)"
            SeamMode.WinnerTakeAll -> "on (winner)"
        }}\n" +
        (if (debugColors) "Colors: SOLID DEBUG FRAMES (placement check, not a photo)\n" else "") +
        "Frames (capture order):\n$poses"}
