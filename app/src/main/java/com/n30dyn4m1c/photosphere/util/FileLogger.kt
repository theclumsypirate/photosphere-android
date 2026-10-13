package com.n30dyn4m1c.photosphere.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The app's own log file: every INFO-and-above log also lands here as one
 * JSON object per line, so `adb` can pull history the logcat buffer already
 * rotated away.
 *
 * Retention is one hour: entries older than [RETENTION_MILLIS] are dropped on
 * init and every [PRUNE_EVERY] appends (plus a size cap as backstop). DEBUG
 * and VERBOSE stay logcat-only — at 50 Hz the attitude stream alone would
 * fill megabytes per hour.
 *
 * Read it from a debug build without root:
 * `adb shell "run-as <package> cat cache/logs/photosphere.log"`
 */
object FileLogger {

    /** Entries older than this are pruned. */
    const val RETENTION_MILLIS = 3_600_000L

    /** Prune check cadence, in appends. */
    private const val PRUNE_EVERY = 100

    /** Backstop: drop the oldest half past this many bytes. */
    private const val MAX_BYTES = 3_000_000L

    private const val DIRECTORY = "logs"
    private const val FILE_NAME = "photosphere.log"

    private val lock = Any()
    private var directory: File? = null
    private var appendsSincePrune = 0

    /** Points the logger at the cache; also prunes on launch. Safe to repeat. */
    fun init(context: Context) {
        synchronized(lock) {
            val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
            directory = dir
            pruneLocked(now = System.currentTimeMillis())
        }
    }

    /** Appends one JSON line. Never throws; logging must not crash the app. */
    fun append(level: String, message: String, throwable: Throwable? = null) {
        val dir = synchronized(lock) { directory } ?: return
        val line = buildLine(System.currentTimeMillis(), level, message, throwable)
        try {
            // appendText opens, writes and closes every time: each line is
            // durable on its own, so even a crash right after logging keeps it.
            synchronized(lock) {
                File(dir, FILE_NAME).appendText(line + "\n")
                if (++appendsSincePrune >= PRUNE_EVERY) {
                    appendsSincePrune = 0
                    pruneLocked(System.currentTimeMillis())
                }
            }
        } catch (_: Exception) {
            // Logging is best-effort by contract.
        }
    }

    private fun buildLine(now: Long, level: String, message: String, throwable: Throwable?): String {
        val tr = throwable?.stackTraceToString()?.take(4096)?.let { ",\"tr\":\"${escape(it)}\"" } ?: ""
        return "{\"ts\":$now,\"level\":\"$level\",\"tag\":\"${Logging.TAG}\",\"msg\":\"${escape(message)}\"$tr}"
    }

    private fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }.toString()

    private fun pruneLocked(now: Long) {
        val dir = directory ?: return
        try {
            val file = File(dir, FILE_NAME)
            if (!file.isFile) return
            val lines = file.readLines()
            val cutoff = now - RETENTION_MILLIS
            var kept = lines.filter { timestampOf(it) >= cutoff }
            if (file.length() > MAX_BYTES && kept.size > 1) {
                kept = kept.drop(kept.size / 2)
            }
            if (kept.size != lines.size) {
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun timestampOf(line: String): Long {
        // Written by buildLine above: {"ts":<millis>,...
        if (!line.startsWith("{\"ts\":")) return 0L
        val end = line.indexOf(',', 6)
        if (end < 0) return 0L
        return line.substring(6, end).toLongOrNull() ?: 0L
    }
}
