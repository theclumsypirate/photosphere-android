package com.n30dyn4m1c.photosphere.camera
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import java.util.Locale

private const val TAG = Logging.TAG

/**
 * How far off the aim the beeps fall silent.
 *
 * Beyond this the target is not "nearby" in any direction the user can walk a
 * beep towards — a slow tick from a target behind you reads as noise, not
 * guidance. The spoken announcement on target change is the cue that far out.
 */
private const val BEEP_MAX_DISTANCE_DEGREES = 40f

/**
 * The purely-computed half of non-visual guidance, so the behaviour can be
 * pinned by unit tests without a device.
 *
 * Guided capture is an entirely visual loop (reticle, markers, dwell arc), so
 * a blind or low-vision user has no way to find the active target, no sense of
 * how far off the aim is, and no cue that a dwell is filling. This object turns
 * the numbers the visual loop already computes — [AlignmentState.distanceDegrees]
 * and [AlignmentState.dwellProgress] — into the signals the non-visual loop
 * plays: beeps whose rate rises as the aim closes (a reversing sensor), haptic
 * ticks as the dwell fills (the imminent shutter is felt), and a spoken
 * description of where the next target sits relative to the camera.
 */
object GuidanceProfile {

    /**
     * How long to wait between aim beeps, or null when no beep is due.
     *
     * The interval falls from 900 ms (far) to 90 ms (at the alignment
     * threshold) as [distanceDegrees] closes on the threshold — the same
     * "getting closer" compression a reversing sensor uses, where the rate of
     * beeping is the distance. Beyond [BEEP_MAX_DISTANCE_DEGREES] there is no
     * beep at all. NaN (no fix yet) is silent.
     */
    fun beepIntervalMillis(distanceDegrees: Float): Long? {
        if (distanceDegrees.isNaN()) return null
        if (distanceDegrees > BEEP_MAX_DISTANCE_DEGREES) return null
        val t = (distanceDegrees.coerceIn(2f, BEEP_MAX_DISTANCE_DEGREES) - 2f) /
            (BEEP_MAX_DISTANCE_DEGREES - 2f)
        // t = 0 at the threshold (fast beeps), 1 far away (slow beeps): the
        // interval grows with the distance, so the *rate* is the distance.
        return (90.0 + 810.0 * t).toLong().coerceIn(90L, 900L)
    }

    /**
     * Whether the dwell just crossed a haptic milestone.
     *
     * The dwell arc fills over [AlignmentGate.DEFAULT_DWELL_MILLIS]; the
     * halfway crossing is the "it is about to fire" cue, felt rather than
     * seen. The shutter's own tick (in [CaptureFeedback]) marks the crossing
     * of the end, so this only needs the halfway point.
     */
    fun crossedDwellMilestone(previousProgress: Float, currentProgress: Float): Boolean =
        previousProgress < 0.5f && currentProgress >= 0.5f

    /**
     * Where the next target sits relative to the camera's aim, for a spoken
     * announcement.
     *
     * The direction comes from the same projection the overlay draws — the
     * target's unit direction in the camera frame, where +X is to the right of
     * the frame, +Y is up, and +Z is straight down the lens. The thresholds
     * are deliberately wider than the aim tolerance: an announcement exists to
     * get the user *facing* the right way, not to fine-tune the last degree.
     */
    fun targetRelation(orientation: OrientationData, target: SphereTarget): TargetRelation {
        val view = SphereProjection.project(orientation, target)
        val vertical = when {
            view.y > 0.12f -> TargetRelation.Vertical.Above
            view.y < -0.12f -> TargetRelation.Vertical.Below
            else -> TargetRelation.Vertical.Level
        }
        val horizontal = when {
            view.x > 0.12f -> TargetRelation.Horizontal.Right
            view.x < -0.12f -> TargetRelation.Horizontal.Left
            else -> TargetRelation.Horizontal.Centre
        }
        return TargetRelation(
            isBehind = !view.isInFront,
            vertical = vertical,
            horizontal = horizontal,
        )
    }
}

/** Where a target sits relative to the camera, in announcement-sized categories. */
data class TargetRelation(
    val isBehind: Boolean,
    val vertical: Vertical,
    val horizontal: Horizontal,
) {
    enum class Vertical { Above, Below, Level }

    enum class Horizontal { Left, Right, Centre }
}

/**
 * The device-backed half of non-visual guidance: beeps, haptic ticks and
 * speech.
 *
 * Holds a [ToneGenerator] for the aim beeps, the system vibrator for the
 * dwell ticks, and a [TextToSpeech] for the per-target announcements. Call
 * [release] when the screen goes away, or use [rememberGuidanceFeedback],
 * which does it for you.
 */
class GuidanceFeedback(context: Context) {

    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME_PERCENT)
    }.getOrNull().also {
        if (it == null) Log.w(TAG, "ToneGenerator unavailable; aim beeps disabled")
    }

    private val vibrator: Vibrator? = context.vibrator()

    private var textToSpeech: TextToSpeech? = null
    private var speechReady = false
    private var speechQueue = ArrayList<String>()

    /** The TextToSpeech engine initialises asynchronously; remember the context. */
    private val appContext = context.applicationContext

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // The announcement phrases are English-only for now (see the
                // strings issue); pin the locale so the engine does not read
                // them with another language's pronunciation.
                textToSpeech?.language = Locale.US
                speechReady = true
                speechQueue.forEach { speak(it) }
                speechQueue.clear()
            }
        }
    }

    /** One short beep. */
    fun beep() {
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_TONE_MILLIS) }
            .onFailure { Log.w(TAG, "Aim beep failed", it) }
    }

    /** One short haptic tick. */
    fun dwellTick() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vibrator.vibrate(effect) }
            .onFailure { Log.w(TAG, "Dwell tick failed", it) }
    }

    /** Speaks [text], replacing anything still being said. */
    fun announce(text: String) {
        val tts = textToSpeech ?: return
        if (!speechReady) {
            // The engine is still warming up; the announcement is worth
            // keeping — a missed target cue means turning around blind.
            speechQueue.add(text)
            return
        }
        speak(text)
    }

    private fun speak(text: String) {
        runCatching { textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guidance") }
            .onFailure { Log.w(TAG, "Announcement failed", it) }
    }

    fun release() {
        speechQueue.clear()
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        speechReady = false
        runCatching { toneGenerator?.release() }
    }

    private companion object {
        /** ToneGenerator volume is 0..100. */
        const val TONE_VOLUME_PERCENT = 60

        /** Length of one beep; the *interval* between beeps carries the distance. */
        const val BEEP_TONE_MILLIS = 60
    }
}

/** A [GuidanceFeedback] released with the composition that created it. */
@Composable
fun rememberGuidanceFeedback(): GuidanceFeedback {
    val context = LocalContext.current
    val feedback = remember(context) { GuidanceFeedback(context) }
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
