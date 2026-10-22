package com.n30dyn4m1c.photosphere.diagnostics

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
 * A frame counts as slow by how late it started, not by the gap since the
 * previous one. The gap includes every idle moment, so an app that sat
 * still for six seconds looked like it had frozen for six seconds the
 * moment it came back. Lateness measures the thing that matters: the
 * frame missed the moment it was supposed to be drawn.
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

    data class SlowFrame(val atMillis: Long, val lateMillis: Long, val where: String)

    /** Something worth seeing next to the frame times, e.g. a palette flip. */
    data class Mark(val atMillis: Long, val text: String)

    private val lock = Any()
    private var frames = 0L
    private var slow = ArrayDeque<SlowFrame>()
    private var marks = ArrayDeque<Mark>()
    private var running = false

    /** The screen the user was on when the last late frame was drawn. */
    private var where = ""

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // How late this frame started, against the moment it was due.
            // The gap since the previous frame would count every idle
            // moment, which is not lag.
            //
            // System.nanoTime, not SystemClock.uptimeNanos: the latter only
            // exists from API 35, and this app runs from 26. Both read the
            // same monotonic clock Choreographer stamps frameTimeNanos with.
            val late = (System.nanoTime() - frameTimeNanos) / 1_000_000L
            synchronized(lock) {
                if (!running) return
                frames++
                if (late >= SLOW_MILLIS) {
                    slow.addLast(SlowFrame(System.currentTimeMillis(), late, where))
                    while (slow.size > KEPT) slow.removeFirst()
                    if (late >= FREEZE_MILLIS) {
                        Logging.w("UI froze for ${late}ms")
                    }
                }
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

    /**
     * Notes the screen the user is on.
     *
     * A late frame is only half an answer on its own. Knowing it happened
     * while the camera was opening says where the time went, so the screen
     * is remembered with every late frame and shown next to it.
     */
    fun where(screen: String) {
        synchronized(lock) {
            where = screen
        }
        mark(screen)
    }

    /** One line for the settings screen. */
    fun summary(): String = synchronized(lock) {
        if (frames == 0L) return "No frames measured yet."
        val worst = slow.maxOfOrNull { it.lateMillis } ?: 0L
        "$frames frames measured, ${slow.size} late by more than ${SLOW_MILLIS}ms, " +
            "worst ${worst}ms late"
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
            appendLine("Late frames, newest last:")
            if (slow.isEmpty()) {
                appendLine("  None.")
            } else {
                slow.forEach {
                    appendLine(
                        "  ${fmt.format(java.util.Date(it.atMillis))} was ${it.lateMillis}ms late" +
                            if (it.where.isEmpty()) "" else " (${it.where})",
                    )
                }
            }
        }
    }

    /** One-paragraph summary plus the worst frames, for the report. */
    fun snapshot(): String = synchronized(lock) {
        if (frames == 0L) return "No frames measured.\n"
        val worst = slow.sortedByDescending { it.lateMillis }.take(10)
        // Declared before the branches below: both the frames and the moments
        // are stamped with it.
        val fmt = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT,
        )
        buildString {
            appendLine("$frames frames measured, ${slow.size} late by more than ${SLOW_MILLIS}ms.")
            if (worst.isEmpty()) {
                appendLine("No slow frames.")
            } else {
                worst.forEach {
                    appendLine(
                        "  ${fmt.format(java.util.Date(it.atMillis))} was ${it.lateMillis}ms late" +
                            if (it.where.isEmpty()) "" else " (${it.where})",
                    )
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
