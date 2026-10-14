package com.n30dyn4m1c.photosphere

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.n30dyn4m1c.photosphere.diagnostics.LagTracker
import com.n30dyn4m1c.photosphere.diagnostics.Report
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.util.FileLogger
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        // First, so a crash below is written down.
        Report.install(this, BuildConfig.VERSION_NAME)
        Logging.installCrashHandler()
        FileLogger.init(this)
        Logging.i("PhotoSphereApplication.onCreate")
        isOpenCvAvailable = OpenCVLoader.initLocal()
        if (isOpenCvAvailable) {
            Logging.i("OpenCV initialised: ${OpenCVLoader.OPENCV_VERSION}")
        } else {
            Logging.e("OpenCV failed to initialise; sphere stitching is disabled")
        }

        // A finished sphere whose process died while it was on screen is
        // orphaned in the cache: the result screen holds it only in memory, so
        // nothing will ever delete it (the session prune covers frames, not
        // spheres). Each one is several megabytes, so the leftovers of a dead
        // run are cleared at the next launch — keeping the newest, which may
        // belong to a stitch the user never got to see.
        pruneOrphanedSpheres()

        // Cached pictures must not outlive the app: if the last run ended more
        // than a few seconds ago (swiped out of recents, phone rebooted), its
        // frames and finished sphere are deleted. No screen can still show
        // them after a process death, and the next launch starts fresh.
        // Diagnostics logs and crash reports are kept: only pictures go.
        pruneDeadImageCache()
        watchForeground()
    }

    /** Deletes every cached sphere but the newest, e.g. after a process death. */
    private fun pruneOrphanedSpheres() {
        val directory = File(cacheDir, SphereImageStore.SPHERES_DIRECTORY_NAME)
        val spheres = directory.listFiles { file -> file.name.endsWith(".jpg") } ?: return
        if (spheres.size <= 1) return
        val newest = spheres.maxByOrNull { it.lastModified() } ?: return
        spheres.forEach { stale ->
            if (stale != newest && !stale.delete()) {
                Logging.w("Could not clear orphaned sphere ${stale.name}")
            }
        }
    }

    /**
     * Deletes cached pictures left behind by a dead run.
     *
     * [BACKGROUND_MARKER] records when the app last went to the background. If
     * that was longer than [IMAGE_CACHE_GRACE_MILLIS] ago, or there is no
     * marker at all (a crash or an upgrade), the frames and spheres in
     * the cache belong to nobody and are wiped. A quick return (marker fresh)
     * keeps them: the process may still be alive under a restored task.
     */
    private fun pruneDeadImageCache() {
        val marker = File(cacheDir, BACKGROUND_MARKER)
        val backgroundedAgo = if (marker.exists()) {
            System.currentTimeMillis() - marker.lastModified()
        } else {
            Long.MAX_VALUE
        }
        if (backgroundedAgo > IMAGE_CACHE_GRACE_MILLIS) {
            backgroundScope.launch {
                SphereImageStore.deleteImageCache(this@PhotoSphereApplication)
                marker.delete()
                Logging.i("Cleared dead image cache")
            }
        }
    }

    /**
     * Stamps the time on every backgrounding so the next launch can tell a
     * fresh return from an abandoned run. The stamp is cleared on return; only
     * a process death leaves it behind.
     */
    private fun watchForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (foregroundCount++ == 0) {
                    File(cacheDir, BACKGROUND_MARKER).delete()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--foregroundCount == 0) {
                    runCatching {
                        File(cacheDir, BACKGROUND_MARKER).apply {
                            createNewFile()
                            setLastModified(System.currentTimeMillis())
                        }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            // Slow-frame counting runs only while something is on screen:
            // resumed means visible and interactive, paused means gone.
            override fun onActivityResumed(activity: Activity) {
                if (resumedCount++ == 0) LagTracker.start()
            }

            override fun onActivityPaused(activity: Activity) {
                if (--resumedCount == 0) LagTracker.stop()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        private const val TAG = "PhotoSphere"

        /** Marker file in the cache holding the last backgrounding time. */
        private const val BACKGROUND_MARKER = "last_background"

        /** Grace after backgrounding before cached pictures count as abandoned. */
        private const val IMAGE_CACHE_GRACE_MILLIS = 30_000L

        private var foregroundCount = 0
        private var resumedCount = 0
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** True once the OpenCV native library has loaded successfully. */
        var isOpenCvAvailable: Boolean = false
            private set
    }
}
