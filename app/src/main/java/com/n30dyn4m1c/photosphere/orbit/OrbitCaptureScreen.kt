package com.n30dyn4m1c.photosphere.orbit

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.n30dyn4m1c.photosphere.camera.AlignmentGate
import com.n30dyn4m1c.photosphere.camera.CaptureFeedback
import com.n30dyn4m1c.photosphere.camera.FocusReticle
import com.n30dyn4m1c.photosphere.camera.FocusReticleOverlay
import com.n30dyn4m1c.photosphere.camera.GuidanceFeedback
import com.n30dyn4m1c.photosphere.camera.GuidanceProfile
import com.n30dyn4m1c.photosphere.camera.rememberCaptureFeedback
import com.n30dyn4m1c.photosphere.camera.rememberGuidanceFeedback
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationData
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Walk-around 360 capture for a person or an object, with the full
 * capture-screen experience: pill HUD, voice guidance, aim beeps, dwell
 * auto-shutter, shutter feedback, brightness slider and retakes.
 *
 * Stand back, keep the subject centred, and walk around it through
 * [angleCount] bearings. When the set is complete the files go to
 * [onDone] in plan order for the orbit viewer.
 *
 * Self-contained apart from imports: nothing outside the `orbit`
 * package is modified.
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

    val guidance: GuidanceFeedback = rememberGuidanceFeedback()
    val shutterSound: CaptureFeedback = rememberCaptureFeedback()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
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
            camera = provider.bindToLifecycle(
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
    val gate = remember(angleCount) { AlignmentGate(ORBIT_DWELL_DEGREES, ORBIT_DWELL_MILLIS) }
    var capturing by remember { mutableStateOf(false) }
    var dwellProgress by remember { mutableFloatStateOf(0f) }

    fun takePhoto(index: Int) {
        val capture = imageCapture ?: return
        if (capturing || index in captured) return
        capturing = true
        gate.reset()
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
                    shutterSound.onFrameCaptured()
                    guidance.dwellTick()
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

    fun retake(index: Int) {
        if (capturing) return
        gate.reset()
        scope.launch(Dispatchers.IO) {
            runCatching { OrbitStore.angleFile(walkDirectory, index).delete() }
            withContext(Dispatchers.Main) {
                captured = captured - index
                Logging.i("Orbit angle $index cleared for retake")
            }
        }
    }

    // Dwell shutter: the gate fires once the aim holds on target.
    val activeTarget = target
    LaunchedEffect(orientation.yawDegrees, activeTarget) {
        if (activeTarget == null) return@LaunchedEffect
        val distance = OrbitGuidance.distanceDegrees(plan, orientation.yawDegrees, activeTarget)
        val reading = gate.update(distance, SystemClock.elapsedRealtime())
        val previous = dwellProgress
        dwellProgress = reading.dwellProgress
        if (GuidanceProfile.crossedDwellMilestone(previous, reading.dwellProgress)) {
            guidance.dwellTick()
        }
        if (reading.isAligned && plan.isOnTarget(orientation.yawDegrees, activeTarget)) {
            takePhoto(activeTarget)
        }
    }

    // Voice line per target, after a beat so it does not talk over itself.
    LaunchedEffect(activeTarget, guidanceEnabled) {
        if (activeTarget == null || !guidanceEnabled) return@LaunchedEffect
        delay(VOICE_SETTLE_MILLIS)
        guidance.announce(OrbitGuidance.announceFor(plan, orientation.yawDegrees, activeTarget))
    }

    // Aim beeps: the closer, the faster.
    LaunchedEffect(activeTarget, capturing, guidanceEnabled) {
        while (activeTarget != null && !capturing && guidanceEnabled) {
            val distance = OrbitGuidance.distanceDegrees(plan, orientation.yawDegrees, activeTarget)
            val interval = GuidanceProfile.beepIntervalMillis(distance) ?: break
            guidance.beep()
            delay(interval)
        }
    }

    // All angles shot: hand the set over in plan order.
    LaunchedEffect(captured.size) {
        if (target == null) {
            val files = plan.angles.indices.map { OrbitStore.angleFile(walkDirectory, it) }
            onDone(files.filter { it.exists() })
        }
    }

    // System back abandons the walk: half a circle is not a product.
    BackHandler(onBack = onBack)

    var guidanceEnabled by remember { mutableStateOf(true) }

    fun walkFiles(): List<File> =
        plan.angles.indices
            .map { OrbitStore.angleFile(walkDirectory, it) }
            .filter { it.exists() }

    fun saveWalkFrames() {
        val files = walkFiles()
        if (files.isEmpty()) return
        Logging.i("Exporting ${files.size} orbit frames")
        scope.launch {
            MediaExporter.exportFrames(context, files)
                .onSuccess { count ->
                    snackbarHostState.showSnackbar("$count orbit photos saved")
                }
                .onFailure { error ->
                    Logging.e("Orbit frame export failed", error)
                    snackbarHostState.showSnackbar("Could not save: ${error.message.orEmpty()}")
                }
        }
    }

    fun restartWalk() {
        if (capturing) return
        gate.reset()
        scope.launch(Dispatchers.IO) {
            runCatching {
                walkDirectory.listFiles()?.forEach { it.delete() }
            }
            withContext(Dispatchers.Main) {
                captured = emptySet()
                Logging.i("Orbit walk restarted")
            }
        }
    }

    // Live exposure compensation, straight from the bound camera.
    val exposureRange = camera?.cameraInfo?.exposureState?.exposureCompensationRange
    val showExposure = camera != null && exposureRange != null &&
        exposureRange.upper > exposureRange.lower
    var exposureIndex by remember { mutableStateOf(0) }

    val hint = if (activeTarget == null) {
        "Done"
    } else {
        OrbitGuidance.hintFor(plan, orientation.yawDegrees, activeTarget)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
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
            // Same aim point and focus square as the sphere capture: the
            // ring warms as the aim closes in and fills with the dwell,
            // the square sits centred and locks green on target.
            val aimDistance = if (activeTarget == null) {
                0f
            } else {
                OrbitGuidance.distanceDegrees(plan, orientation.yawDegrees, activeTarget)
            }
            val onTarget = activeTarget != null &&
                plan.isOnTarget(orientation.yawDegrees, activeTarget)
            OrbitReticleOverlay(
                distanceDegrees = aimDistance,
                thresholdDegrees = ORBIT_DWELL_DEGREES,
                dwellProgress = dwellProgress,
            )
            FocusReticleOverlay(
                focus = FocusReticle(
                    x = 0.5f,
                    y = 0.5f,
                    isWorking = false,
                    isLocked = onTarget,
                ),
                modifier = Modifier.fillMaxSize(),
            )
            OrbitHud(
                capturedCount = captured.size,
                totalTargets = plan.angles.size,
                hint = hint,
                canFinish = captured.size >= ORBIT_MIN_FINISH && activeTarget != null,
                onFinish = {
                    val files = walkFiles()
                    if (files.isNotEmpty()) onDone(files)
                },
                onRestart = ::restartWalk,
                canUndo = captured.isNotEmpty(),
                undoEnabled = !capturing,
                onUndo = {
                    captured.maxOrNull()?.let { retake(it) }
                },
                guidanceEnabled = guidanceEnabled,
                onToggleGuidance = { guidanceEnabled = !guidanceEnabled },
                evIndex = exposureIndex,
                evRange = exposureRange?.let { it.lower..it.upper },
                showExposure = showExposure,
                onEvChange = { value ->
                    exposureIndex = value
                    camera?.cameraControl?.setExposureCompensationIndex(value)
                },
                frameCount = captured.size,
                canSaveFrames = captured.isNotEmpty(),
                onSaveFrames = ::saveWalkFrames,
                isComplete = activeTarget == null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            )
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

private const val ORBIT_DWELL_DEGREES = 6f
private const val ORBIT_DWELL_MILLIS = 800L
private const val VOICE_SETTLE_MILLIS = 600L
private const val ORBIT_MIN_FINISH = 3
