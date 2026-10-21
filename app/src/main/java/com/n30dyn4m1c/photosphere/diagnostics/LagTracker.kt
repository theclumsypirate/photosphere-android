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

    /** Moments remembered per process, newest last. */
    private const val MARKS_KEPT = 50

    data class SlowFrame(val atMillis: Long, val durationMillis: Long)

    /** Something worth seeing next to the frame times, e.g. a palette flip. */
    data class Mark(val atMillis: Long, val text: String)

    private val lock = Any()
    private var frames = 0L
    private var slow = ArrayDeque<SlowFrame>()
    private var marks = ArrayDeque<Mark>()
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

    /**
     * Notes a moment that explains the frame times around it.
     *
     * A slow frame on its own says little. A slow frame right after the user
     * flipped the palette says a lot, so those moments are kept next to the
     * frame list.
     */
    fun mark(text: String) {
        synchronized(lock) {
            marks.addLast(Mark(System.currentTimeMillis(), text))
            while (marks.size > MARKS_KEPT) marks.removeFirst()
        }
    }

    /** One line for the settings screen. */
    fun summary(): String = synchronized(lock) {
        if (frames == 0L) return "No frames measured yet."
        val worst = slow.maxOfOrNull { it.durationMillis } ?: 0L
        "$frames frames measured, ${slow.size} slower than ${SLOW_MILLIS}ms, " +
            "worst ${worst}ms"
    }

    /** The whole picture, for a text file the user can send. */
    fun report(): String = synchronized(lock) {
        buildString {
            // One stamp format for the whole file.
            val fmt = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT,
            )
            appendLine(summary())
            appendLine()
            appendLine("Moments:")
            if (marks.isEmpty()) {
                appendLine("  None.")
            } else {
                marks.forEach {
                    appendLine("  ${fmt.format(java.util.Date(it.atMillis))} ${it.text}")
                }
            }
            appendLine()
            appendLine("Slow frames, newest last:")
            if (slow.isEmpty()) {
                appendLine("  None.")
            } else {
                slow.forEach {
                    appendLine("  ${fmt.format(java.util.Date(it.atMillis))} took ${it.durationMillis}ms")
                }
            }
        }
    }

    /** One-paragraph summary plus the worst frames, for the report. */
    fun snapshot(): String = synchronized(lock) {
        if (frames == 0L) return "No frames measured.\n"
        val worst = slow.sortedByDescending { it.durationMillis }.take(10)
        // Declared before the branches below: both the frames and the moments
        // are stamped with it.
        val fmt = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT,
        )
        buildString {
            appendLine("$frames frames measured, ${slow.size} slower than ${SLOW_MILLIS}ms.")
            if (worst.isEmpty()) {
                appendLine("No slow frames.")
            } else {
                worst.forEach {
                    appendLine("  ${fmt.format(java.util.Date(it.atMillis))} froze ${it.durationMillis}ms")
                }
            }
            if (marks.isNotEmpty()) {
                appendLine("Moments around those frames:")
                marks.forEach {
                    appendLine("  ${fmt.format(java.util.Date(it.atMillis))} ${it.text}")
                }
            }
        }
    }
}
