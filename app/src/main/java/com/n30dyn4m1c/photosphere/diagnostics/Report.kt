package com.n30dyn4m1c.photosphere.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.util.FileLogger
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crash and log report: one .txt with the app version, how the app last
 * ended, saved crashes, slow UI frames, the JSON logs and the live logcat
 * tail. Sections are split by lines of `=`. Then share it or save it
 * through the system picker.
 *
 * No background recording: [FileLogger] already keeps four hours of JSON in
 * the process, [LagTracker] only counts frames while something is on
 * screen, so nothing extra runs, and nothing is logged while the app is
 * closed. Pass [appVersion] explicitly; redaction covers account-like text.
 */
object Report {

    fun install(context: Context, appVersion: String = "unknown") {
        val dir = File(context.filesDir, CRASH_DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val trace = android.util.Log.getStackTraceString(error)
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "${stamp(System.currentTimeMillis())} on ${thread.name}, " +
                        "$appVersion\n$trace",
                )
                dir.listFiles()?.sortedByDescending { it.name }?.drop(CRASHES_KEPT)
                    ?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, error)
        }
        Logging.i("Report installed (app $appVersion)")
    }

    suspend fun build(
        context: Context,
        appVersion: String,
        names: List<String> = emptyList(),
    ): File = withContext(Dispatchers.IO) {
        Logging.i("Report building (app $appVersion)")
        val text = buildString {
            appendLine("App $appVersion")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Written ${stamp(System.currentTimeMillis())}")
            appendLine()

            appendLine("== How the app last ended, as Android recorded it")
            append(exits(context))
            appendLine(divider())

            appendLine("== Crashes the app wrote down")
            val crashes = File(context.filesDir, CRASH_DIR).listFiles()
                ?.sortedByDescending { it.name }.orEmpty()
            if (crashes.isEmpty()) appendLine("None.")
            crashes.forEach { appendLine(it.readText()); appendLine() }
            appendLine(divider())

            appendLine("== Slow frames (lag)")
            append(LagTracker.snapshot())
            appendLine(divider())

            appendLine("== App JSON logs, newest last")
            val recent = FileLogger.readRecent()
            append(if (recent.isBlank()) "None.\n" else recent)
            if (!recent.endsWith("\n")) appendLine()
            appendLine()

            // Debug only: the stairs grid run leaves its optics beside the
            // frames, and the report is the easy way to read them back off the
            // device without adb. Skipped whole when no run exists, and absent
            // from a release build because BuildConfig.DEBUG is a compile time
            // false there.
            if (BuildConfig.DEBUG) {
                stairsOptics(context)?.let { optics ->
                    appendLine("== Stairs grid optics (debug)")
                    append(optics)
                    appendLine(divider())
                }
            }

            appendLine("== What is left in the system log")
            append(log())
        }
        val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "app-report-$appVersion.txt").apply {
            writeText(redact(text, names))
        }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Logging.i("Report shared: ${file.name}")
    }

    private fun exits(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Not kept before Android 11.\n"
        val manager = context.getSystemService(ActivityManager::class.java) ?: return "Unavailable.\n"
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, EXITS_KEPT)
        }.getOrNull().orEmpty()
        if (exits.isEmpty()) return "None recorded.\n"
        return buildString {
            exits.forEach { exit ->
                appendLine(
                    "${stamp(exit.timestamp)}  ${reason(exit.reason)}" +
                        (exit.description?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") +
                        (if (exit.pss > 0) ", ${exit.pss / 1024} MB" else ""),
                )
                if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        exit.traceInputStream?.bufferedReader()?.useLines { lines ->
                            lines.take(ANR_LINES).forEach { appendLine("    $it") }
                        }
                    }
                }
            }
        }
    }

    private fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_CRASH -> "crash (Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "closed for memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much battery or CPU"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it needed stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "other"
        14 -> "frozen"
        15 -> "the app state changed"
        16 -> "the app was updated"
        else -> "other ($code)"
    }

    /**
     * Debug only: the `optics.txt` of the newest stairs grid run, so the lens
     * geometry and the exact stitch command can be read straight out of the
     * report instead of pulled off the device. The directory name and the
     * prefix belong to `app/src/debug`, and the caller is already behind
     * `BuildConfig.DEBUG`; returns null when no run exists.
     */
    private fun stairsOptics(context: Context): String? {
        val root = context.getExternalFilesDir(null)?.let { File(it, STAIRS_DIR) } ?: return null
        val run = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(STAIRS_RUN_PREFIX) }
            ?.maxByOrNull { it.name }
            ?: return null
        val optics = File(run, STAIRS_OPTICS_FILE)
        if (!optics.isFile) return null
        return runCatching {
            "Run ${run.name}\n${optics.readText()}"
        }.getOrElse { "Run ${run.name}: could not be read: ${it.message}\n" }
    }

    private fun log(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "-t", LOG_LINES.toString(),
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
            .also { process.waitFor() }
    }.getOrElse { "Could not be read: ${it.message}\n" }

    internal fun redact(text: String, names: List<String>): String {
        var out = text
        names
            .map { it.trim() }
            .filter { it.length >= MIN_NAME }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { out = out.replace(it, "<account>", ignoreCase = true) }
        SHAPES.forEach { (pattern, replacement) -> out = pattern.replace(out, replacement) }
        return out
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    private fun divider(): String = "=".repeat(DIVIDER_WIDTH)

    private val SHAPES = listOf(
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer <token>",
        Regex("""(?i)((?:access|refresh|id)_token|code|client_secret)(["']?\s*[=:]\s*["']?)[A-Za-z0-9._~+/=-]{8,}""") to "$1$2<token>",
    )

    private const val CRASH_DIR = "crashes"
    private const val REPORT_DIR = "reports"
    private const val CRASHES_KEPT = 3
    private const val EXITS_KEPT = 5
    private const val ANR_LINES = 400
    private const val LOG_LINES = 6000
    private const val STAIRS_DIR = "stairs_grid"
    private const val STAIRS_RUN_PREFIX = "run_"
    private const val STAIRS_OPTICS_FILE = "optics.txt"
    private const val MIN_NAME = 3
    private const val DIVIDER_WIDTH = 64
}
