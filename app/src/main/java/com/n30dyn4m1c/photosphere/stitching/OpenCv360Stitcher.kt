package com.n30dyn4m1c.photosphere.stitching

import android.content.Context
import android.util.Log
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenCV 360° Equirectangular Stitcher - JNI Wrapper
 * 
 * Debug-only wrapper for the native OpenCV stitching pipeline that implements
 * the EXACT pipeline from sphere-100-percent-cpp-kotlin.md:
 * - Uses KNOWN poses from gyroscope (sidecar poses.json) — NO feature matching for pose estimation
 * - Direct equirectangular 2:1 rendering via cv::remap with known poses
 * - Gain compensation (exposure equalization) on equirect grid
 * - Feather blending via distance transform on valid masks
 * - Optional SIFT pose refinement on overlapping edges
 * - Coverage validation per 10° latitude band
 * 
 * Coordinate convention (from sphere-100-percent-cpp-kotlin.md §1):
 *   World: X right, Y down, Z forward
 *   yaw   : rotation around Y, positive = right
 *   pitch : rotation around X, positive = UP (camera tilted up)
 *   roll  : rotation around Z, positive = clockwise from behind
 *   R = Ry(yaw) * Rx(pitch) * Rz(roll)  -- camera -> world
 * 
 * This is a DEBUG-ONLY module. It loads the native library
 * 'opencv_360_stitcher' which must be built via CMake.
 * 
 * Usage:
 * ```kotlin
 * val stitcher = OpenCv360Stitcher()
 * val result = stitcher.stitch360(
 *     jsonPath = "/path/poses.json",  // sidecar with poses from Kotlin
 *     outputPath = "/path/output_pano.jpg",
 *     outWidth = 1024,
 *     ssaa = 2,
 *     hfovDeg = 65.0f,
 *     doGain = true,
 *     doRefine = true,
 *     refineIters = 4
 * )
 * ```
 * 
 * Note: This requires the native library to be built via CMake.
 * The library is only available in debug builds.
 */
class OpenCv360Stitcher private constructor() {
    
    companion object {
        private const val TAG = "OpenCv360Stitcher"
        private var sLibraryLoaded = false
        private val sLoadLock = Any()
        
        /**
         * Creates an instance of the 360° stitcher.
         * Loads the native library on first use.
         */
        fun create(): OpenCv360Stitcher? {
            synchronized(sLoadLock) {
                if (!sLibraryLoaded) {
                    try {
                        System.loadLibrary("opencv_360_stitcher")
                        sLibraryLoaded = true
                        Logging.i("Loaded native library: opencv_360_stitcher")
                    } catch (e: UnsatisfiedLinkError) {
                        Logging.e("Failed to load native library", e)
                        return null
                    }
                }
                return OpenCv360Stitcher()
            }
        }
        
        /** Checks if the native library is available */
        fun isAvailable(): Boolean {
            return sLibraryLoaded || try {
                System.loadLibrary("opencv_360_stitcher")
                sLibraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }
    }
    
    /**
     * Stitches a 360° equirectangular panorama from images with known poses.
     * 
     * The poses MUST be provided in a sidecar JSON file (poses.json) written by Kotlin
     * with the exact format expected by the C++ implementation:
     * ```json
     * {
     *   "hfov_deg": 65.0,
     *   "shots": [
     *     {"file": "/abs/path/img0.jpg", "index": 0, "yaw_deg": 0.0, "pitch_deg": 0.0, "roll_deg": 0.0},
     *     ...
     *   ]
     * }
     * ```
     * 
     * Coordinate convention (from sphere-100-percent-cpp-kotlin.md §1):
     * - World: X right, Y down, Z forward
     * - yaw: rotation around Y, positive = right
     * - pitch: rotation around X, positive = UP (camera tilted up)
     * - roll: rotation around Z, positive = clockwise from behind
     * - R = Ry(yaw) * Rx(pitch) * Rz(roll)  -- camera -> world
     * 
     * @param jsonPath Absolute path to poses.json sidecar file
     * @param outputPath Absolute path for output equirectangular panorama (2:1 JPEG)
     * @param outWidth Output width in pixels (height = width/2)
     * @param ssaa Super-sampling anti-aliasing factor (2 = 2x, recommended for poles)
     * @param hfovDeg Horizontal FOV in degrees (must match capture)
     * @param doGain Apply gain compensation (exposure equalization) on equirect grid
     * @param doRefine Run optional SIFT pose refinement on overlapping edges
     * @param refineIters Number of SIFT refinement iterations
     * @return Result with success status, output path, dimensions, coverage stats
     */
    suspend fun stitch360(
        jsonPath: String,
        outputPath: String,
        outWidth: Int = 1024,
        ssaa: Int = 2,
        hfovDeg: Float = 65.0f,
        doGain: Boolean = true,
        doRefine: Boolean = true,
        refineIters: Int = 4
    ): Stitch360Result = withContext(Dispatchers.IO) {
        val result = nativeStitch360(
            jsonPath = jsonPath,
            outputPath = outputPath,
            outWidth = outWidth,
            ssaa = ssaa,
            hfovDeg = hfovDeg,
            doGain = doGain,
            doRefine = doRefine,
            refineIters = refineIters
        )
        
        Stitch360Result(
            success = result.success,
            outputPath = if (result.success) outputPath else "",
            width = result.width,
            height = result.height,
            coverage = result.coverage,
            bandCoverage = result.bandCoverage,
            errorMessage = if (!result.success) nativeGetError() else null
        )
    }
    
    /**
     * Convenience method with auto settings.
     */
    suspend fun stitch360Auto(
        jsonPath: String,
        outputPath: String
    ): Stitch360Result = stitch360(
        jsonPath = jsonPath,
        outputPath = outputPath,
        outWidth = 1024,
        ssaa = 2,
        hfovDeg = 65.0f,
        doGain = true,
        doRefine = true,
        refineIters = 4
    )
    
    // Native methods (match the C++ JNI signatures exactly)
    private external fun nativeStitch360(
        jsonPath: String,
        outputPath: String,
        outWidth: Int,
        ssaa: Int,
        hfovDeg: Float,
        doGain: Boolean,
        doRefine: Boolean,
        refineIters: Int
    ): NativeStitchResult
    
    private external fun nativeGetError(): String
    
    private companion object {
        // Library loading handled by public companion object
    }
}

/**
 * Result of 360° stitching operation
 */
data class Stitch360Result(
    val success: Boolean,
    val outputPath: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val coverage: Double = 0.0,
    val bandCoverage: List<Double> = emptyList(),
    val errorMessage: String? = null
) {
    override fun toString(): String {
        return if (success) {
            "Stitch360Result(success=true, output=$outputPath, ${width}x$height, coverage=${String.format("%.2f", coverage * 100)}%)"
        } else {
            "Stitch360Result(success=false, error=$errorMessage)"
        }
    }
}

/**
 * Internal native result structure
 */
private data class NativeStitchResult(
    val success: Boolean,
    val width: Int,
    val height: Int,
    val coverage: Double,
    val bandCoverage: List<Double>
)