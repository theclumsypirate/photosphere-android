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
 * Material You is optional and off by default. With it on, the wallpaper
 * gives the hues and the app keeps its own neutrals, so the chrome around
 * the picture stays dark and only the accents move. The accents are also the
 * two signal colours guided capture is built on, [SphereAccent] for
 * "captured" and [SphereActive] for "aim here", so the capture markers
 * follow the wallpaper with the palette on and keep the app's own green and
 * amber with it off.
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
            // The wallpaper gives the hues, the app keeps its own neutrals. The
            // camera stays black, as a camera app should, and the window the
            // platform paints at cold start matches what Compose draws, so
            // there is no flash of another dark on the way in.
            val wallpaper = dynamicDarkColorScheme(context)
            wallpaper.copy(
                background = SphereBackground,
                onBackground = SphereOnSurface,
                surface = SphereSurface,
                onSurface = SphereOnSurface,
                surfaceVariant = SphereSurfaceHigh,
                onSurfaceVariant = SphereOnSurfaceVariant,
                outline = SphereOutline,
                surfaceContainerHighest = SphereSurfaceHigh,
                surfaceTint = wallpaper.primary,
            )
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
