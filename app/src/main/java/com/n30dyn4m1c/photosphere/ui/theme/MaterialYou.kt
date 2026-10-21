package com.n30dyn4m1c.photosphere.ui.theme

import android.content.Context
import android.os.Build
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * The Material You switch: the app takes its accent colours from the
 * wallpaper, the way the platform intends.
 *
 * Dynamic colour exists from Android 12, where the system derives a palette
 * from the user's wallpaper. Below that there is nothing to derive, so the
 * setting is not shown at all: a switch that can only ever say no is noise.
 *
 * The state lives in shared preferences, the same way the Samsung ultrawide
 * preference does, so it survives a restart without pulling in a new
 * dependency. Off by default, which keeps the app's own palette until the
 * user asks for the wallpaper's.
 */
object MaterialYou {
    private const val PREFS = "photosphere_prefs"
    private const val KEY = "material_you"

    /** True where the platform can derive colours from the wallpaper. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        Logging.i("Material You: $enabled")
    }
}
