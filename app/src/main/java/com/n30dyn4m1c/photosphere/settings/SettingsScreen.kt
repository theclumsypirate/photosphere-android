package com.n30dyn4m1c.photosphere.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.camera.SamsungHighRes
import com.n30dyn4m1c.photosphere.diagnostics.LagTracker
import com.n30dyn4m1c.photosphere.diagnostics.Report
import com.n30dyn4m1c.photosphere.ui.theme.MaterialYou
import com.n30dyn4m1c.photosphere.util.Logging
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App settings. Today this is the diagnostics report export: what the app
 * recorded (recent activity, crashes, how they ended), saved or shared on
 * request. Space left for future toggles.
 *
 * Two rows sit above the report and are debug only: the Material You switch
 * and the frame timing readout. The report itself is not behind that gate, so
 * a user can still send one.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        // The two extras are for the moment being, so they live behind the
        // debug gate: the palette switch and the frame timing readout. The
        // report below them is not, because a user may need it.
        if (BuildConfig.DEBUG) {
            if (MaterialYou.isSupported) {
                MaterialYouRow(modifier = Modifier.padding(top = 20.dp))
            }
            LagRows(modifier = Modifier.padding(top = 20.dp))
        }
        ReportRows(modifier = Modifier.padding(top = 28.dp))
        if (BuildConfig.DEBUG && SamsungHighRes.isSamsung()) {
            SamsungRows(modifier = Modifier.padding(top = 20.dp))
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text(text = stringResource(R.string.report_back))
        }
    }
}

/**
 * Samsung-only camera extras: the full-sensor still test and the
 * ultrawide preference. Hidden on all other phones.
 */
@Composable
private fun SamsungRows(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var probed by remember { mutableStateOf<SamsungHighRes.HighResTarget?>(null) }
    var ultrawide by remember {
        mutableStateOf(SamsungHighRes.isUltrawidePreferred(context))
    }

    fun failed(error: Throwable) {
        Logging.w("Samsung high-res failed", error)
        Toast.makeText(
            context,
            context.getString(
                R.string.samsung_highres_failed,
                error.message.orEmpty(),
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.samsung_highres_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.samsung_highres_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        val target = probed
        if (target != null) {
            Text(
                text = stringResource(
                    R.string.samsung_highres_probe,
                    "${target.size.width}x${target.size.height}",
                    target.cameraId,
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )
        }
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    runCatching {
                        val found = probed ?: SamsungHighRes.probe(context).getOrThrow()
                            .also { probed = it }
                        val dir = File(context.cacheDir, "samsung_highres").apply {
                            mkdirs()
                        }
                        val file = File(
                            dir,
                            "highres_${found.size.width}x${found.size.height}.jpg",
                        )
                        withContext(Dispatchers.IO) {
                            SamsungHighRes.captureStill(context, found, file)
                        }
                    }.onSuccess { file ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.samsung_highres_saved,
                                file.name,
                                "${file.length() / 1024} KB",
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onFailure(::failed)
                    busy = false
                }
            },
            enabled = !busy,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.samsung_highres_working
                    else R.string.samsung_highres_capture
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.samsung_ultrawide_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.samsung_ultrawide_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ultrawide,
                onCheckedChange = {
                    ultrawide = it
                    SamsungHighRes.setUltrawidePreferred(context, it)
                },
            )
        }
    }
}

/**
 * The Material You switch: take the accent colours from the wallpaper.
 *
 * One line and one switch, no note under it. The platform does the rest, and
 * the option is hidden on anything older than Android 12, where there is no
 * wallpaper palette to take.
 */
@Composable
private fun MaterialYouRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled = MaterialYou.isEnabled
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.settings_material_you),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { MaterialYou.setEnabled(context, it) },
        )
    }
}

/**
 * How the app has been drawing: frames measured, how many were slow, the
 * worst one, and a way to write the whole list to a file.
 *
 * "It lags" is worth nothing without the numbers, and the numbers are only
 * useful next to what was happening at the time, so the file carries the
 * moments too, such as a palette flip.
 */
@Composable
private fun LagRows(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Read again whenever the palette flips: the point of the row is to see
    // what a flip costs.
    val palette = MaterialYou.isEnabled
    var summary by remember { mutableStateOf(LagTracker.summary()) }
    LaunchedEffect(palette) { summary = LagTracker.summary() }

    val saveLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = buildString {
                        appendLine("Frame timing")
                        appendLine(LagTracker.report())
                    }
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
                    } ?: error("no stream for $uri")
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_lag_saved),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Logging.w("Lag log save failed", error)
                Toast.makeText(
                    context,
                    context.getString(R.string.report_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.settings_lag_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(
            onClick = { saveLog.launch("lag-log.txt") },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(text = stringResource(R.string.settings_lag_save))
        }
    }
}

/**
 * Builds the diagnostics report and hands it to save or share.
 *
 * Nothing leaves the device unless the user taps: Save writes through the
 * system picker, Send opens the share sheet. Material3 only.
 */
@Composable
private fun ReportRows(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingReport by remember { mutableStateOf<File?>(null) }

    val saveReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val file = pendingReport
        pendingReport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("no stream for $uri")
                }
                Toast.makeText(context, context.getString(R.string.report_saved), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Logging.w("Report not saved", it)
                Toast.makeText(context, context.getString(R.string.report_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun failed(error: Throwable) {
        Logging.w("Report failed", error)
        Toast.makeText(context, context.getString(R.string.report_failed), Toast.LENGTH_SHORT).show()
    }

    fun prepare(then: (File) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { Report.build(context, BuildConfig.VERSION_NAME) }
                .onSuccess(then)
                .onFailure(::failed)
            busy = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.send_report_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        Button(
            onClick = {
                prepare { file ->
                    pendingReport = file
                    saveReport.launch(file.name)
                }
            },
            enabled = !busy,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.report_preparing else R.string.save_report
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Button(
            onClick = {
                prepare { file ->
                    runCatching { Report.share(context, file) }.onFailure(::failed)
                }
            },
            enabled = !busy,
            shape = PillShape,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text(
                text = stringResource(R.string.send_report),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
