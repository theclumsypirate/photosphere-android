package com.n30dyn4m1c.photosphere.stitching

import android.content.Context
import android.util.Log
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenCV 360° Equirectangular Stitcher - JNI Wrapper
 * 
 * Debug-only wrapper for the native OpenCV stitching pipeline.
 * Provides high-quality 360° equirectangular panorama stitching
 * using OpenCV's full stitching pipeline (feature detection, matching,
 * camera estimation, bundle adjustment, warping, blending).
 * 
 * This is a DEBUG-ONLY module. It loads the native library
 * 'opencv_360_stitcher' which must be built via CMake.
 * 
 * Usage:
 * ```kotlin
 * val stitcher = OpenCv360Stitcher()
 * val result = stitcher.stitch360(
 *     imagePaths = listOf("/path/img1.jpg", "/path/img2.jpg", ...),
 *     outputPath = "/path/output_pano.jpg",
 *     workMegapix = 0.6,
 *     seamMegapix = 0.1,
 *     composeMegapix = -1.0,  // -1 = original resolution
 *     matchConf = 0.3f,
 *     blendStrength = 5.0f,
 *     tryCuda = false
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
     * Stitches a 360° equirectangular panorama from multiple images.
     * 
     * @param imagePaths List of absolute paths to input images (JPEG/PNG)
     * @param outputPath Absolute path for output equirectangular panorama
     * @param workMegapix Resolution for feature detection (default: 0.6 Mpx)
     * @param seamMegapix Resolution for seam estimation (default: 0.1 Mpx)
     * @param composeMegapix Resolution for final composition, -1 for original (default: -1)
     * @param matchConf Feature matching confidence threshold (default: 0.3 for ORB)
     * @param blendStrength Blending strength 0-100 (default: 5)
     * @param tryCuda Attempt to use CUDA if available (default: false)
     * @return Result with success status, output path, dimensions, and error message
     */
    suspend fun stitch360(
        imagePaths: List<String>,
        outputPath: String,
        workMegapix: Double = 0.6,
        seamMegapix: Double = 0.1,
        composeMegapix: Double = -1.0,
        matchConf: Float = 0.3f,
        blendStrength: Float = 5.0f,
        tryCuda: Boolean = false
    ): Stitch360Result = withContext(Dispatchers.IO) {
        if (imagePaths.size < 2) {
            Stitch360Result(
                success = false,
                errorMessage = "Need at least 2 images for 360° stitching"
            )
        } else {
            val result = nativeStitch360(
                imagePaths = imagePaths.toTypedArray(),
                outputPath = outputPath,
                workMegapix = workMegapix.toFloat(),
                seamMegapix = seamMegapix.toFloat(),
                composeMegapix = composeMegapix.toFloat(),
                matchConf = matchConf,
                blendStrength = blendStrength,
                tryCuda = tryCuda
            )
            
            Stitch360Result(
                success = result.success,
                outputPath = if (result.success) outputPath else "",
                width = result.width,
                height = result.height,
                errorMessage = if (!result.success) nativeGetError() else null
            )
        }
    }
    
    /**
     * Stitches with default settings optimized for 360° phone panoramas.
     */
    suspend fun stitch360Auto(
        imagePaths: List<String>,
        outputPath: String
    ): Stitch360Result = stitch360(
        imagePaths = imagePaths,
        outputPath = outputPath,
        workMegapix = 0.6,
        seamMegapix = 0.1,
        composeMegapix = -1.0,
        matchConf = 0.3f,
        blendStrength = 5.0f,
        tryCuda = false
    )
    
    // Native methods
    private external fun nativeStitch360(
        imagePaths: Array<String>,
        outputPath: String,
        workMegapix: Float,
        seamMegapix: Float,
        composeMegapix: Float,
        matchConf: Float,
        blendStrength: Float,
        tryCuda: Boolean
    ): NativeStitchResult
    
    private external fun nativeGetError(): String
    
    /**
     * Loads the native library statically.
     * Called automatically by companion object.
     */
    private companion object {
        // Library loading is handled by the public companion object
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
    val errorMessage: String? = null
) {
    override fun toString(): String {
        return if (success) {
            "Stitch360Result(success=true, output=$outputPath, ${width}x$height)"
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
    val height: Int
)