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

    /** Scrie in logcat orice excepție neprinsă, apoi lasa comportamentul normal (procesul moare). */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "CRASH neprins in firul '${thread.name}'", throwable)
            } catch (_: Throwable) {
                // nimic: nu vrem ca logging-ul sa ascunda crash-ul original
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
