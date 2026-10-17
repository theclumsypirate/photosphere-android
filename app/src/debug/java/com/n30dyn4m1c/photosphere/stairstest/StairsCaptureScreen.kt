package com.n30dyn4m1c.photosphere.stairstest

import android.Manifest
import android.content.Context
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
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.camera.AlignmentGate
import com.n30dyn4m1c.photosphere.camera.AlignmentState
import com.n30dyn4m1c.photosphere.camera.FocusReticle
import com.n30dyn4m1c.photosphere.camera.FocusReticleOverlay
import com.n30dyn4m1c.photosphere.camera.GuidanceProfile
import com.n30dyn4m1c.photosphere.camera.SphereTarget
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan
import com.n30dyn4m1c.photosphere.camera.TargetOverlay
import com.n30dyn4m1c.photosphere.camera.TargetRelation
import com.n30dyn4m1c.photosphere.camera.rememberGuidanceFeedback
import com.n30dyn4m1c.photosphere.camera.rememberSphereOptics
import com.n30dyn4m1c.photosphere.orbit.OrbitFieldOfView
import com.n30dyn4m1c.photosphere.orbit.orbitCameraFieldOfView
import com.n30dyn4m1c.photosphere.orbit.wrapDelta
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationData
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Stills taken per target, of which the sharpest is kept.
 *
 * The same idea as the normal path's burst: a frame blurred by handheld shake
 * is a soft patch in the sphere. Three is what the reference device profile
 * uses, and it is the point where another shot buys less than the shake it
 * invites.
 */
private const val BURST_SHOTS = 3

/**
 * How long a new target settles before it is announced.
 *
 * The announcement exists to get the user facing the right way, so it must not
 * fire for a bearing the plan only passed through: a hand-over that is still
 * moving is not yet a decision about where to stand.
 */
private const val ANNOUNCE_SETTLE_MILLIS = 600L

/**
 * How long the stitch waits for pose sidecars still being written.
 *
 * A frame saved a moment before the button press is on disk before its pose
 * is; stitching without it would drop a frame the user just shot. The wait is
 * bounded so a stuck write cannot wedge the screen forever.
 */
private const val POSE_WRITE_WAIT_MILLIS = 5_000L

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

    // The run directory is saveable, so a recreation (a call, a rotation, a
    // process death and restore) comes back to the same folder with the frames
    // already shot still in it, instead of starting a second run beside it.
    val gridPath = rememberSaveable {
        StairsStore.newGridDirectory(context).absolutePath
    }
    val gridDirectory = remember(gridPath) { File(gridPath) }

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
    // Set when the session refuses to bind, so a black viewfinder has an
    // explanation on screen instead of looking like a frozen app.
    var cameraError by remember { mutableStateOf<String?>(null) }
    // On phone stitching of the finished run.
    var stitching by remember { mutableStateOf(false) }
    var stitchProgress by remember { mutableStateOf<Float?>(null) }
    var stitchedFile by remember { mutableStateOf<File?>(null) }
    // Pose sidecars are written off the main thread. The stitch waits on this
    // counter so a frame saved a moment before the button press cannot be
    // dropped for want of its attitude.
    val pendingWrites = remember { AtomicInteger(0) }

    LaunchedEffect(previewView) {
        val provider = runCatching {
            ProcessCameraProvider.getInstance(context).get()
        }.getOrNull()
        if (provider == null) {
            cameraError = "Camera provider unavailable"
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
            cameraError = null
            Logging.i(
                "Stairs camera bound id=$cameraId " +
                    "fov=${lensFov?.hFovDegrees}x${lensFov?.vFovDegrees}",
            )
        }.onFailure { error ->
            cameraError = error.message ?: error.javaClass.simpleName
            Logging.e("Stairs: camera bind failed", error)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Logging.i("Stairs capture screen disposed")
            runCatching { imageCapture = null }
        }
    }

    val orientationState = rememberOrientationData()
    val orientation by orientationState
    val pitchNow = orientation.pitchDegrees

    // Beeps and speech: the walker is holding the phone at arm's length and
    // looking at the room, so the cues the normal path plays are worth having
    // here too.
    val guidance = rememberGuidanceFeedback()

    var started by rememberSaveable { mutableStateOf(false) }
    // Kept as NaN rather than null: a Bundle round-trips a float, and the
    // bearing the walk is anchored on is exactly the kind of one-off value a
    // recreation would otherwise drop.
    var startYawStored by rememberSaveable { mutableStateOf(Float.NaN) }
    val startYawAbs = startYawStored.takeIf { !it.isNaN() }
    // Read back from the run directory rather than built up in memory, so a
    // walk that was interrupted knows which frames it already has.
    var captured by remember { mutableStateOf(StairsStore.savedIndices(gridDirectory)) }
    var manualTarget by remember { mutableStateOf<Int?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var dwellProgress by remember { mutableFloatStateOf(0f) }
    var alignmentState by remember { mutableStateOf(AlignmentState()) }
    var focusReticle by remember { mutableStateOf<FocusReticle?>(null) }
    var lastBeepAt by remember { mutableStateOf(0L) }
    var previousDwell by remember { mutableFloatStateOf(0f) }
    var guidanceMuted by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }

    val plan = remember { stairsPlan(0f) }
    val yawRel = startYawAbs?.let { wrapDelta(orientation.yawDegrees - it) } ?: 0f
    val activeTarget = manualTarget?.takeIf { it !in captured && it in plan.targets.indices }
        ?: plan.nextTarget(captured)

    // The same grid in the app's own target type, anchored on the bearing the
    // walk started at. The overlay draws markers by projecting each target
    // against the live absolute attitude, so it needs absolute bearings; the
    // walk's own plan stays relative and is untouched by this.
    val spherePlan = remember(plan, startYawAbs) {
        val anchor = startYawAbs ?: 0f
        SphereTargetPlan(
            targets = plan.targets.map {
                SphereTarget(it.yawDegrees + anchor, it.pitchDegrees)
            },
            rings = plan.rings.map { ring -> ring.first().index..ring.last().index },
        )
    }

    val gate = remember { AlignmentGate(DWELL_DEGREES, DWELL_MILLIS) }

    // The compass gate of the normal path. Only Unreliable blocks: the plan is
    // anchored on the walk's own starting bearing, so an uncalibrated
    // magnetometer shifts every target together and costs the sphere nothing,
    // while a fusion that says its own output cannot be believed would put the
    // markers in the wrong place entirely.
    val accuracyWarning = when (orientation.accuracy) {
        OrientationAccuracy.Unreliable -> context.getString(R.string.capture_accuracy_blocked)
        OrientationAccuracy.Low -> context.getString(R.string.capture_low_accuracy)
        else -> null
    }
    val compassBlocked = !orientation.accuracy.allowsCapture

    // Which ring the active target belongs to, and how far through it the walk
    // is. A ring is the unit that matters: a run stopped at a ring boundary is
    // a complete band of sphere rather than a walk with a gap in it.
    val ringText = plan.rings
        .indexOfFirst { ring -> ring.any { it.index == activeTarget } }
        .takeIf { it >= 0 && activeTarget != null }
        ?.let { ringIndex ->
            val ring = plan.rings[ringIndex]
            val done = ring.count { it.index in captured }
            "Ring ${ringIndex + 1} of ${plan.rings.size}: $done/${ring.size}"
        }
        .orEmpty()

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

    /**
     * Shoots one target as a burst and keeps the sharpest frame.
     *
     * The whole burst runs in one coroutine on the main thread: the shutter
     * callbacks all arrive on the main executor, and the pose of each shot is
     * read from the sensor's own state at the moment it is taken rather than
     * from whatever the composition last saw.
     */
    fun shutter(index: Int) {
        val capture = imageCapture ?: return
        if (capturing || index in captured) return
        capturing = true
        gate.reset()
        val anchor = startYawAbs
        val target = index
        scope.launch {
            val winner = try {
                captureSharpestBurst(
                    capture = capture,
                    directory = gridDirectory,
                    index = target,
                    shots = BURST_SHOTS,
                    executor = ContextCompat.getMainExecutor(context),
                    poseAt = {
                        val sample = orientationState.value
                        StairsShotPose(
                            yawDegrees = anchor?.let { wrapDelta(sample.yawDegrees - it) } ?: 0f,
                            pitchDegrees = sample.pitchDegrees,
                            rollDegrees = sample.rollDegrees,
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                // Leaving the screen mid-burst is not a capture failure; let the
                // cancellation travel instead of reporting it as one. Whatever
                // candidates were written are scratch names, and the frame is
                // simply still uncaptured.
                capturing = false
                throw cancelled
            } catch (error: Exception) {
                // Half a burst written is none of it: clear the candidates so
                // the directory keeps its one file per target shape.
                capturing = false
                scope.launch(Dispatchers.IO) {
                    discardBurst(gridDirectory, target, BURST_SHOTS)
                }
                statusLine = "Frame $target failed: ${error.message}"
                Logging.e("Stairs frame $target failed", error)
                return@launch
            }

            capturing = false
            captured = captured + target
            statusLine = "Saved frame $target (${captured.size}/${plan.size})"
            Logging.i(
                "Stairs frame $target saved (${captured.size}/${plan.size}) " +
                    "yaw=%.2f pitch=%.2f roll=%.2f".format(
                        Locale.US,
                        winner.pose.yawDegrees,
                        winner.pose.pitchDegrees,
                        winner.pose.rollDegrees,
                    ),
            )
            val writeOpticsNow = !opticsWritten
            opticsWritten = true
            val fov = lensFov
            val framePath = winner.file.absolutePath
            val pose = winner.pose
            val directory = gridDirectory
            val total = plan.size
            pendingWrites.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                try {
                    StairsStore.writePose(
                        directory = directory,
                        index = target,
                        yawDegrees = pose.yawDegrees,
                        pitchDegrees = pose.pitchDegrees,
                        rollDegrees = pose.rollDegrees,
                    )
                    if (writeOpticsNow) {
                        // Read the real pixel size off the first saved frame,
                        // so the note matches the files on disk.
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
                } finally {
                    pendingWrites.decrementAndGet()
                }
            }
        }
    }

    fun stitchOnPhone() {
        if (stitching) return
        stitching = true
        stitchProgress = null
        statusLine = "Stitching on the phone..."
        Logging.i("Stairs stitch requested with ${captured.size} frames")
        scope.launch {
            // A frame saved a moment before the press may still be waiting for
            // its pose sidecar; stitching without it would silently drop the
            // frame, and the stitch has no way to place a frame without one.
            val drained = awaitPendingWrites(pendingWrites)
            val posed = StairsStore.savedIndices(gridDirectory)
            if (!drained) {
                Logging.w(
                    "Stairs stitch started with pose writes still in flight: " +
                        "${posed.size} posed frames on disk",
                )
            }
            if (posed.size < MIN_FRAMES_TO_STITCH) {
                statusLine = "Only ${posed.size} posed frames on disk, nothing to stitch"
                stitching = false
                return@launch
            }
            statusLine = "Stitching ${posed.size} frames on the phone..."
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

    // The shutter: hold the aim on target and it fires by itself. Skipped
    // entirely while the compass is unreliable, for the same reason the normal
    // path refuses there: the markers are drawn from that attitude, so a
    // bearing the fusion says cannot be believed is not a bearing to shoot at.
    LaunchedEffect(yawRel, pitchNow, activeTarget, started, compassBlocked) {
        if (!started || activeTarget == null) return@LaunchedEffect
        val turn = plan.turnTo(yawRel, activeTarget)
        val tilt = plan.tiltTo(pitchNow, activeTarget)
        val distance = maxOf(abs(turn), abs(tilt))
        if (compassBlocked) {
            // Blocked, but the distance is still reported: the reticle keeps
            // telling the user how close the aim is while they recalibrate.
            gate.reset()
            dwellProgress = 0f
            previousDwell = 0f
            alignmentState = AlignmentState(distanceDegrees = distance)
            return@LaunchedEffect
        }
        val now = SystemClock.elapsedRealtime()
        val reading = gate.update(distance, now)
        dwellProgress = reading.dwellProgress
        alignmentState = AlignmentState(
            distanceDegrees = distance,
            dwellProgress = reading.dwellProgress,
            isAligned = reading.isAligned,
            isCapturing = capturing,
        )

        // The beep cadence is the distance: faster as the aim closes, silent
        // once the target is far enough off to be somewhere behind the user.
        if (!guidanceMuted) {
            val interval = GuidanceProfile.beepIntervalMillis(distance)
            if (interval != null && now - lastBeepAt >= interval) {
                lastBeepAt = now
                guidance.beep()
            }
            // Halfway through the dwell, felt rather than seen: the shutter is
            // about to fire.
            if (GuidanceProfile.crossedDwellMilestone(previousDwell, reading.dwellProgress)) {
                guidance.dwellTick()
            }
        }
        previousDwell = reading.dwellProgress

        if (reading.isAligned && reading.isTriggered &&
            plan.isOnTarget(yawRel, pitchNow, activeTarget)
        ) {
            shutter(activeTarget)
        }
    }

    // The spoken hand-over, as on the normal path: "next: above you, to your
    // left". Delayed by a settle so a bearing the walk merely passed through is
    // not announced, and skipped for the first target, which sits wherever the
    // user is already facing.
    LaunchedEffect(activeTarget, started) {
        val target = activeTarget ?: return@LaunchedEffect
        if (!started || captured.isEmpty()) return@LaunchedEffect
        if (guidanceMuted) return@LaunchedEffect
        delay(ANNOUNCE_SETTLE_MILLIS)
        val relation = GuidanceProfile.targetRelation(
            orientationState.value,
            spherePlan.targets[target],
        )
        guidance.announce(
            context.getString(R.string.guidance_target, targetPhrase(context, relation)),
        )
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Tap-to-focus on the preview. It sits above the viewfinder so it
        // receives the taps, and below the HUD so the buttons still win their
        // own corners of the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val nx = offset.x / size.width
                        val ny = offset.y / size.height
                        focusReticle = FocusReticle(nx, ny, isWorking = true, isLocked = false)
                        scope.launch {
                            val locked = runCatching {
                                camera?.focusAtPoint(nx, ny, previewView) ?: false
                            }.getOrDefault(false)
                            focusReticle = FocusReticle(nx, ny, isWorking = false, isLocked = locked)
                        }
                    }
                },
        )

        if (started) {
            // The production overlay: the grid's targets projected onto the
            // preview, with the live one pulsing and the capture reticle in the
            // middle. The text arrows below say the same thing in words, which
            // is what the walk is judged on, but the dots are what make a
            // 62-frame grid walkable without counting.
            TargetOverlay(
                orientation = { orientationState.value },
                alignment = { alignmentState },
                plan = spherePlan,
                activeIndex = activeTarget ?: -1,
                captured = captured,
                fieldOfView = optics.fieldOfView,
            )
        }

        FocusReticleOverlay(focus = focusReticle)

        if (!started) {
            StartOverlay(
                savedCount = captured.size,
                onStart = {
                    startYawStored = orientation.yawDegrees
                    manualTarget = null
                    // The run directory may already hold frames (a resumed
                    // walk), so the count comes from disk rather than memory.
                    captured = StairsStore.savedIndices(gridDirectory)
                    started = true
                    // One format over the whole line: a `.format` on the second
                    // literal of a concatenation would bind only to that half
                    // and misalign the arguments.
                    val startText = (
                        "Stairs grid walk started at yaw=%.2f pitch=%.2f " +
                            "accuracy=%s gyroAssisted=%s framesOnDisk=%d"
                        ).format(
                            Locale.US,
                            orientation.yawDegrees,
                            orientation.pitchDegrees,
                            orientation.accuracy,
                            orientation.isGyroAssisted,
                            captured.size,
                        )
                    Logging.i(startText)
                },
                onExit = onExit,
            )
        } else {
            StairsWalkHud(
                plan = plan,
                captured = captured,
                statusLine = statusLine,
                opticsText = lensFovText,
                ringText = ringText,
                accuracyWarning = accuracyWarning,
                cameraError = cameraError,
                yawRel = yawRel,
                pitchNow = pitchNow,
                activeTarget = activeTarget,
                dwellProgress = dwellProgress,
                capturing = capturing,
                exposureLocked = exposureLocked,
                guidanceMuted = guidanceMuted,
                onToggleGuidance = {
                    guidanceMuted = !guidanceMuted
                    statusLine = if (guidanceMuted) "Guidance muted" else "Guidance on"
                    Logging.i("Stairs guidance muted=$guidanceMuted")
                },
                showList = showList,
                onToggleList = { showList = !showList },
                onRetakeLast = {
                    val last = captured.maxOrNull()
                    if (last != null) {
                        captured = captured - last
                        runCatching {
                            // The frame, its pose and any burst candidate left
                            // behind by an interrupted burst all go together.
                            StairsStore.frameFiles(gridDirectory, last)
                                .forEach { it.delete() }
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
    ringText: String,
    accuracyWarning: String?,
    cameraError: String?,
    yawRel: Float,
    pitchNow: Float,
    activeTarget: Int?,
    dwellProgress: Float,
    capturing: Boolean,
    exposureLocked: Boolean,
    guidanceMuted: Boolean,
    onToggleGuidance: () -> Unit,
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
                    if (ringText.isNotEmpty()) {
                        Text(
                            text = ringText,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (accuracyWarning != null) {
                        Text(
                            text = accuracyWarning,
                            color = Color(0xFFFFC107),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (cameraError != null) {
                        Text(
                            text = "Camera unavailable: $cameraError",
                            color = Color(0xFFEF5350),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
                        onClick = onToggleGuidance,
                        shape = PillShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (guidanceMuted) {
                                    R.string.guidance_toggle_show
                                } else {
                                    R.string.guidance_toggle_hide
                                },
                            ),
                        )
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
private fun StartOverlay(savedCount: Int, onStart: () -> Unit, onExit: () -> Unit) {
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
            if (savedCount > 0) {
                Text(
                    text = "This run already holds $savedCount frames: it will pick " +
                        "up where it stopped.",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
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

/**
 * "behind you, above you, to your left": the spoken direction of a target.
 *
 * The same phrasing the normal path speaks, so the cues sound identical in
 * both walks. The plan's own text arrows stay relative, which is why this reads
 * the forward projection rather than the turn and tilt numbers.
 */
private fun targetPhrase(context: Context, relation: TargetRelation): String {
    val parts = buildList {
        if (relation.isBehind) add(context.getString(R.string.guidance_behind))
        when (relation.vertical) {
            TargetRelation.Vertical.Above -> add(context.getString(R.string.guidance_above))
            TargetRelation.Vertical.Below -> add(context.getString(R.string.guidance_below))
            TargetRelation.Vertical.Level -> {}
        }
        when (relation.horizontal) {
            TargetRelation.Horizontal.Left -> add(context.getString(R.string.guidance_left))
            TargetRelation.Horizontal.Right -> add(context.getString(R.string.guidance_right))
            TargetRelation.Horizontal.Centre -> {}
        }
    }
    return parts.joinToString(", ")
}

/**
 * Waits for the pose writers to drain, up to [POSE_WRITE_WAIT_MILLIS].
 *
 * @return true when nothing is left in flight, false when the wait expired and
 * the caller has to carry on with whatever reached the disk.
 */
private suspend fun awaitPendingWrites(pending: AtomicInteger): Boolean {
    val deadline = SystemClock.elapsedRealtime() + POSE_WRITE_WAIT_MILLIS
    while (pending.get() > 0 && SystemClock.elapsedRealtime() < deadline) {
        delay(50)
    }
    return pending.get() == 0
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
