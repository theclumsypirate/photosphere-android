package com.n30dyn4m1c.photosphere.orbit

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationData
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Walk-around 360 capture for a person or an object.
 *
 * Stand back, keep the subject centred, and walk around it: the screen
 * shows which bearing is next and fires the shutter on its own when the
 * aim holds still on target. Twelve angles by default. When the set is
 * complete the files go to [onDone] for the orbit viewer.
 *
 * Fully self-contained: permission, camera, guidance and files all live
 * here, nothing outside the `orbit` package is modified.
 *
 * @param onDone called with one file per angle, in plan order.
 * @param onBack leaves without keeping anything.
 */
@Composable
fun OrbitCaptureScreen(
    onDone: (List<File>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    angleCount: Int = DEFAULT_ANGLE_COUNT,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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
        OrbitPermissionGate(onRetry = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        })
        return
    }

    val walkDirectory = remember { OrbitStore.newWalkDirectory(context) }
    LaunchedEffect(walkDirectory) {
        withContext(Dispatchers.IO) { OrbitStore.pruneWalks(context, walkDirectory) }
    }
    val plan = remember(angleCount) { orbitPlan(angleCount) }
    var captured by remember { mutableStateOf(setOf<Int>()) }
    val target = plan.nextTarget(captured)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    LaunchedEffect(previewView) {
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = capture
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
        }.onFailure { error ->
            Logging.e("Orbit camera bind failed", error)
            scope.launch {
                snackbarHostState.showSnackbar("The camera could not start.")
            }
        }
    }

    val orientation by rememberOrientationData()
    var capturing by remember { mutableStateOf(false) }
    var dwellJob by remember { mutableStateOf<Job?>(null) }

    // System back abandons the walk: half a circle is not a product.
    BackHandler(onBack = onBack)

    fun takePhoto(index: Int) {
        val capture = imageCapture ?: return
        if (capturing || index in captured) return
        capturing = true
        val output = ImageCapture.OutputFileOptions.Builder(
            OrbitStore.angleFile(walkDirectory, index),
        ).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    capturing = false
                    captured = captured + index
                    Logging.i("Orbit angle $index captured")
                }

                override fun onError(error: ImageCaptureException) {
                    capturing = false
                    Logging.e("Orbit capture failed", error)
                    scope.launch {
                        snackbarHostState.showSnackbar("That photo did not save. Try again.")
                    }
                }
            },
        )
    }

    // Dwell shutter: hold the aim on target for a second and the photo
    // takes itself. Any drift restarts the wait.
    val activeTarget = target
    LaunchedEffect(orientation.yawDegrees, activeTarget) {
        dwellJob?.cancel()
        dwellJob = null
        if (activeTarget != null && plan.isOnTarget(orientation.yawDegrees, activeTarget)) {
            dwellJob = launch {
                delay(DWELL_MILLIS)
                takePhoto(activeTarget)
            }
        }
    }

    // All angles shot: hand the set over in plan order.
    LaunchedEffect(captured.size) {
        if (target == null) {
            val files = plan.angles.indices.map { OrbitStore.angleFile(walkDirectory, it) }
            onDone(files.filter { it.exists() })
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Angle ${captured.size + 1} of ${plan.angles.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = if (activeTarget == null) {
                        "Done"
                    } else {
                        val turn = plan.turnTo(orientation.yawDegrees, activeTarget)
                        when {
                            plan.isOnTarget(orientation.yawDegrees, activeTarget) ->
                                "Hold still…"
                            turn > 0 -> "Turn right ${turn.toInt()}°"
                            else -> "Turn left ${(-turn).toInt()}°"
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Button(
                    enabled = !capturing && activeTarget != null,
                    onClick = { activeTarget?.let { takePhoto(it) } },
                ) {
                    Text("Shoot now")
                }
            }
        }
    }
}

/** Camera permission is missing and the request was denied. */
@Composable
private fun OrbitPermissionGate(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The orbit walk needs the camera.")
            Button(onClick = onRetry) { Text("Allow camera") }
        }
    }
}

private const val DWELL_MILLIS = 1000L
