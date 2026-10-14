package com.n30dyn4m1c.photosphere.diagnostics

import android.os.SystemClock
import android.view.Choreographer
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Counts slow UI frames while the app is in the foreground.
 *
 * One timestamp per vsync through [Choreographer]: cheap enough to leave
 * on, with no per-frame logging. Only frames slower than [SLOW_MILLIS]
 * are remembered (bounded list), and only near-freezes above
 * [FREEZE_MILLIS] also land in the JSON log. The report renders the
 * counts plus the worst offenders, so "it lags" arrives with numbers.
 *
 * Started and stopped from the application as activities resume and
 * pause: nothing runs while the app is closed.
 */
object LagTracker {

    /** A frame slower than this counts as lag. */
    const val SLOW_MILLIS = 50L

    /** A frame slower than this is also written to the JSON log. */
    const val FREEZE_MILLIS = 700L

    /** Slowest frames remembered per process, newest last. */
    private const val KEPT = 200

    data class SlowFrame(val atMillis: Long, val durationMillis: Long)

    private val lock = Any()
    private var frames = 0L
    private var slow = ArrayDeque<SlowFrame>()
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        private var lastNanos = 0L

        override fun doFrame(frameTimeNanos: Long) {
            synchronized(lock) {
                if (!running) return
                if (lastNanos != 0L) {
                    val took = (frameTimeNanos - lastNanos) / 1_000_000L
                    frames++
                    if (took >= SLOW_MILLIS) {
                        slow.addLast(SlowFrame(System.currentTimeMillis(), took))
                        while (slow.size > KEPT) slow.removeFirst()
                        if (took >= FREEZE_MILLIS) {
                            Logging.w("UI froze for ${took}ms")
                        }
                    }
                }
                lastNanos = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        synchronized(lock) { running = false }
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /** One-paragraph summary plus the worst frames, for the report. */
    fun snapshot(): String = synchronized(lock) {
        if (frames == 0L) return "No frames measured.\n"
        val worst = slow.sortedByDescending { it.durationMillis }.take(10)
        buildString {
            appendLine("$frames frames measured, ${slow.size} slower than ${SLOW_MILLIS}ms.")
            if (worst.isEmpty()) {
                appendLine("No slow frames.")
            } else {
                val fmt = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT,
                )
                worst.forEach {
                    appendLine("  ${fmt.format(java.util.Date(it.atMillis))} froze ${it.durationMillis}ms")
                }
            }
        }
    }
}
