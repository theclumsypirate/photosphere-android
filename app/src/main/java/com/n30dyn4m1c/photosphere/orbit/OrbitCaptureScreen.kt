package com.n30dyn4m1c.photosphere.orbit

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.camera.AlignmentGate
import com.n30dyn4m1c.photosphere.camera.FocusReticle
import com.n30dyn4m1c.photosphere.camera.FocusReticleOverlay
import com.n30dyn4m1c.photosphere.camera.GuidanceFeedback
import com.n30dyn4m1c.photosphere.camera.GuidanceProfile
import com.n30dyn4m1c.photosphere.camera.rememberCaptureFeedback
import com.n30dyn4m1c.photosphere.camera.rememberGuidanceFeedback
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationData
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.ui.theme.GlassContent
import com.n30dyn4m1c.photosphere.ui.theme.GlassSurface
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Walk-around 360 capture for a chosen subject, with the full
 * capture-screen experience: pill HUD, voice guidance, aim beeps, dwell
 * auto-shutter, shutter feedback, brightness slider and retakes.
 *
 * Two phases. First tap the subject (person, apple, anything): the lens
 * meters on it and the walk starts from the bearing faced right then.
 * Then walk around it through [angleCount] bearings while the screen
 * guides each step. When the set is complete the files go to [onDone]
 * in plan order for the orbit viewer.
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

    // Phase one: no plan yet. The first tap chooses the subject and the
    // bearing faced at that moment becomes angle zero.
    var started by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<OrbitPlan?>(null) }
    var captured by remember { mutableStateOf(setOf<Int>()) }
    val target = plan?.nextTarget(captured)

    val guidance: GuidanceFeedback = rememberGuidanceFeedback()
    val shutterSound = rememberCaptureFeedback()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Subject-relative guidance (markdown orbit notes): the pose analyzer
    // watches the person while the walk runs on yaw. ML Kit by default,
    // MediaPipe toggle for the 3D path. Both stay inert until bound here,
    // and this whole screen is debug-only via the MainActivity entry.
    var useMediaPipe by remember { mutableStateOf(false) }
    var subjectReading by remember { mutableStateOf<OrbitSubjectReading?>(null) }
    val subjectLock = remember { OrbitSubjectLock() }
    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var boundAnalyzer by remember { mutableStateOf<ImageAnalysis.Analyzer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysis?.let { cameraProvider?.unbind(it) } }
            runCatching { (boundAnalyzer as? AutoCloseable)?.close() }
            analysisExecutor.shutdown()
        }
    }
    LaunchedEffect(previewView, useMediaPipe) {
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = capture
        val analyzer: ImageAnalysis.Analyzer = if (useMediaPipe) {
            OrbitMediaPipeAnalyzer(context) { subjectReading = it }
        } else {
            OrbitMlKitAnalyzer { subjectReading = it }
        }
        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        runCatching { analysis?.let { provider.unbind(it) } }
        runCatching { (boundAnalyzer as? AutoCloseable)?.close() }
        analysis = analysisUseCase
        boundAnalyzer = analyzer
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                analysisUseCase,
            )
        }.onFailure { error ->
            Logging.e("Orbit camera bind failed", error)
            scope.launch {
                snackbarHostState.showSnackbar("The camera could not start.")
            }
        }
    }

    val orientation by rememberOrientationData()

    // Subject tap point, preview-normalised. The square follows it until
    // the walk starts, then returns to the centre.
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    // The tap only locks the subject; the walk starts from the Confirm
    // button below, so the choice is deliberate, never blind.
    val gate = remember(angleCount) { AlignmentGate(ORBIT_DWELL_DEGREES, ORBIT_DWELL_MILLIS) }
    var capturing by remember { mutableStateOf(false) }
    var dwellProgress by remember { mutableFloatStateOf(0f) }

    fun focusSubject(point: Offset) {
        val cam = camera ?: return
        tapPoint = point
        // Lock the skeleton too when the analyzer already sees the person:
        // the walk then guides relative to the subject, not the compass.
        subjectReading?.let { reading ->
            if (subjectLock.lock(reading)) {
                Logging.i("Orbit skeleton locked")
            }
        }
        val factory = SurfaceOrientedMeteringPointFactory(
            previewSize.width.toFloat().coerceAtLeast(1f),
            previewSize.height.toFloat().coerceAtLeast(1f),
        )
        val meteringPoint = factory.createPoint(point.x, point.y)
        val action = FocusMeteringAction.Builder(meteringPoint).build()
        runCatching {
            cam.cameraControl.startFocusAndMetering(action)
        }.onFailure { error ->
            Logging.w("Orbit subject focus failed", error)
        }
        // The "AHA" moment: the lens, the square and the voice all lock
        // onto the tapped subject before the walk starts.
        Logging.i("Orbit subject locked at ${point.x.toInt()},${point.y.toInt()}")
        scope.launch {
            guidance.announce(context.getString(R.string.orbit_subject_locked))
        }
    }

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
                    // Same EXIF pose stamp as the main camera: the stitcher
                    // places each angle from it, no re-shoot needed.
                    val shotYaw = orientation.yawDegrees
                    val shotPitch = orientation.pitchDegrees
                    val shotRoll = orientation.rollDegrees
                    scope.launch(Dispatchers.IO) {
                        SphereImageStore.stampCaptureOrientation(
                            OrbitStore.angleFile(walkDirectory, index),
                            index,
                            shotYaw,
                            shotPitch,
                            shotRoll,
                        )
                    }
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
    val currentPlan = plan
    val activeTarget = target
    LaunchedEffect(orientation.yawDegrees, activeTarget, currentPlan) {
        if (currentPlan == null || activeTarget == null) return@LaunchedEffect
        val distance = OrbitGuidance.distanceDegrees(currentPlan, orientation.yawDegrees, activeTarget)
        val reading = gate.update(distance, SystemClock.elapsedRealtime())
        val previous = dwellProgress
        dwellProgress = reading.dwellProgress
        if (GuidanceProfile.crossedDwellMilestone(previous, reading.dwellProgress)) {
            guidance.dwellTick()
        }
        if (reading.isAligned && currentPlan.isOnTarget(orientation.yawDegrees, activeTarget)) {
            takePhoto(activeTarget)
        }
    }

    // Voice line per target, after a beat so it does not talk over itself.
    LaunchedEffect(activeTarget, currentPlan) {
        if (currentPlan == null || activeTarget == null) return@LaunchedEffect
        delay(VOICE_SETTLE_MILLIS)
        guidance.announce(
            OrbitGuidance.announceFor(context, currentPlan, orientation.yawDegrees, activeTarget),
        )
    }

    // Aim beeps: the closer, the faster.
    var guidanceEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(activeTarget, capturing, guidanceEnabled, currentPlan) {
        while (currentPlan != null && activeTarget != null && !capturing && guidanceEnabled) {
            val distance = OrbitGuidance.distanceDegrees(
                currentPlan, orientation.yawDegrees, activeTarget,
            )
            val interval = GuidanceProfile.beepIntervalMillis(distance) ?: break
            guidance.beep()
            delay(interval)
        }
    }

    // All angles shot: hand the set over in plan order.
    LaunchedEffect(captured.size, currentPlan) {
        if (currentPlan != null && activeTarget == null) {
            val files = currentPlan.angles.indices.map { OrbitStore.angleFile(walkDirectory, it) }
            onDone(files.filter { it.exists() })
        }
    }

    // System back abandons the walk: half a circle is not a product.
    BackHandler(onBack = onBack)

    // Live exposure compensation, straight from the bound camera.
    val exposureRange = camera?.cameraInfo?.exposureState?.exposureCompensationRange
    val showExposure = camera != null && exposureRange != null &&
        exposureRange.upper > exposureRange.lower
    var exposureIndex by remember { mutableStateOf(0) }

    val hint = when {
        currentPlan == null && tapPoint != null ->
            context.getString(R.string.orbit_subject_locked)
        currentPlan == null ->
            context.getString(R.string.orbit_tap_subject)
        activeTarget == null -> "Done"
        else -> OrbitGuidance.hintFor(context, currentPlan, orientation.yawDegrees, activeTarget)
    }
    // Subject-relative line under the yaw hint: where the person is in
    // frame and how far, so the user walks the subject, not the compass.
    // "Stay still" moment is the dwell itself: this line only positions.
    val personHint = subjectReading?.let { reading -> subjectHint(reading, subjectLock) }
    // Vision-driven loop validation (markdown orbit notes): the walk is
    // only a real 360 once the analyzer has seen front, side and back.
    // Yaw still drives the targets; this set proves the circle closed.
    var seenPhases by remember { mutableStateOf(setOf<OrbitPhase>()) }
    LaunchedEffect(subjectReading) {
        val phase = subjectReading?.phase
        if (started && phase != null && phase != OrbitPhase.UNKNOWN) {
            if (phase !in seenPhases) {
                seenPhases = seenPhases + phase
                Logging.i("Orbit phase seen: $phase")
            }
        }
    }
    val loopHint = when {
        !started -> null
        seenPhases.size >= 3 -> "Full circle seen"
        else -> {
            val missing = listOf(OrbitPhase.FRONT, OrbitPhase.PROFILE, OrbitPhase.BACK)
                .filter { it !in seenPhases }
                .joinToString { it.name.lowercase() }
            "Still missing: $missing, keep walking around"
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .onSizeChanged { previewSize = it }
                .pointerInput(started, tapPoint == null) {
                    detectTapGestures { offset ->
                        if (!started) focusSubject(offset)
                    }
                },
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            // Same aim point and focus square as the sphere capture: the
            // ring warms as the aim closes in and fills with the dwell,
            // the square sits centred and locks green on target. Before
            // the walk starts the square follows the subject tap instead.
            val aimDistance = if (currentPlan == null || activeTarget == null) {
                0f
            } else {
                OrbitGuidance.distanceDegrees(currentPlan, orientation.yawDegrees, activeTarget)
            }
            val onTarget = currentPlan != null && activeTarget != null &&
                currentPlan.isOnTarget(orientation.yawDegrees, activeTarget)
            val turn = if (currentPlan == null || activeTarget == null) {
                null
            } else {
                currentPlan.turnTo(orientation.yawDegrees, activeTarget)
            }
            val walkPlan = currentPlan
            if (started && walkPlan != null) {
                OrbitReticleOverlay(
                    distanceDegrees = aimDistance,
                    thresholdDegrees = ORBIT_DWELL_DEGREES,
                    dwellProgress = dwellProgress,
                    turnDegrees = turn,
                )
                OrbitTargetsOverlay(
                    plan = walkPlan,
                    currentYawDegrees = orientation.yawDegrees,
                    activeIndex = activeTarget,
                    captured = captured,
                )
            }
            val square = tapPoint?.takeUnless { started }
            FocusReticleOverlay(
                focus = if (started) {
                    FocusReticle(
                        x = 0.5f,
                        y = 0.5f,
                        isWorking = false,
                        isLocked = onTarget,
                    )
                } else if (square != null && previewSize != IntSize.Zero) {
                    FocusReticle(
                        x = (square.x / previewSize.width).coerceIn(0f, 1f),
                        y = (square.y / previewSize.height).coerceIn(0f, 1f),
                        isWorking = false,
                        isLocked = true,
                    )
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!started) {
                // The tap only locks the subject; Confirm below starts the
                // walk, so the choice is deliberate, never blind.
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = PillShape,
                        color = GlassSurface,
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (tapPoint == null) {
                                    R.string.orbit_tap_subject
                                } else {
                                    R.string.orbit_subject_locked
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = GlassContent,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                        )
                    }
                    // Live subject line from the pose analyzer plus the
                    // ML Kit / MediaPipe switch. Debug lab only.
                    if (personHint != null) {
                        Text(
                            text = personHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassContent,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    TextButton(
                        onClick = { useMediaPipe = !useMediaPipe },
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text(
                            text = if (useMediaPipe) "Pose: MediaPipe" else "Pose: ML Kit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassContent,
                        )
                    }
                    Button(
                        enabled = tapPoint != null,
                        onClick = {
                            plan = orbitPlan(angleCount, orientation.yawDegrees)
                            started = true
                            Logging.i("Orbit walk started")
                        },
                        modifier = Modifier.padding(bottom = 32.dp),
                    ) {
                        Text(stringResource(R.string.orbit_confirm))
                    }
                }
            } else if (currentPlan != null) {
                OrbitHud(
                    capturedCount = captured.size,
                    totalTargets = currentPlan.angles.size,
                    hint = buildString {
                        append(hint)
                        if (personHint != null) {
                            append(". ").append(personHint)
                        }
                        if (loopHint != null) {
                            append(". ").append(loopHint)
                        }
                    },
                    canFinish = captured.size >= ORBIT_MIN_FINISH && activeTarget != null,
                    onFinish = {
                        val files = currentPlan.angles.indices
                            .map { OrbitStore.angleFile(walkDirectory, it) }
                            .filter { it.exists() }
                        if (files.isNotEmpty()) onDone(files)
                    },
                    onRestart = {
                        if (!capturing) {
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
                    },
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
                    onSaveFrames = {
                        val files = currentPlan.angles.indices
                            .map { OrbitStore.angleFile(walkDirectory, it) }
                            .filter { it.exists() }
                        if (files.isNotEmpty()) {
                            Logging.i("Exporting ${files.size} orbit frames")
                            scope.launch {
                                MediaExporter.exportFrames(context, files)
                                    .onSuccess { count ->
                                        snackbarHostState.showSnackbar("$count orbit photos saved")
                                    }
                                    .onFailure { error ->
                                        Logging.e("Orbit frame export failed", error)
                                        snackbarHostState.showSnackbar(
                                            "Could not save: ${error.message.orEmpty()}",
                                        )
                                    }
                            }
                        }
                    },
                    isComplete = activeTarget == null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                )
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

private const val ORBIT_DWELL_DEGREES = 6f
private const val ORBIT_DWELL_MILLIS = 800L
private const val VOICE_SETTLE_MILLIS = 600L
private const val ORBIT_MIN_FINISH = 3
