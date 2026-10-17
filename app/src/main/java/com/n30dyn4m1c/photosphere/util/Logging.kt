package com.n30dyn4m1c.photosphere.util

import android.util.Log

/**
 * Process-wide logging helpers.
 *
 * The crash handler exists so an uncaught exception leaves its stack trace in
 * logcat under one recognisable tag before the process dies — otherwise a
 * background-thread crash (sensor thread, stitch worker) can kill the app with
 * nothing but the generic AndroidRuntime dump to go on.
 */
object Logging {

    const val TAG = "PhotoSphere"

    /**
     * Logcat plus the JSON file ([FileLogger], INFO and above). DEBUG and
     * VERBOSE stay logcat-only: at 50 Hz the attitude stream alone would fill
     * megabytes of JSON per hour.
     */
    fun i(message: String) {
        Log.i(TAG, message)
        FileLogger.append("I", message)
    }

    fun d(message: String) {
        Log.d(TAG, message)
        FileLogger.append("D", message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        FileLogger.append("W", message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
        FileLogger.append("E", message, throwable)
    }

    fun v(message: String) {
        Log.v(TAG, message)
    }

    /** Logs any uncaught exception to logcat, then lets the normal behavior run (the process dies). */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("Uncaught crash in thread '${thread.name}'", throwable)
            } catch (_: Throwable) {
                // Nothing: logging must never hide the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
