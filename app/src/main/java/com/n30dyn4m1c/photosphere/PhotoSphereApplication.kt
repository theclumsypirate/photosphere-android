package com.n30dyn4m1c.photosphere

import android.app.Application
import android.util.Log
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.util.Logging
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Loads OpenCV's native library once, at process start.
 *
 * [OpenCVLoader.initLocal] links against the `.so` files packaged inside the
 * APK — there is no separate "OpenCV Manager" app to install. If it returns
 * false the stitching pipeline is unavailable, but capture still works, so we
 * record the state instead of crashing.
 */
class PhotoSphereApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Logging.installCrashHandler()
        isOpenCvAvailable = OpenCVLoader.initLocal()
        if (isOpenCvAvailable) {
            Log.i(TAG, "OpenCV initialised: ${OpenCVLoader.OPENCV_VERSION}")
        } else {
            Log.e(TAG, "OpenCV failed to initialise; sphere stitching is disabled")
        }

        // A finished sphere whose process died while it was on screen is
        // orphaned in the cache: the result screen holds it only in memory, so
        // nothing will ever delete it (the session prune covers frames, not
        // spheres). Each one is several megabytes, so the leftovers of a dead
        // run are cleared at the next launch — keeping the newest, which may
        // belong to a stitch the user never got to see.
        pruneOrphanedSpheres()
    }

    /** Deletes every cached sphere but the newest, e.g. after a process death. */
    private fun pruneOrphanedSpheres() {
        val directory = File(cacheDir, SphereImageStore.SPHERES_DIRECTORY_NAME)
        val spheres = directory.listFiles { file -> file.name.endsWith(".jpg") } ?: return
        if (spheres.size <= 1) return
        val newest = spheres.maxByOrNull { it.lastModified() } ?: return
        spheres.forEach { stale ->
            if (stale != newest && !stale.delete()) {
                Log.w(TAG, "Could not clear orphaned sphere ${stale.name}")
            }
        }
    }

    companion object {
        private const val TAG = "PhotoSphere"

        /** True once the OpenCV native library has loaded successfully. */
        var isOpenCvAvailable: Boolean = false
            private set
    }
}
