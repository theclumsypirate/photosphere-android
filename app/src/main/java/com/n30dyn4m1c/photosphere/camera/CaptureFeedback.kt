package com.n30dyn4m1c.photosphere.camera
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val TAG = Logging.TAG

/** Fallback buzz for devices with no predefined tick (API 26–28). */
private const val FALLBACK_TICK_MILLIS = 20L

/**
 * The tick-and-click that tells the user a frame landed.
 *
 * Guided capture fires the shutter on its own, with the phone held at arm's
 * length and the user watching the marker rather than the frame counter, so the
 * confirmation has to be felt and heard rather than read.
 *
 * Holds a loaded sound pool; call [release] when the screen goes away, or use
 * [rememberCaptureFeedback], which does it for you.
 */
class CaptureFeedback(context: Context) {

    private val vibrator: Vibrator? = context.vibrator()

    private val shutterSound = MediaActionSound().apply {
        // Preload: an unloaded sample plays late, and late is worse than silent
        // when the point is to mark the instant of capture.
        load(MediaActionSound.SHUTTER_CLICK)
    }

    private var isReleased = false

    /**
     * Plays the shutter click and a haptic tick.
     *
     * The click is intentionally the system shutter sound: in several
     * jurisdictions a camera must be audible when it captures, and the platform
     * sample is the one that survives silent mode where it is mandated.
     */
    fun onFrameCaptured() {
        if (isReleased) return
        playTick()
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
            .onFailure { Logging.w( "Shutter sound failed", it) }
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        runCatching { shutterSound.release() }
    }

    private fun playTick() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(FALLBACK_TICK_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vibrator.vibrate(effect) }
            .onFailure { Logging.w( "Haptic tick failed", it) }
    }
}

/** A [CaptureFeedback] released with the composition that created it. */
@Composable
fun rememberCaptureFeedback(): CaptureFeedback {
    val context = LocalContext.current
    val feedback = remember(context) { CaptureFeedback(context) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    return feedback
}

private fun Context.vibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }
