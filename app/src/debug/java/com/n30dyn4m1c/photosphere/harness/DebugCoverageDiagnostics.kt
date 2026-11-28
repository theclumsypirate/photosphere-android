package com.n30dyn4m1c.photosphere.harness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.n30dyn4m1c.photosphere.stitching.CameraBasis
import com.n30dyn4m1c.photosphere.stitching.Equirectangular
import com.n30dyn4m1c.photosphere.stitching.FrameIntrinsics
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.RadialDistortion
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.projectDirection
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Debug-only coverage diagnostic.
 *
 * Rasterises what the captured frames actually reach on the sphere, using the
 * same camera model as the stitcher. A black patch in the final panorama that
 * is also black here is a capture or projection gap. A black patch that is
 * white here is a blending or exposure problem. The mask is written next to
 * the harness results so it can be opened directly.
 */
internal object DebugCoverageDiagnostics {

    data class Report(
        val coverage: Float,
        val maskFile: File,
        val summary: String,
    )

    fun analyze(
        context: Context,
        frames: List<SphereFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
        distortion: RadialDistortion?,
        width: Int = 720,
        height: Int = 360,
    ): Report {
        val cameras = frames.mapNotNull { frame ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(frame.file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@mapNotNull null
            val corrected = PhotoSphereStitcher.correctFovOrientation(
                widthPx = options.outWidth,
                heightPx = options.outHeight,
                horizontalFovDegrees = horizontalFovDegrees,
                verticalFovDegrees = verticalFovDegrees,
            ) ?: (horizontalFovDegrees to verticalFovDegrees)
            CameraSetup(
                basis = CameraBasis.of(frame.pose),
                intrinsics = FrameIntrinsics.forLens(
                    widthPx = options.outWidth,
                    heightPx = options.outHeight,
                    horizontalFovDegrees = corrected.first,
                    verticalFovDegrees = corrected.second,
                    distortion = distortion,
                ),
            )
        }

        val covered = BooleanArray(width * height)
        var coveredPixels = 0
        for (row in 0 until height) {
            val latitude = Equirectangular.latitudeDegrees(row, height)
            for (col in 0 until width) {
                val longitude = Equirectangular.longitudeDegrees(col, width)
                val direction = Equirectangular.direction(longitude, latitude)
                for (camera in cameras) {
                    if (projectDirection(
                            basis = camera.basis,
                            intrinsics = camera.intrinsics,
                            x = direction[0],
                            y = direction[1],
                            z = direction[2],
                        ) != null
                    ) {
                        covered[row * width + col] = true
                        coveredPixels++
                        break
                    }
                }
            }
        }

        val fraction = coveredPixels.toFloat() / (width * height).coerceAtLeast(1)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (index in covered.indices) {
            pixels[index] = if (covered[index]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        mask.setPixels(pixels, 0, width, 0, 0, width, height)

        val outDir = File(context.cacheDir, "harness_results").apply { mkdirs() }
        val maskFile = File(outDir, "coverage_mask.jpg")
        runCatching {
            FileOutputStream(maskFile).use { mask.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }.onFailure { Logging.w("Could not write coverage mask", it) }
        mask.recycle()

        val weakBands = mutableListOf<String>()
        val bandDegrees = 10
        for (band in 0 until 180 / bandDegrees) {
            val topLatitude = 90 - band * bandDegrees
            val bottomLatitude = topLatitude - bandDegrees
            val rowStart = (Equirectangular.rowFor(topLatitude.toDouble(), height) + 0.5)
                .toInt().coerceIn(0, height - 1)
            val rowEnd = (Equirectangular.rowFor(bottomLatitude.toDouble(), height) + 0.5)
                .toInt().coerceIn(rowStart, height - 1)
            var hits = 0
            for (row in rowStart..rowEnd) {
                for (col in 0 until width) if (covered[row * width + col]) hits++
            }
            val bandFraction = hits.toFloat() / ((rowEnd - rowStart + 1) * width).coerceAtLeast(1)
            if (bandFraction < 0.8f) {
                weakBands += "${bottomLatitude}..${topLatitude}deg:${(bandFraction * 100).roundToInt()}%"
            }
        }
        val summary = if (weakBands.isEmpty()) {
            "Coverage ${(fraction * 100).roundToInt()}%. No latitude band below 80%. " +
                "Mask: ${maskFile.name}"
        } else {
            "Coverage ${(fraction * 100).roundToInt()}%. Weak bands ${weakBands.joinToString(" ")}. " +
                "Mask: ${maskFile.name}"
        }
        Logging.i("Debug coverage: $summary")
        return Report(fraction, maskFile, summary)
    }

    private data class CameraSetup(
        val basis: CameraBasis,
        val intrinsics: FrameIntrinsics,
    )
}
