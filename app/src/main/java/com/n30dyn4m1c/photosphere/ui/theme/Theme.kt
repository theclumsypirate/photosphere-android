package com.n30dyn4m1c.photosphere.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app's colour scheme.
 *
 * Dark, always, and not as a default the system can talk it out of.
 *
 * Both screens exist to show an image: one is a live viewfinder, the other
 * frames a finished photograph. A light chrome around either of those throws
 * light back at the user's eyes in exactly the situation where they are judging
 * exposure and detail, and it tints the picture it surrounds. Every serious
 * camera app is dark for the same reason, and the capture screen here is
 * already pinned to black regardless, so following the system into a light
 * scheme would only ever have produced a light result screen bolted onto a
 * black capture screen.
 *
 * Material You is optional, and the two signal colours guided capture is
 * built on, [SphereAccent] for "captured" and [SphereActive] for "aim here",
 * are deliberately kept out of its reach. They are referenced as constants
 * rather than through the colour scheme, so a wallpaper palette can never
 * repaint them into two neighbouring pastels with no contrast against each
 * other or against the scene. Dynamic colour tints the Material components
 * around the picture and nothing else. It stays dark either way: the accent
 * moves, the lightness does not.
 */
private val SphereColors = darkColorScheme(
    primary = SphereAccent,
    onPrimary = SphereBackground,
    primaryContainer = SphereAccent.copy(alpha = 0.16f),
    onPrimaryContainer = SphereAccent,

    secondary = SphereActive,
    onSecondary = SphereBackground,
    secondaryContainer = SphereActive.copy(alpha = 0.16f),
    onSecondaryContainer = SphereActive,

    tertiary = SphereOnSurfaceVariant,
    onTertiary = SphereBackground,

    background = SphereBackground,
    onBackground = SphereOnSurface,
    surface = SphereSurface,
    onSurface = SphereOnSurface,
    surfaceVariant = SphereSurfaceHigh,
    onSurfaceVariant = SphereOnSurfaceVariant,
    surfaceContainerHighest = SphereSurfaceHigh,

    outline = SphereOutline,
    outlineVariant = SphereOutline.copy(alpha = 0.5f),
)

@Composable
fun PhotoSphereTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamic = MaterialYou.isEnabled
    // Recomputed when the switch flips, not on every recomposition: reading
    // the wallpaper palette is a resource lookup, not a free operation.
    val colors = remember(dynamic, context) {
        // The version check sits next to the call so lint sees it: the dynamic
        // scheme only exists from Android 12.
        if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context)
        } else {
            SphereColors
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PhotoSphereTypography,
        shapes = PhotoSphereShapes,
        content = content,
    )
}
