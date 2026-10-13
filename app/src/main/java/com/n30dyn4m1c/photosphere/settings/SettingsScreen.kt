package com.n30dyn4m1c.photosphere.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.n30dyn4m1c.photosphere.diagnostics.Report
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
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        ReportRows(modifier = Modifier.padding(top = 28.dp))
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text(text = stringResource(R.string.report_back))
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
