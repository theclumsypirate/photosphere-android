package com.n30dyn4m1c.photosphere.stitching

import android.content.Context
import com.n30dyn4m1c.photosphere.BuildConfig

/**
 * Stitcher backend selection utility.
 * 
 * Determines which stitching backend to use based on debug preferences.
 * This is a debug-only feature - release builds always use the Kotlin backend.
 */
object StitcherBackend {
    
    /** SharedPreferences key for C++ stitcher toggle */
    private const val PREFS_KEY = "use_cpp_stitcher"
    private const val PREFS_NAME = "debug_prefs"
    
    /**
     * Enum for available stitcher backends
     */
    enum class Backend {
        /** Kotlin-based stitcher with OpenCV Java (default) */
        KOTLIN,
        /** C++ OpenCV native stitcher (debug only, requires native library) */
        CPP_OPENCV
    }
    
    /**
     * Gets the currently selected stitcher backend from preferences.
     * Returns KOTLIN in release builds or if preference is not set.
     */
    fun getSelectedBackend(context: Context): Backend {
        if (!BuildConfig.DEBUG) {
            return Backend.KOTLIN
        }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val useCpp = prefs.getBoolean(PREFS_KEY, false)
        
        return if (useCpp) {
            Backend.CPP_OPENCV
        } else {
            Backend.KOTLIN
        }
    }
    
    /**
     * Checks if C++ OpenCV stitcher is selected (doesn't verify availability).
     * Use OpenCv360Stitcher.isAvailable() separately to check native library.
     */
    fun isCppStitcherSelected(context: Context): Boolean {
        return getSelectedBackend(context) == Backend.CPP_OPENCV
    }
    
    /**
     * Sets the stitcher backend preference.
     */
    fun setBackend(context: Context, backend: Backend) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_KEY, backend == Backend.CPP_OPENCV)
            .apply()
    }
    
    /**
     * Gets a human-readable description of the current backend.
     */
    fun getBackendDescription(context: Context): String {
        return when (getSelectedBackend(context)) {
            Backend.KOTLIN -> "Kotlin (OpenCV Java) - Sensor-guided capture with Java stitching"
            Backend.CPP_OPENCV -> "C++ OpenCV - Native pipeline (spherical warp, multiband blend, bundle adjustment)"
        }
    }
}