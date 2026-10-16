package com.n30dyn4m1c.photosphere.orbit

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.util.Logging
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug only lab that binds a live camera preview with a pose analyzer.
 *
 * Preview plus ImageAnalysis only. No capture, no stitching, no file writes.
 * Progress ring is a static demo driven by a simulate button.
 */
@Composable
fun OrbitLabCameraScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.DEBUG) {
        Text("Debug only")
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (!hasPermission) {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Camera permission needed for the lab.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant")
                }
                Button(onClick = onBack) {
                    Text("Back")
                }
            }
        }
        return
    }

    var useMediaPipe by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<OrbitSubjectReading?>(null) }
    var capturedCount by remember { mutableStateOf(0) }
    val lock = remember { OrbitSubjectLock() }
    val plan = remember { orbitPitchPlan() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var analyzer: ImageAnalysis.Analyzer? by remember { mutableStateOf(null) }

    DisposableEffect(useMediaPipe) {
        onDispose {
            (analyzer as? AutoCloseable)?.close()
            analyzer = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            (analyzer as? AutoCloseable)?.close()
            executor.shutdown()
        }
    }

    LaunchedEffect(previewView, useMediaPipe) {
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        (analyzer as? AutoCloseable)?.close()
        val active: ImageAnalysis.Analyzer = if (useMediaPipe) {
            OrbitMediaPipeAnalyzer(context) { reading = it }
        } else {
            OrbitMlKitAnalyzer { reading = it }
        }
        analyzer = active
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also {
                it.setAnalyzer(executor, active)
            }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }.onFailure { error ->
            Logging.e("Orbit lab bind failed", error)
        }
    }

    val hint = reading?.let { subjectHint(it, lock) } ?: "No reading yet"
    val total = plan.size

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("Back")
            }
            Button(onClick = { useMediaPipe = !useMediaPipe }) {
                Text(if (useMediaPipe) "Use ML Kit" else "Use MediaPipe")
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Captured $capturedCount of $total (demo)")
            Button(
                onClick = { capturedCount = (capturedCount + 1).coerceAtMost(total) },
            ) {
                Text("Simulate capture")
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
                OrbitRingOverlay(
                    capturedCount = capturedCount,
                    totalCount = total,
                    activeIndex = capturedCount.coerceAtMost(total - 1),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
