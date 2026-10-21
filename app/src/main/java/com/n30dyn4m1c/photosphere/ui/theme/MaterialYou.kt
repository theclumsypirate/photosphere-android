package com.n30dyn4m1c.photosphere.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.n30dyn4m1c.photosphere.diagnostics.LagTracker
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * The Material You switch: the app takes its accent colours from the
 * wallpaper, the way the platform intends.
 *
 * Dynamic colour exists from Android 12, where the system derives a palette
 * from the user's wallpaper. Below that there is nothing to derive, so the
 * setting is not shown at all: a switch that can only ever say no is noise.
 *
 * The flag lives here rather than in a screen, because two very different
 * places read it: the theme that paints the app and the switch that flips
 * it. Keeping it in one observable holder means a flip recomposes those two
 * and nothing else. Held in a composable higher up, every flip would
 * invalidate the whole tree, camera preview and all, and each press would
 * cost more than the last.
 *
 * The stored value lives in shared preferences, the same way the Samsung
 * ultrawide preference does, so it survives a restart without pulling in a
 * new dependency. Off by default, which keeps the app's own palette until
 * the user asks for the wallpaper's.
 */
object MaterialYou {
    private const val PREFS = "photosphere_prefs"
    private const val KEY = "material_you"

    /** The live flag. Read inside a composition, it subscribes to it. */
    private var enabled by mutableStateOf(false)

    /** True where the platform can derive colours from the wallpaper. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Whether the wallpaper palette is in use. */
    val isEnabled: Boolean
        get() = enabled

    /** Reads the stored value once, at startup. */
    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
        Logging.i("Material You loaded: $enabled")
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
        enabled = value
        // The lag report is most useful when it says what happened around a
        // slow frame, so a palette flip leaves a mark next to the frame times.
        LagTracker.mark("Material You ${if (value) "on" else "off"}")
        Logging.i("Material You: $value")
    }
}
