package com.n30dyn4m1c.photosphere.orbit

import android.content.Context
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where orbit photos live.
 *
 * One directory per walk under the cache: intermediate data, like sphere
 * sessions. Only photos the user saves to the gallery leave the app (see
 * the viewer screen). Starting a new walk deletes the previous one, and
 * abandoned walks are cleared on demand - a dozen full-resolution JPEGs
 * each, never left to pile up.
 */
object OrbitStore {

    /** Cache subdirectory holding one directory per walk. */
    internal const val WALKS_DIRECTORY = "orbit_walks"

    /** Fresh directory for one walk, created now. */
    fun newWalkDirectory(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(File(context.cacheDir, WALKS_DIRECTORY), "walk_$stamp")
            .apply {
                mkdirs()
                Logging.i("Orbit walk directory $name")
            }
    }

    /** Where angle [index] of a walk is written. */
    fun angleFile(directory: File, index: Int): File =
        File(directory, "orbit_%02d.jpg".format(index))

    /** Deletes every walk except [keep], e.g. when a new walk starts. */
    fun pruneWalks(context: Context, keep: File?) {
        val root = File(context.cacheDir, WALKS_DIRECTORY)
        root.listFiles()?.forEach { walk ->
            if (walk != keep && !walk.deleteRecursively()) {
                Logging.w("Could not clear orbit walk ${walk.name}")
            }
        }
    }
}
