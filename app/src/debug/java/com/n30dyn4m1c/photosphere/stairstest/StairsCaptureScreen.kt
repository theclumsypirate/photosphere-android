package com.n30dyn4m1c.photosphere.stairstest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.camera.AlignmentGate
import com.n30dyn4m1c.photosphere.camera.rememberSphereOptics
import com.n30dyn4m1c.photosphere.orbit.OrbitFieldOfView
import com.n30dyn4m1c.photosphere.orbit.orbitCameraFieldOfView
import com.n30dyn4m1c.photosphere.orbit.wrapDelta
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationData
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Dwell the aim must hold on a target before the shutter fires. */
private const val DWELL_DEGREES = 3f
private const val DWELL_MILLIS = 600L

/**
 * Aspect ratio of the buffer the capture selector asks for (4:3). The optics
 * estimate is scaled by it, so the stitch is not told the frame sees more or
 * less of the world than it does.
 */
private const val STREAM_ASPECT_RATIO = 4f / 3f

/** Fewest posed frames worth handing to the stitcher. */
private const val MIN_FRAMES_TO_STITCH = 2

/**
 * Guided capture on the proven 62 point grid, debug only.
 *
 * The screen tells the walker exactly where to point: the live sensor aim
 * is compared with the active target, arrows say how far to turn and tilt,
 * and a shutter fires on its own once the aim holds. Every frame is saved
 * at the highest resolution the lens offers, untouched, with its attitude
 * in a sidecar, so the offline stitch sees exactly the coordinates the
 * screen aimed at.
 *
 * This is a twin of the orbit screen in spirit but shares no state with the
 * normal capture path: it binds its own camera session and never imports or
 * edits anything from `PhotoSphereCameraScreen`. Debug only.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun StairsCaptureScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        Logging.i("Stairs camera permission granted=$granted")
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (!hasPermission) {
        StairsPermissionGate(onRetry = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        })
        return
    }

    val gridDirectory = remember { StairsStore.newGridDirectory(context) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var camera2Control by remember { mutableStateOf<Camera2CameraControl?>(null) }
    var exposureLocked by remember { mutableStateOf(false) }
    var lensFov by remember { mutableStateOf<OrbitFieldOfView?>(null) }
    var opticsWritten by remember { mutableStateOf(false) }
    // Bound lens id, so the optics estimate describes the lens that is really
    // streaming rather than whichever one the profile would guess.
    var boundCameraId by remember { mutableStateOf<String?>(null) }
    // On phone stitching of the finished run.
    var stitching by remember { mutableStateOf(false) }
    var stitchProgress by remember { mutableStateOf<Float?>(null) }
    var stitchedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(previewView) {
        val provider = runCatching {
            ProcessCameraProvider.getInstance(context).get()
        }.getOrNull()
        if (provider == null) {
            Logging.e("Stairs: camera provider unavailable", null)
            return@LaunchedEffect
        }
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
            .apply { surfaceProvider = previewView.surfaceProvider }
        // Highest resolution the lens will give: this mode exists to test
        // the stitch at full quality, not to save bytes.
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
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
            camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
            // The lens geometry this run is shot at, recorded beside the
            // frames so the offline stitch is not guessing at the field of
            // view the way the virtual set's defaults do.
            val cameraId = Camera2CameraInfo.from(camera!!.cameraInfo).cameraId
            boundCameraId = cameraId
            lensFov = orbitCameraFieldOfView(context, cameraId)
            Logging.i(
                "Stairs camera bound id=$cameraId " +
                    "fov=${lensFov?.hFovDegrees}x${lensFov?.vFovDegrees}",
            )
        }.onFailure { error ->
            Logging.e("Stairs: camera bind failed", error)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Logging.i("Stairs capture screen disposed")
            runCatching { imageCapture = null }
        }
    }

    val orientation by rememberOrientationData()
    val pitchNow = orientation.pitchDegrees

    var started by remember { mutableStateOf(false) }
    var startYawAbs by remember { mutableStateOf<Float?>(null) }
    var captured by remember { mutableStateOf(setOf<Int>()) }
    var manualTarget by remember { mutableStateOf<Int?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var dwellProgress by remember { mutableFloatStateOf(0f) }
    var showList by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }

    val plan = remember { stairsPlan(0f) }
    val yawRel = startYawAbs?.let { wrapDelta(orientation.yawDegrees - it) } ?: 0f
    val activeTarget = manualTarget?.takeIf { it !in captured && it in plan.targets.indices }
        ?: plan.nextTarget(captured)

    val gate = remember { AlignmentGate(DWELL_DEGREES, DWELL_MILLIS) }

    // The device's real optics, from the lens the session bound, so the on
    // phone stitch gets the same field of view, distortion model and EXIF
    // fallback the normal camera feeds it.
    val optics = rememberSphereOptics(boundCameraId, streamAspectRatio = STREAM_ASPECT_RATIO)

    val lensFovText = lensFov?.let {
        "lens %.1f x %.1f deg".format(Locale.US, it.hFovDegrees, it.vFovDegrees)
    } ?: ""

    // One line per target change, not per sensor sample: the guidance itself
    // stays silent so the 50 Hz attitude stream never reaches the log file.
    LaunchedEffect(activeTarget, started) {
        val target = activeTarget
        if (started && target != null) {
            val t = plan.targets[target]
            Logging.i(
                "Stairs target $target (${captured.size}/${plan.size}) " +
                    "yaw=${t.yawDegrees} pitch=${t.pitchDegrees}",
            )
        }
    }

    fun takePhoto(index: Int) {
        val capture = imageCapture ?: return
        if (capturing || index in captured) return
        capturing = true
        gate.reset()
        val shotYaw = yawRel
        val shotPitch = pitchNow
        val shotRoll = orientation.rollDegrees
        val file = StairsStore.frameFile(gridDirectory, index)
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    capturing = false
                    captured = captured + index
                    statusLine = "Saved frame $index (${captured.size}/${plan.size})"
                    Logging.i(
                        "Stairs frame $index saved (${captured.size}/${plan.size}) " +
                            "yaw=%.2f pitch=%.2f roll=%.2f".format(
                                Locale.US,
                                shotYaw,
                                shotPitch,
                                shotRoll,
                            ),
                    )
                    val writeOpticsNow = !opticsWritten
                    opticsWritten = true
                    val fov = lensFov
                    val framePath = file.absolutePath
                    val directory = gridDirectory
                    val total = plan.size
                    scope.launch(Dispatchers.IO) {
                        StairsStore.writePose(directory, index, shotYaw, shotPitch, shotRoll)
                        if (writeOpticsNow) {
                            // Read the real pixel size off the first saved
                            // frame, so the note matches the files on disk.
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(framePath, bounds)
                            StairsStore.writeOptics(
                                directory = directory,
                                hFovDegrees = fov?.hFovDegrees,
                                vFovDegrees = fov?.vFovDegrees,
                                frameWidth = bounds.outWidth,
                                frameHeight = bounds.outHeight,
                                frameCount = total,
                            )
                        }
                    }
                }

                override fun onError(error: ImageCaptureException) {
                    capturing = false
                    statusLine = "Frame $index failed: ${error.message}"
                    Logging.e("Stairs frame $index failed", error)
                }
            },
        )
    }

    fun stitchOnPhone() {
        if (stitching) return
        stitching = true
        stitchProgress = null
        statusLine = "Stitching ${captured.size} frames on the phone..."
        Logging.i("Stairs stitch requested with ${captured.size} frames")
        scope.launch {
            runCatching {
                StairsStitch.stitch(
                    context = context,
                    directory = gridDirectory,
                    optics = optics,
                    // Called from the stitching thread, so hop back to the main
                    // one before touching Compose state.
                    onProgress = { progress ->
                        scope.launch { stitchProgress = progress.fraction }
                    },
                )
            }.onSuccess { result ->
                stitchedFile = result.file
                statusLine = "Stitched ${result.width}x${result.height}: ${result.file.name}"
                Logging.i(
                    "Stairs stitch done ${result.file.name} ${result.width}x${result.height}",
                )
            }.onFailure { error ->
                statusLine = "Stitch failed: ${error.message}"
                Logging.e("Stairs stitch failed", error)
            }
            stitching = false
            stitchProgress = null
        }
    }

    fun shareStitched() {
        val file = stitchedFile ?: return
        runCatching {
            val uri = SphereImageStore.shareUri(context, file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Logging.i("Stairs stitch shared: ${file.name}")
        }.onFailure { error ->
            Logging.w("Stairs stitch share failed", error)
        }
    }

    // The shutter: hold the aim on target and it fires by itself.
    LaunchedEffect(yawRel, pitchNow, activeTarget, started) {
        if (!started || activeTarget == null) return@LaunchedEffect
        val turn = plan.turnTo(yawRel, activeTarget)
        val tilt = plan.tiltTo(pitchNow, activeTarget)
        val distance = maxOf(abs(turn), abs(tilt))
        val reading = gate.update(distance, SystemClock.elapsedRealtime())
        dwellProgress = reading.dwellProgress
        if (reading.isAligned && reading.isTriggered &&
            plan.isOnTarget(yawRel, pitchNow, activeTarget)
        ) {
            takePhoto(activeTarget)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (!started) {
            StartOverlay(
                onStart = {
                    startYawAbs = orientation.yawDegrees
                    manualTarget = null
                    started = true
                    Logging.i(
                        "Stairs grid walk started at yaw=%.2f pitch=%.2f".format(
                            Locale.US,
                            orientation.yawDegrees,
                            orientation.pitchDegrees,
                        ),
                    )
                },
                onExit = onExit,
            )
        } else {
            StairsWalkHud(
                plan = plan,
                captured = captured,
                statusLine = statusLine,
                opticsText = lensFovText,
                yawRel = yawRel,
                pitchNow = pitchNow,
                activeTarget = activeTarget,
                dwellProgress = dwellProgress,
                capturing = capturing,
                exposureLocked = exposureLocked,
                showList = showList,
                onToggleList = { showList = !showList },
                onRetakeLast = {
                    val last = captured.maxOrNull()
                    if (last != null) {
                        captured = captured - last
                        runCatching {
                            StairsStore.frameFile(gridDirectory, last).delete()
                            StairsStore.poseFile(gridDirectory, last).delete()
                        }
                        manualTarget = last
                        statusLine = "Cleared frame $last for retake"
                        Logging.i("Stairs frame $last cleared for retake")
                    }
                },
                onToggleExposure = {
                    val control = camera2Control
                    if (control != null) {
                        runCatching {
                            val lock = !exposureLocked
                            control.addCaptureRequestOptions(
                                CaptureRequestOptions.Builder()
                                    .setCaptureRequestOption(
                                        android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK,
                                        lock,
                                    )
                                    .build()
                            )
                            exposureLocked = lock
                            statusLine = if (lock) "Exposure locked" else "Exposure auto"
                            Logging.i("Stairs exposure locked=$lock")
                        }.onFailure { error ->
                            Logging.w("Stairs exposure lock failed", error)
                        }
                    }
                },
                onDone = {
                    Logging.i(
                        "Stairs walk finished: ${captured.size}/${plan.size} frames " +
                            "at ${gridDirectory.absolutePath}",
                    )
                    onExit()
                },
                onPickTarget = { index ->
                    manualTarget = index
                    gate.reset()
                    Logging.i("Stairs target picked manually: $index")
                },
                stitching = stitching,
                stitchProgress = stitchProgress,
                hasStitched = stitchedFile != null,
                onStitch = { stitchOnPhone() },
                onShare = { shareStitched() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The live walk: progress card, aim guidance, and the action row. */
@Composable
private fun StairsWalkHud(
    plan: StairsPlan,
    captured: Set<Int>,
    statusLine: String,
    opticsText: String,
    yawRel: Float,
    pitchNow: Float,
    activeTarget: Int?,
    dwellProgress: Float,
    capturing: Boolean,
    exposureLocked: Boolean,
    showList: Boolean,
    onToggleList: () -> Unit,
    onRetakeLast: () -> Unit,
    onToggleExposure: () -> Unit,
    onDone: () -> Unit,
    onPickTarget: (Int) -> Unit,
    stitching: Boolean,
    stitchProgress: Float?,
    hasStitched: Boolean,
    onStitch: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Stairs grid: ${captured.size}/${plan.size} captured",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (statusLine.isNotEmpty()) {
                        Text(
                            text = statusLine,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (opticsText.isNotEmpty()) {
                        Text(
                            text = opticsText,
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            val target = activeTarget
            if (target != null) {
                GuideOverlay(
                    target = plan.targets[target],
                    turn = plan.turnTo(yawRel, target),
                    tilt = plan.tiltTo(pitchNow, target),
                    dwellProgress = dwellProgress,
                    capturing = capturing,
                )
            } else {
                Text(
                    text = "All ${plan.size} frames captured. Stitch on the phone, " +
                        "or pull the folder.",
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }

            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onRetakeLast,
                        shape = PillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Retake last")
                    }
                    OutlinedButton(
                        onClick = onToggleList,
                        shape = PillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (showList) "Hide targets" else "Pick target")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onToggleExposure,
                        shape = PillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (exposureLocked) "Unlock exposure" else "Lock exposure")
                    }
                    OutlinedButton(
                        onClick = onDone,
                        shape = PillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Done")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onStitch,
                        enabled = !stitching && captured.size >= MIN_FRAMES_TO_STITCH,
                        shape = PillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stitchLabel(stitching, stitchProgress))
                    }
                    if (hasStitched) {
                        OutlinedButton(
                            onClick = onShare,
                            shape = PillShape,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Share sphere")
                        }
                    }
                }
            }
        }

        if (showList) {
            TargetListPanel(
                plan = plan,
                captured = captured,
                active = activeTarget,
                onPick = onPickTarget,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
    }
}

/** The pre-walk card: one clear instruction and a deliberate start. */
@Composable
private fun StartOverlay(onStart: () -> Unit, onExit: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Stairs grid test",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Stand in one spot. Hold the phone level facing the middle of " +
                    "the room, then press Start. The screen asks for 62 frames and " +
                    "shoots each one when you are aimed at it.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
            Button(onClick = onStart, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                Text("Start")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onExit, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

/** Turn and tilt guidance for the active target. */
@Composable
private fun GuideOverlay(
    target: StairsTarget,
    turn: Float,
    tilt: Float,
    dwellProgress: Float,
    capturing: Boolean,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Target ${target.index}: yaw ${target.yawDegrees.roundToInt()} " +
                    "pitch ${target.pitchDegrees.roundToInt()}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "turn ${arrowFor(turn, "right", "left")}   " +
                    "tilt ${arrowFor(tilt, "down", "up")}",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LinearProgressIndicator(
                progress = { if (capturing) 1f else dwellProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Text(
                text = if (capturing) "shooting" else "hold still",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Label on the on phone stitch button, with progress while it runs. */
private fun stitchLabel(stitching: Boolean, progress: Float?): String {
    if (!stitching) return "Stitch on phone"
    val percent = progress?.let { " ${(it * 100f).roundToInt()}%" } ?: ""
    return "Stitching$percent"
}

/** Arrow glyph plus the degrees left to move, or "on" when close. */
private fun arrowFor(delta: Float, positiveLabel: String, negativeLabel: String): String {
    val magnitude = abs(delta)
    if (magnitude < 1f) return "on"
    val label = if (delta > 0) positiveLabel else negativeLabel
    return "${if (delta > 0) ">>" else "<<"} $label ${magnitude.roundToInt()}"
}

/** Tappable list of every target, so the walker can choose where to go. */
@Composable
private fun TargetListPanel(
    plan: StairsPlan,
    captured: Set<Int>,
    active: Int?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Pick a target",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            plan.targets.forEach { t ->
                val mark = if (t.index in captured) "[x]" else "[ ]"
                val isActive = t.index == active
                Text(
                    text = "$mark ${t.index}. yaw ${t.yawDegrees.roundToInt()} " +
                        "pitch ${t.pitchDegrees.roundToInt()}" +
                        if (isActive) "  < active" else "",
                    color = if (isActive) Color.Yellow else Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(
                            if (isActive) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}

/** Permission gate for the debug screen. */
@Composable
private fun StairsPermissionGate(onRetry: () -> Unit) {
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Camera permission is needed for the grid test.",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry, shape = PillShape) {
                Text("Grant camera")
            }
        }
    }
}
