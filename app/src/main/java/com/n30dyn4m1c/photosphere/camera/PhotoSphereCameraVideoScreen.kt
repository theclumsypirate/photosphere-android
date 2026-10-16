package com.n30dyn4m1c.photosphere.camera

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.util.Logging
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only video capture screen.
 *
 * Viewfinder plus record, no photo sphere involved. Muted recording
 * so no audio permission is needed. Release builds never reach here.
 */
@Composable
fun PhotoSphereCameraVideoScreen(
    onVideoSaved: (Uri) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val preview = remember { Preview.Builder().build() }
    val previewView = remember { PreviewView(context) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var savedLabel by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        preview.surfaceProvider = previewView.surfaceProvider
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture,
            )
        }.onFailure { error -> Logging.w("Video bind failed", error) }
        onDispose { runCatching { provider.unbindAll() } }
    }

    fun startRecording() {
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(contentValues).build()
        recording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Logging.i("Video recording started")
                        savedLabel = null
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        if (!event.hasError()) {
                            Logging.i("Video saved: ${event.outputResults.outputUri}")
                            savedLabel = "Saved"
                            onVideoSaved(event.outputResults.outputUri)
                        } else {
                            Logging.w("Video save failed: ${event.error}")
                            savedLabel = "Failed: ${event.error}"
                        }
                    }
                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (recording != null) {
            Text(
                text = "REC",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 16.dp)
                    .background(Color.Red.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (savedLabel != null) {
                Text(text = savedLabel.orEmpty(), color = Color.White)
            }
            FilledTonalButton(
                onClick = { if (recording != null) stopRecording() else startRecording() },
            ) {
                Text(if (recording != null) "Stop" else "Record")
            }
            FilledTonalButton(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
