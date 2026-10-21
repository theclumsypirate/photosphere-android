package com.n30dyn4m1c.photosphere.extendedcapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import com.n30dyn4m1c.photosphere.camera.SphereCaptureScope
import com.n30dyn4m1c.photosphere.camera.SphereTargetPlan
import com.n30dyn4m1c.photosphere.camera.TargetOverlay
import com.n30dyn4m1c.photosphere.camera.TargetRelation
import com.n30dyn4m1c.photosphere.camera.TargetSelection
import com.n30dyn4m1c.photosphere.camera.rememberGuidanceFeedback
import com.n30dyn4m1c.photosphere.camera.rememberSphereOptics
import com.n30dyn4m1c.photosphere.orbit.wrapDelta
import com.n30dyn4m1c.photosphere.result.PanoramaResultScreen
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationData
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.ui.theme.GlassContent
import com.n30dyn4m1c.photosphere.ui.theme.GlassContentDim
import com.n30dyn4m1c.photosphere.ui.theme.GlassSurface
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import com.n30dyn4m1c.photosphere.ui.theme.SphereActive
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

/**
 * Dwell the aim must hold on a target before the shutter fires.
 *
 * The same tolerance the normal camera uses: tight enough that the frame really
 * is where the grid asked for it, which is what keeps the reference stitch on
 * its planned coordinates, and still holdable by hand.
 */
private const val DWELL_DEGREES = 2f
private const val DWELL_MILLIS = 300L

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
 * Guided capture on a sphere grid, debug only.
 *
 * The screen tells the walker exactly where to point: the live sensor aim is
 * compared with the active target, arrows say how far to turn and tilt, and a
 * shutter fires on its own once the aim holds. Every frame is saved at the
 * highest resolution the lens offers, untouched, with its attitude in a
 * sidecar, so the offline stitch sees exactly the coordinates the screen aimed
 * at.
 *
 * One plan is used. The session binds the main back camera, the lens the 62
 * point grid was proven for, and the grid is walked whenever that lens's field
 * of view is close enough to the frame the grid was laid out for. On a lens
 * that sees less, the plan is laid out from the lens's own field of view
 * instead, so the sphere still closes without gaps. Nothing is chosen by hand:
 * the screen picks the lens, the walk just follows the plan. It is stitched
 * with the same stitcher the normal camera uses, the way the thing it stands in
 * for is stitched (see [ExtendedStitch.Mode]).
 *
 * This shares no state with the normal capture path: it binds its own camera
 * session and never imports or edits anything from `PhotoSphereCameraScreen`.
 * Debug only.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun ExtendedCaptureScreen(
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
        Logging.i("Extended camera permission granted=$granted")
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (!hasPermission) {
        ExtendedPermissionGate(onRetry = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        })
        return
    }

    // The main back lens. The reference grid was proven for its frame, so it is
    // the lens the walk wants, and the screen binds it without asking. A phone
    // with an ultrawide never has to be told which to use: the ultrawide frame
    // is the wrong shape for the grid, so the main lens is the one that walks
    // it. If that lens sees too little for the grid, the plan is laid out from
    // its own field of view instead (see [matchesReferenceFieldOfView]).
    //
    // The run directory is saveable, so a recreation (a call, a rotation, a
    // process death and restore) comes back to the same folder with the frames
    // already shot still in it, instead of starting a second run beside it.
    var runPath by rememberSaveable {
        mutableStateOf(ExtendedStore.newCaptureDirectory(context).absolutePath)
    }
    val captureDirectory = remember(runPath) { File(runPath) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
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
    // Set when a stitch finishes, and the screen switches to the same result
    // viewer the normal capture path uses.
    var resultSphere by remember { mutableStateOf<SphereImageStore.StitchedSphere?>(null) }
    // The higher resolution rerun is behind a warning, so it starts here.
    var showHighResDialog by remember { mutableStateOf(false) }
    // Pose sidecars are written off the main thread. The stitch waits on this
    // counter so a frame saved a moment before the button press cannot be
    // dropped for want of its attitude.
    val pendingWrites = remember { AtomicInteger(0) }
    // The camera2 control that holds AE/AWB for the run. Kept so the lock can
    // be released when the screen goes, instead of outliving the walk.
    val runExposureControl = remember { mutableStateOf<Camera2CameraControl?>(null) }

    LaunchedEffect(previewView) {
        val provider = runCatching {
            ProcessCameraProvider.getInstance(context).get()
        }.getOrNull()
        if (provider == null) {
            cameraError = "Camera provider unavailable"
            Logging.e("Extended: camera provider unavailable", null)
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

        fun bind(selector: CameraSelector): Camera {
            provider.unbindAll()
            return provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        }

        runCatching { bind(CameraSelector.DEFAULT_BACK_CAMERA) }
            .onSuccess { bound ->
                camera = bound
                boundCameraId = Camera2CameraInfo.from(bound.cameraInfo).cameraId
                cameraError = null
                Logging.i("Extended camera bound id=$boundCameraId")
            }
            .onFailure { error ->
                cameraError = error.message ?: error.javaClass.simpleName
                Logging.e("Extended: camera bind failed", error)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            Logging.i("Extended capture screen disposed")
            runCatching { runExposureControl.value?.clearCaptureRequestOptions() }
            runCatching { imageCapture = null }
        }
    }

    val orientationState = rememberOrientationData()
    val orientation by orientationState

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
    var captured by remember { mutableStateOf(ExtendedStore.savedIndices(captureDirectory)) }
    var activeIndex by rememberSaveable { mutableStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var dwellProgress by remember { mutableFloatStateOf(0f) }
    var alignmentState by remember { mutableStateOf(AlignmentState()) }
    var focusReticle by remember { mutableStateOf<FocusReticle?>(null) }
    var lastBeepAt by remember { mutableStateOf(0L) }
    var previousDwell by remember { mutableFloatStateOf(0f) }
    var guidanceMuted by remember { mutableStateOf(false) }
    // Log-only text. Nothing in here reaches the screen: the walk HUD shows a
    // count, a direction and a progress bar, and everything else goes to the
    // export log.
    var statusLine by remember { mutableStateOf("") }

    // The device's real optics, from the lens the session bound, so the on
    // phone stitch gets the same field of view, distortion model and EXIF
    // fallback the normal camera feeds it. Its field of view is already in the
    // frame's own axes as displayed, and it reconciles a multi camera's lens
    // list against the profile, which the orbit package's estimate does not: a
    // logical rear camera lists every lens it fronts and the shortest one is
    // the ultrawide, so that estimate can describe a lens this session never
    // bound.
    val optics = rememberSphereOptics(boundCameraId, streamAspectRatio = STREAM_ASPECT_RATIO)

    // The plan follows the lens. The reference grid was proven for one frame,
    // and only a frame close to it can walk the grid without gaps; any lens
    // that sees less gets a plan laid out from its own field of view.
    val usesReferenceGrid = matchesReferenceFieldOfView(optics.fieldOfView)
    val planName = if (usesReferenceGrid) "reference" else "adaptive"

    // Rebuilt the moment the walk is anchored, so every target carries the
    // absolute bearing to aim at, and stable for the rest of the run: the lens
    // is fixed once started, so the field of view cannot change under it.
    val planAnchor = startYawAbs ?: 0f
    val plan = remember(usesReferenceGrid, planAnchor, optics) {
        if (usesReferenceGrid) {
            extendedReferencePlan(planAnchor)
        } else {
            SphereTargetPlan.createForFieldOfView(
                startYawDegrees = planAnchor,
                fieldOfView = optics.fieldOfView,
                scope = SphereCaptureScope.Sphere,
            )
        }
    }

    // The coordinates the reference grid asked for, relative to where the walk
    // started. The sensor yaw drifts over a full turn, so the reference stitch
    // is placed on these instead, then refined against the frames. Roll stays
    // the measured one, which the stitch keeps from the sidecar.
    val plannedPoses = remember(usesReferenceGrid, plan, planAnchor) {
        if (!usesReferenceGrid) {
            emptyMap()
        } else {
            plan.targets.mapIndexed { index, target ->
                index to CameraPose(
                    yawDegrees = wrapDelta(target.yawDegrees - planAnchor),
                    pitchDegrees = target.pitchDegrees,
                    rollDegrees = 0f,
                )
            }.toMap()
        }
    }

    // The active target is the normal path's own selection: it follows the
    // plan's order, hands over when the walker aims at a different uncaptured
    // dot, and moves on by itself once one is shot.
    val activeTarget = TargetSelection.select(plan, orientation, captured, activeIndex)

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

    // One line per change of optics, not per frame, for the log file.
    LaunchedEffect(optics) {
        Logging.i(
            "Extended optics: fov=%.2f x %.2f deg portraitRotation=%d".format(
                Locale.US,
                optics.fieldOfView.horizontalDegrees,
                optics.fieldOfView.verticalDegrees,
                optics.portraitRotationDegrees,
            ),
        )
    }

    LaunchedEffect(plan, planName, boundCameraId) {
        Logging.i(
            "Extended plan: $planName ${plan.size} targets, ${plan.rings.size} rings, " +
                "lens=${boundCameraId ?: "?"}",
        )
    }

    // One line per target change, not per sensor sample: the guidance itself
    // stays silent so the 50 Hz attitude stream never reaches the log file.
    LaunchedEffect(activeTarget, started) {
        val target = activeTarget
        if (started && target != null) {
            val t = plan.targets[target]
            Logging.i(
                "Extended target $target (${captured.size}/${plan.size}) " +
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
        val mode = if (usesReferenceGrid) ExtendedStitch.Mode.Reference else ExtendedStitch.Mode.Adaptive
        val directory = captureDirectory
        scope.launch {
            val winner = try {
                captureSharpestBurst(
                    capture = capture,
                    directory = directory,
                    index = target,
                    shots = BURST_SHOTS,
                    executor = ContextCompat.getMainExecutor(context),
                    poseAt = {
                        val sample = orientationState.value
                        ExtendedShotPose(
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
                    discardBurst(directory, target, BURST_SHOTS)
                }
                statusLine = "Frame $target failed: ${error.message}"
                Logging.e("Extended frame $target failed", error)
                return@launch
            }

            capturing = false
            captured = captured + target
            statusLine = "Saved frame $target (${captured.size}/${plan.size})"
            Logging.i(
                "Extended frame $target saved (${captured.size}/${plan.size}) " +
                    "yaw=%.2f pitch=%.2f roll=%.2f".format(
                        Locale.US,
                        winner.pose.yawDegrees,
                        winner.pose.pitchDegrees,
                        winner.pose.rollDegrees,
                    ),
            )
            val writeOpticsNow = !opticsWritten
            opticsWritten = true
            val framePath = winner.file.absolutePath
            val pose = winner.pose
            val total = plan.size
            val opticsNow = optics
            val lensId = boundCameraId ?: "?"
            pendingWrites.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                try {
                    ExtendedStore.writePose(
                        directory = directory,
                        index = target,
                        yawDegrees = pose.yawDegrees,
                        pitchDegrees = pose.pitchDegrees,
                        rollDegrees = pose.rollDegrees,
                    )
                    if (writeOpticsNow) {
                        // The note describes this frame, so its size comes off
                        // the file, upright, and its field of view from the
                        // same estimate the stitch is handed.
                        val size = ExtendedStore.uprightFrameSize(framePath)
                        if (size == null) {
                            Logging.w(
                                "Extended: could not read ${winner.file.name}, " +
                                    "optics not written",
                            )
                        } else {
                            val (frameWidth, frameHeight) = size
                            ExtendedStore.writeOptics(
                                directory = directory,
                                frameWidth = frameWidth,
                                frameHeight = frameHeight,
                                widthFovDegrees = opticsNow.fieldOfView.horizontalDegrees,
                                heightFovDegrees = opticsNow.fieldOfView.verticalDegrees,
                                frameCount = total,
                                pivot = ExtendedStitch.pivotFor(mode),
                                decodeMaxDimension = if (mode == ExtendedStitch.Mode.Reference) {
                                    maxOf(frameWidth, frameHeight) / 2
                                } else {
                                    ExtendedStitch.MAX_INPUT_DIMENSION
                                },
                                refinement = true,
                                planName = planName,
                                lensId = lensId,
                            )
                        }
                    }
                } finally {
                    pendingWrites.decrementAndGet()
                }
            }
        }
    }

    fun stitchOnPhone(highResolution: Boolean = false) {
        if (stitching) return
        stitching = true
        stitchProgress = null
        val outputWidth = if (highResolution) {
            PrototypeRenderer.HIGH_RESOLUTION_OUTPUT_WIDTH
        } else {
            ExtendedStitch.DEFAULT_REFERENCE_WIDTH
        }
        statusLine = if (highResolution) {
            "Stitching at high resolution on the phone..."
        } else {
            "Stitching on the phone..."
        }
        Logging.i(
            "Extended stitch requested with ${captured.size} frames at width $outputWidth",
        )
        val mode = if (usesReferenceGrid) ExtendedStitch.Mode.Reference else ExtendedStitch.Mode.Adaptive
        scope.launch {
            // A frame saved a moment before the press may still be waiting for
            // its pose sidecar; stitching without it would silently drop the
            // frame, and the stitch has no way to place a frame without one.
            val drained = awaitPendingWrites(pendingWrites)
            val posed = ExtendedStore.savedIndices(captureDirectory)
            if (!drained) {
                Logging.w(
                    "Extended stitch started with pose writes still in flight: " +
                        "${posed.size} posed frames on disk",
                )
            }
            if (posed.size < MIN_FRAMES_TO_STITCH) {
                statusLine = "Only ${posed.size} posed frames on disk, nothing to stitch"
                stitching = false
                return@launch
            }
            statusLine = "Stitching ${posed.size} of ${plan.size} frames on the phone..."
            runCatching {
                ExtendedStitch.stitch(
                    context = context,
                    directory = captureDirectory,
                    optics = optics,
                    mode = mode,
                    outputWidth = outputWidth,
                    plannedPoses = plannedPoses,
                    // Called from the stitching thread, so hop back to the main
                    // one before touching Compose state.
                    onProgress = { progress ->
                        scope.launch { stitchProgress = progress.fraction }
                    },
                )
            }.onSuccess { result ->
                // A 4K rerun replaces the sphere on screen; drop the one it
                // replaces so the cache does not keep both.
                resultSphere?.let { previous ->
                    if (previous.file != result.file) {
                        runCatching { SphereImageStore.deleteCachedSphere(previous.file) }
                    }
                }
                resultSphere = result
                statusLine = "Stitched ${result.width}x${result.height}: ${result.file.name}"
                Logging.i(
                    "Extended stitch done ${result.file.name} ${result.width}x${result.height}",
                )
            }.onFailure { error ->
                statusLine = "Stitch failed: ${error.message}"
                Logging.e("Extended stitch failed", error)
            }
            stitching = false
            stitchProgress = null
        }
    }

    // The shutter: hold the aim on target and it fires by itself. Skipped
    // entirely while the compass is unreliable, for the same reason the normal
    // path refuses there: the markers are drawn from that attitude, so a
    // bearing the fusion says cannot be believed is not a bearing to shoot at.
    LaunchedEffect(
        orientation.yawDegrees,
        orientation.pitchDegrees,
        activeTarget,
        started,
        compassBlocked,
        resultSphere,
    ) {
        // Reading the result: the walk is over, so nothing more is shot.
        if (resultSphere != null) return@LaunchedEffect
        val target = activeTarget ?: return@LaunchedEffect
        if (target != activeIndex) activeIndex = target
        if (!started) return@LaunchedEffect
        val turn = plan.turnTo(orientation.yawDegrees, target)
        val tilt = plan.tiltTo(orientation.pitchDegrees, target)
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
            plan.isOnTarget(orientation.yawDegrees, orientation.pitchDegrees, target)
        ) {
            shutter(target)
        }
    }

    // The spoken hand-over, as on the normal path: "next: above you, to your
    // left". Delayed by a settle so a bearing the walk merely passed through is
    // not announced, and skipped for the first target, which sits wherever the
    // user is already facing.
    LaunchedEffect(activeTarget, started, resultSphere) {
        if (resultSphere != null) return@LaunchedEffect
        val target = activeTarget ?: return@LaunchedEffect
        if (!started || captured.isEmpty()) return@LaunchedEffect
        if (guidanceMuted) return@LaunchedEffect
        delay(ANNOUNCE_SETTLE_MILLIS)
        val relation = GuidanceProfile.targetRelation(
            orientationState.value,
            plan.targets[target],
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
            // is what the walk is judged on, but the dots are what make a dense
            // grid walkable without counting.
            TargetOverlay(
                orientation = { orientationState.value },
                alignment = { alignmentState },
                plan = plan,
                activeIndex = activeTarget ?: -1,
                captured = captured,
                fieldOfView = optics.fieldOfView,
            )
        }

        FocusReticleOverlay(focus = focusReticle)

        val finishedSphere = resultSphere
        if (finishedSphere != null) {
            // The same result viewer the normal camera hands a finished sphere
            // to: flat preview, pannable panorama, fullscreen, export and
            // share. "Take another" comes back here with the run still on disk.
            Box(modifier = Modifier.fillMaxSize()) {
                PanoramaResultScreen(
                    sphere = finishedSphere,
                    frameFiles = ExtendedStore.frameJpegs(captureDirectory),
                    onTakeAnother = {
                        Logging.i("Extended result: take another")
                        // The normal result screen hands the sphere back the
                        // same way: dropping it drops the cached file too, or
                        // every walk would leave a sphere behind.
                        resultSphere = null
                        runCatching { SphereImageStore.deleteCachedSphere(finishedSphere.file) }
                    },
                    // The higher resolution rerun lives on the result page, the
                    // way the normal page carries its own actions, and only for
                    // the reference grid, where a 4K pass matches the proven
                    // render. It is not offered while one is running, nor when
                    // the sphere on screen is already the 4K one.
                    onHighResRerun = if (
                        usesReferenceGrid &&
                        !stitching &&
                        finishedSphere.width < PrototypeRenderer.HIGH_RESOLUTION_OUTPUT_WIDTH
                    ) {
                        { showHighResDialog = true }
                    } else {
                        null
                    },
                    highResRerunLabel = "Make a 4K sphere",
                    modifier = Modifier.fillMaxSize(),
                )
                if (stitching) {
                    Surface(
                        color = GlassSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                    ) {
                        Text(
                            text = stitchLabel(stitching = true, progress = stitchProgress),
                            color = GlassContent,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        } else if (!started) {
            StartOverlay(
                savedCount = captured.size,
                planSize = plan.size,
                onStart = {
                    startYawStored = orientation.yawDegrees
                    activeIndex = 0
                    // The run directory may already hold frames (a resumed
                    // walk), so the count comes from disk rather than memory.
                    captured = ExtendedStore.savedIndices(captureDirectory)
                    started = true
                    // Hold exposure and white balance for the whole run, so the
                    // frames agree at their borders and a flat ceiling does not
                    // fan into light and dark wedges.
                    camera?.let { runExposureControl.value = lockRunExposure(it) }
                    // One format over the whole line: a `.format` on the second
                    // literal of a concatenation would bind only to that half
                    // and misalign the arguments.
                    val startText = (
                        "Extended walk started at yaw=%.2f pitch=%.2f " +
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
            ExtendedWalkHud(
                capturedCount = captured.size,
                planSize = plan.size,
                accuracyWarning = accuracyWarning,
                cameraError = cameraError,
                activeTarget = activeTarget,
                turn = activeTarget?.let { plan.turnTo(orientation.yawDegrees, it) } ?: 0f,
                tilt = activeTarget?.let { plan.tiltTo(orientation.pitchDegrees, it) } ?: 0f,
                dwellProgress = dwellProgress,
                capturing = capturing,
                guidanceMuted = guidanceMuted,
                onToggleGuidance = {
                    guidanceMuted = !guidanceMuted
                    Logging.i("Extended guidance muted=$guidanceMuted")
                },
                canRetake = captured.isNotEmpty() && !capturing,
                onRetakeLast = {
                    val last = captured.maxOrNull()
                    if (last != null) {
                        captured = captured - last
                        runCatching {
                            // The frame, its pose and any burst candidate left
                            // behind by an interrupted burst all go together.
                            ExtendedStore.frameFiles(captureDirectory, last)
                                .forEach { it.delete() }
                        }
                        activeIndex = last
                        Logging.i("Extended frame $last cleared for retake")
                    }
                },
                onStitch = { stitchOnPhone() },
                onExit = onExit,
                stitching = stitching,
                stitchProgress = stitchProgress,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showHighResDialog) {
            AlertDialog(
                onDismissRequest = { showHighResDialog = false },
                title = { Text("Make a 4K sphere?") },
                text = {
                    Text(
                        "The sphere you already have is the normal pass. " +
                            "A 4096 wide rerun is the high resolution one: " +
                            "it needs a lot more RAM and more than 5 minutes.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showHighResDialog = false
                            stitchOnPhone(highResolution = true)
                        },
                    ) {
                        Text("Yes, make 4K")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showHighResDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/**
 * The live walk: a count, a direction, and one button.
 *
 * Deliberately the same shape as the normal capture HUD: a glass progress pill
 * between an undo and a mute control, the guidance line near the bottom, and
 * one primary action. No coordinates, no plan names, no lens numbers: those go
 * to the log, not to a user holding the phone at arm's length.
 */
@Composable
private fun ExtendedWalkHud(
    capturedCount: Int,
    planSize: Int,
    accuracyWarning: String?,
    cameraError: String?,
    activeTarget: Int?,
    turn: Float,
    tilt: Float,
    dwellProgress: Float,
    capturing: Boolean,
    guidanceMuted: Boolean,
    onToggleGuidance: () -> Unit,
    canRetake: Boolean,
    onRetakeLast: () -> Unit,
    onStitch: () -> Unit,
    onExit: () -> Unit,
    stitching: Boolean,
    stitchProgress: Float?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudIconButton(
                    icon = Icons.Filled.Close,
                    description = "Close",
                    onClick = onExit,
                )
                Spacer(modifier = Modifier.width(8.dp))
                HudIconButton(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    description = "Retake the last frame",
                    onClick = onRetakeLast,
                    enabled = canRetake,
                )
                Spacer(modifier = Modifier.weight(1f))
                CaptureCountPill(capturedCount = capturedCount, total = planSize)
                Spacer(modifier = Modifier.weight(1f))
                HudIconButton(
                    icon = if (guidanceMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    description = if (guidanceMuted) {
                        stringResource(R.string.guidance_toggle_show)
                    } else {
                        stringResource(R.string.guidance_toggle_hide)
                    },
                    onClick = onToggleGuidance,
                )
            }

            if (accuracyWarning != null) {
                Spacer(modifier = Modifier.height(10.dp))
                StatusChip(text = accuracyWarning, color = SphereActive)
            }
            if (cameraError != null) {
                Spacer(modifier = Modifier.height(10.dp))
                StatusChip(text = "Camera unavailable: $cameraError", color = Color(0xFFEF5350))
            }

            Spacer(modifier = Modifier.weight(1f))

            if (activeTarget != null) {
                GuideOverlay(
                    turn = turn,
                    tilt = tilt,
                    dwellProgress = dwellProgress,
                    capturing = capturing,
                )
            } else {
                Surface(
                    color = GlassSurface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "All $planSize frames captured.",
                        color = GlassContent,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (stitching) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(
                        progress = { stitchProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = SphereAccent,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            Button(
                onClick = onStitch,
                enabled = !stitching && capturedCount >= MIN_FRAMES_TO_STITCH,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text(
                    text = stitchLabel(stitching, stitchProgress),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** One glass round control, matching the capture HUD's corner buttons. */
@Composable
private fun HudIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = CircleShape,
        color = if (enabled) GlassSurface else GlassSurface.copy(alpha = 0.28f),
        shadowElevation = 8.dp,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) GlassContent else GlassContentDim,
            )
        }
    }
}

/** The count between the corner controls: the one number worth glancing at. */
@Composable
private fun CaptureCountPill(capturedCount: Int, total: Int) {
    val fraction = if (total > 0) capturedCount.toFloat() / total else 0f
    Surface(shape = PillShape, color = GlassSurface, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "$capturedCount",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GlassContent,
                )
                Text(
                    text = "/ $total",
                    style = MaterialTheme.typography.titleSmall,
                    color = GlassContentDim,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(96.dp)
                    .height(3.dp),
                color = SphereAccent,
                trackColor = GlassContent.copy(alpha = 0.22f),
            )
        }
    }
}

/** A single-line warning chip: compass trouble or a camera that will not bind. */
@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(shape = PillShape, color = GlassSurface) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** The pre-walk card: the lens choice, one clear instruction, and a start. */
@Composable
private fun StartOverlay(
    savedCount: Int,
    planSize: Int,
    onStart: () -> Unit,
    onExit: () -> Unit,
) {
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
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Extended capture",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Stand in one spot and turn slowly. The app takes $planSize " +
                    "photos by itself, one each time you aim at a dot.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            )
            if (savedCount > 0) {
                Text(
                    text = "This run already holds $savedCount frames: it will pick " +
                        "up where it stopped.",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
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

/**
 * The one direction line: turn, tilt, or hold still.
 *
 * No coordinates and no target numbers. The dots on the preview already say
 * where the next shot is; this says which way to move towards it, and the bar
 * fills while the aim is held.
 */
@Composable
private fun GuideOverlay(
    turn: Float,
    tilt: Float,
    dwellProgress: Float,
    capturing: Boolean,
) {
    val onTarget = abs(turn) < 1.5f && abs(tilt) < 1.5f
    val text = when {
        capturing -> "Hold still"
        onTarget -> "Hold still"
        abs(turn) >= abs(tilt) -> if (turn > 0) "Turn right" else "Turn left"
        else -> if (tilt > 0) "Tilt down" else "Tilt up"
    }
    Surface(
        color = GlassSurface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                color = GlassContent,
                style = MaterialTheme.typography.headlineSmall,
            )
            LinearProgressIndicator(
                progress = { if (capturing) 1f else dwellProgress },
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(160.dp)
                    .height(5.dp),
                color = SphereAccent,
                trackColor = GlassContent.copy(alpha = 0.22f),
            )
        }
    }
}

/** Label on the stitch button, with progress while it runs. */
private fun stitchLabel(
    stitching: Boolean,
    progress: Float?,
): String {
    if (!stitching) return "Finish and stitch"
    val percent = progress?.let { " ${(it * 100f).roundToInt()}%" } ?: ""
    return "Stitching$percent"
}

/**
 * "behind you, above you, to your left": the spoken direction of a target.
 *
 * The same phrasing the normal path speaks, so the cues sound identical in
 * both walks. It reads the forward projection rather than a raw turn and tilt
 * so that "behind you" and "above you" compose the way a listener expects.
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

/** Permission gate for the debug screen. */
@Composable
private fun ExtendedPermissionGate(onRetry: () -> Unit) {
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Camera permission is needed for extended capture.",
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

/**
 * Holds exposure and white balance where the preview settled them, for a walk.
 *
 * Every frame is stitched against the others, and the stitcher only softens a
 * seam where two frames agree. A run whose frames each auto-expose leaves a
 * brightness step at every ownership border, which reads as a fan of light and
 * dark wedges on a flat ceiling. Locking AE and AWB once the walk starts gives
 * all 62 frames one exposure, so the borders have nothing to disagree about.
 *
 * Returns the camera2 control so [Camera2CameraControl.clearCaptureRequestOptions]
 * can release the lock when the screen goes, or null when the bound camera is
 * not camera2 backed (in which case the run stitches as it always did).
 *
 * Debug only.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun lockRunExposure(camera: Camera): Camera2CameraControl? {
    val control = runCatching { Camera2CameraControl.from(camera.cameraControl) }.getOrNull()
        ?: return null
    val options = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        .build()
    control.addCaptureRequestOptions(options)
    Logging.i("Extended exposure locked (AE+AWB) for the run")
    return control
}
