@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@file:SuppressLint("UnsafeOptInUsageError")

package com.n30dyn4m1c.photosphere.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationTracker
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchException
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.stitching.StitchStage
import com.n30dyn4m1c.photosphere.stitching.StitchStatus
import com.n30dyn4m1c.photosphere.storage.ImageBufferManager
import com.n30dyn4m1c.photosphere.storage.SessionSupersededException
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.storage.rememberImageBufferManager
import com.n30dyn4m1c.photosphere.ui.theme.ChromeScrim
import com.n30dyn4m1c.photosphere.ui.theme.GlassContent
import com.n30dyn4m1c.photosphere.ui.theme.GlassContentDim
import com.n30dyn4m1c.photosphere.ui.theme.GlassSurface
import com.n30dyn4m1c.photosphere.ui.theme.GlassSurfaceDim
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import com.n30dyn4m1c.photosphere.ui.theme.SphereOnSurface
import com.n30dyn4m1c.photosphere.ui.theme.SphereOnSurfaceVariant
import com.n30dyn4m1c.photosphere.ui.theme.SphereSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.acos
import kotlin.math.roundToInt

private const val TAG = "PhotoSphereCamera"

/**
 * How long the hint keeps saying "hold" after the raw hold state drops.
 *
 * Display-only debouncing against gate flapping at the 2° threshold; capture
 * and audio guidance are unaffected.
 */
private const val HINT_HOLD_RELEASE_MILLIS = 400L

/**
 * How long to wait for the 3A to report a converged exposure before locking
 * AE/AWB anyway.
 *
 * The convergence callback locks the moment the HAL reports
 * `CONTROL_AE_STATE_CONVERGED`, which is when the exposure has actually
 * settled on the scene — the right moment on a bright day (a few frames) and
 * on a dark night (after the exposure ramps up). Some HALs never report
 * CONVERGED for a scene, so the lock falls back to this timeout: by then the
 * repeating request has long since settled, and a settled-but-unreported
 * exposure is still far better than the stream-start default a black viewfinder
 * would have frozen.
 */
private const val THREE_A_LOCK_TIMEOUT_MS = 6_000L

/**
 * How much taller a ring capture's canvas band is than the lens's vertical
 * field of view.
 *
 * A ring is shot with the camera level, so each frame reaches about half a
 * field of view above and below the horizon when held upright. Rolling the
 * phone in its own plane can reach further — the corners sweep out the frame's
 * half-diagonal, which for a tall portrait frame is noticeably wider than the
 * vertical half-angle (and for a landscape frame, wider still in the other
 * sense) — so the band is widened to comfortably cover that plus a degree or
 * two of aim error from the gate and any pose-refinement movement.
 */
private const val RING_LATITUDE_SPAN_FACTOR = 1.3f

/**
 * Guided capture: viewfinder, alignment overlay, and an automatic shutter.
 *
 * The screen owns the loop that turns device attitude into frames. A
 * [SphereTargetPlan] is laid out around whichever bearing the user is facing
 * when the first sensor fix lands; [TargetOverlay] projects it onto the preview;
 * and [AlignmentGate] fires [ImageCapture] once the aim has held on the active
 * target long enough to be deliberate. Frames land in cache as full-resolution
 * JPEGs — they are the stitcher's input, not photos the user asked to keep, so
 * they stay out of the gallery until there is a sphere to save.
 *
 * Stitching ends the screen's involvement: the finished sphere goes to
 * [onSphereReady] as a cached, GPano-tagged JPEG, and what happens to it —
 * gallery, share sheet, or nothing — is the result screen's business.
 *
 * The caller is responsible for the CAMERA permission; see
 * `MainActivity.RequirePermissions`.
 *
 * @param onSphereReady called on the main thread with a finished sphere, once
 *   the frames it was built from have been cleared
 */
@Composable
fun PhotoSphereCameraScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Guided capture can take minutes of pointing the phone around the scene;
    // the system screen timeout must not cut in partway through.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        rootView.keepScreenOn = true
        onDispose {
            rootView.keepScreenOn = false
        }
    }

    val tracker = rememberOrientationTracker()
    // Held as a State rather than read with `by`: touching `.value` here would
    // recompose the whole screen at the sensor's rate. Only the overlay's draw
    // lambda reads it.
    val orientationState = tracker.orientation.collectAsStateWithLifecycle()
    val feedback = rememberCaptureFeedback()
    val guidance = rememberGuidanceFeedback()
    val deviceProfile = remember { SphereDeviceProfile.forDevice() }

    // Non-visual guidance: beeps, dwell haptics and spoken target cues, so the
    // capture loop can be driven without sight. Defaults on for a screen
    // reader user; anyone else can switch it on from the HUD. The loops
    // themselves sit further down, once the state they drive from exists.
    var guidanceEnabled by rememberSaveable {
        mutableStateOf(context.isScreenReaderActive())
    }

    // The capture loop's decision-making, and the single owner of where the run
    // has got to. Capture, undo and restart all move that position, and they are
    // not naturally exclusive — a capture suspends for hundreds of milliseconds
    // while its frame is written — so they are serialised in there rather than
    // raced against each other from here.
    val coordinator = remember { SphereCaptureCoordinator() }
    val run by coordinator.guidance.collectAsStateWithLifecycle()

    // Which lens CameraX actually bound, and the shape of the buffer it is
    // producing. Both are only knowable once the bind has resolved, and both
    // scale everything downstream — the spacing of the plan, the rectangles on
    // the overlay, the angle each stitched frame is taken to cover — so the
    // optics are re-read from them rather than from a guess at what would bind.
    var boundCameraId by remember { mutableStateOf<String?>(null) }
    var streamAspectRatio by remember { mutableFloatStateOf(0f) }
    val optics = rememberSphereOptics(boundCameraId, streamAspectRatio)
    val fieldOfView = optics.fieldOfView

    // True once the session's AE/AWB lock has been applied (either by the
    // convergence callback or the timeout fallback). The capture loop waits for
    // it before the first trigger: a still fired mid-convergence would be
    // pinned to the exposure reached so far. Written from the camera thread and
    // the main thread; Compose snapshot state is safe to write from either.
    var isThreeALocked by remember { mutableStateOf(false) }
    // Set on the first successful lock and cleared only by a rebind: the
    // shutter gate needs "the exposure converged at least once", not "it is
    // locked right now", because the user may unlock later for a manual
    // brightness adjustment without killing capture.
    var hasLockedOnce by remember { mutableStateOf(false) }
    // Live brightness: the HAL's exposure-compensation range (null while
    // unbound, 0..0 when unsupported) and the index currently requested.
    var exposureRange by remember { mutableStateOf<IntRange?>(null) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    // The repeating request's camera2 control, for (un)locking AE outside the
    // bind effect (mirrors the atomic used by the convergence callback).
    var camera2ControlState by remember { mutableStateOf<Camera2CameraControl?>(null) }

    // How much of the sphere the plan walks: the whole sphere (rings out to
    // both poles) or just the horizon ring — a regular pano that goes all the
    // way around. Locked once the first frame lands: re-planning under buffered
    // frames would renumber the targets they were shot against.
    var captureScope by rememberSaveable { mutableStateOf(SphereCaptureScope.Sphere) }

    // The plan's anchor survives configuration changes (the buffer does too —
    // see rememberImageBufferManager), so a recreation mid-run rebuilds the same
    // plan rather than re-anchoring it at a fresh bearing. The walk position
    // needs no saving: the coordinator moves off any target the buffer already
    // holds a frame for (see TargetSelection).
    var planStartYawDegrees by rememberSaveable { mutableFloatStateOf(Float.NaN) }

    // How tall the output canvas is, as a span of latitude. A sphere covers the
    // poles (180°); a ring covers only the band of latitude its level frames
    // reach, widened past the vertical field of view so tilt or refinement can
    // not push content off the canvas. A ring is capped at 180°: GPano cannot
    // express a taller span, and throwing over it would discard the render.
    val latitudeSpanDegrees = when (captureScope) {
        SphereCaptureScope.Sphere -> 180f
        SphereCaptureScope.Ring ->
            (fieldOfView.verticalDegrees * RING_LATITUDE_SPAN_FACTOR).coerceAtMost(180f)
    }

    val currentFieldOfView by rememberUpdatedState(fieldOfView)

    // Where focus is aimed and whether the lens has locked there. Set by the
    // initial centre lock once the camera binds, then re-aimed by every tap on
    // the viewfinder.
    var focusReticle by remember { mutableStateOf<FocusReticle?>(null) }

    val previewView = remember(context) {
        PreviewView(context).apply {
            // The projection maths assumes a uniform fill: see
            // SphereProjection.focalLengthPx.
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val buffer = rememberImageBufferManager()
    val bufferedFrames by buffer.frames.collectAsStateWithLifecycle()
    val sessionId by buffer.sessionId.collectAsStateWithLifecycle()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // The locked capture settings this device settled on, and the Camera the
    // locks were bound with. Read by the capture loop, and by the HUD to say
    // what the session is holding.
    var captureProfile by remember { mutableStateOf<SphereCaptureProfile?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var isHolding by remember { mutableStateOf(false) }

    // Display-only debouncing of the hold state: hand tremor at the 2° gate
    // flaps isHolding, which would strobe the hint text through its crossfade.
    // Capture and guidance keep the raw value; only the hint waits 400 ms
    // before admitting the aim was lost.
    var hintHolding by remember { mutableStateOf(false) }
    LaunchedEffect(isHolding) {
        if (isHolding) {
            hintHolding = true
        } else {
            delay(HINT_HOLD_RELEASE_MILLIS)
            hintHolding = false
        }
    }
    var accuracy by remember { mutableStateOf(OrientationAccuracy.Unknown) }

    // Why the camera did not start, and the counter that asks it to try again.
    // A bind can fail for reasons that pass — another app holding the camera,
    // a provider that was not ready — so the failure is a state the user can
    // act on rather than a dead screen.
    var cameraError by remember { mutableStateOf<String?>(null) }
    var bindAttempt by remember { mutableIntStateOf(0) }

    // Lens model defaults. The sensor poses + pinhole combination is the
    // reliable core of the stitch; pose refinement can move frames off the
    // sensor's accurate answer when the scene has parallax (a body-swivel
    // capture of a close scene breaks the pure-rotation feature model), so it
    // starts OFF. The debug toggles let the two optional stages be switched
    // back on to compare.
    var distortionEnabled by remember { mutableStateOf(false) }
    var refinementEnabled by remember { mutableStateOf(false) }
    // Debug: seam-carve the overlaps instead of the wide cross-fade — the
    // sharpness comparison for the same frames.
    var seamEnabled by remember { mutableStateOf(false) }
    // Debug: paint each frame a solid colour so the pano shows where each one
    // was placed — distinguishes a placement bug from a content bug.
    var colorFrames by remember { mutableStateOf(false) }

    /**
     * Runs a focus sweep at a normalised point in the preview and locks the
     * lens there once it converges.
     *
     * This is tap-to-focus: AE and AWB are already locked by the session, so a
     * tap only moves focus. In `AF_MODE_AUTO` the lens holds the converged
     * distance until the next trigger, which is what keeps every frame of the
     * sphere on one focal plane — and what keeps the shots sharp on the actual
     * scene rather than parked at infinity.
     */
    fun lockFocusAt(nx: Float, ny: Float) {
        val camera = boundCamera
        if (camera == null) return
        focusReticle = FocusReticle(x = nx, y = ny, isWorking = true, isLocked = false)
        scope.launch {
            val ran = runCatching { camera.focusAt(nx, ny, previewView) }.getOrDefault(false)
            focusReticle = FocusReticle(x = nx, y = ny, isWorking = false, isLocked = ran)
        }
    }

    // A one-time reminder of how to stand, shown before the first frame falls
    // so the capture that follows is built on a steady pivot.
    var showInstructions by rememberSaveable { mutableStateOf(true) }

    /**
     * Pins or releases the preview's auto-exposure outside the bind effect.
     *
     * Locking re-pins whatever the HAL converged on (the brightness slider's
     * counterpart: adjust, then lock). AWB is left alone — only AE moves.
     */
    fun setAeLocked(locked: Boolean) {
        val control = camera2ControlState
        if (control == null) {
            Log.w(TAG, "No camera control yet for AE ${if (locked) "lock" else "unlock"}")
            return
        }
        runCatching {
            control.addCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                    .build()
            )
        }.onSuccess { isThreeALocked = locked }
            .onFailure { error -> Log.w(TAG, "Could not change AE lock", error) }
    }

    /**
     * Requests an exposure-compensation index for a brighter/darker preview.
     *
     * A locked AE ignores compensation changes on most HALs, so adjusting
     * unlocks first — the lock button (or the next bind) re-pins. Stills
     * follow the preview on most HALs; where they don't, the preview is still
     * an honest brightness aid while aiming.
     */
    fun applyExposure(index: Int, force: Boolean = false) {
        val camera = boundCamera ?: return
        val clamped = exposureRange?.let { index.coerceIn(it.first, it.last) } ?: index
        if (!force && clamped == exposureIndex && !isThreeALocked) return
        exposureIndex = clamped
        if (isThreeALocked) setAeLocked(false)
        runCatching {
            camera.cameraControl.setExposureCompensationIndex(clamped)
                .addListener(
                    { Log.i(TAG, "Exposure compensation -> $clamped") },
                    ContextCompat.getMainExecutor(context),
                )
        }.onFailure { error ->
            Log.w(TAG, "Could not set exposure compensation", error)
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.capture_failed, error.message.orEmpty())
                )
            }
        }
    }

    // The stitch runs off the main thread and reports back from there, so its
    // progress travels as a StateFlow rather than as Compose state written from
    // a background dispatcher.
    val stitchProgress = remember { MutableStateFlow(StitchProgress.Preparing) }
    var stitchJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Changes at the sensor's rate and is therefore never read during
     * composition — only inside [TargetOverlay]'s draw lambda.
     */
    var alignment by remember { mutableStateOf(AlignmentState()) }

    /** Clears the guidance state so the next sphere starts from scratch. */
    suspend fun resetGuidance() {
        coordinator.reset()
        planStartYawDegrees = Float.NaN
        isHolding = false
        alignment = AlignmentState()
    }

    // The guidance loop. Polls the alignment state (sensor-rate data) rather
    // than observing it, at the beep cadence the state itself dictates — the
    // audio never needs fresher input than its own period.
    LaunchedEffect(guidanceEnabled, run.plan, run.activeIndex, tracker.isSensorAvailable) {
        if (!guidanceEnabled || !tracker.isSensorAvailable) return@LaunchedEffect
        var lastDwellProgress = 0f
        while (true) {
            val current = alignment
            val complete = coordinator.guidance.value.isComplete
            when {
                // Aim guidance: beep at a rate that rises as the aim closes.
                current.hasDistance && !current.isAligned && !complete -> {
                    val interval = GuidanceProfile.beepIntervalMillis(current.distanceDegrees)
                    if (interval != null) {
                        guidance.beep()
                        delay(interval)
                    } else {
                        delay(250)
                    }
                }

                // Dwell feedback: a tick at the halfway point of the fill, so
                // the imminent shutter is felt before the shutter tick itself.
                current.isAligned && current.dwellProgress > 0f -> {
                    if (GuidanceProfile.crossedDwellMilestone(
                            lastDwellProgress,
                            current.dwellProgress,
                        )
                    ) {
                        guidance.dwellTick()
                    }
                    lastDwellProgress = current.dwellProgress
                    delay(40)
                }

                else -> {
                    lastDwellProgress = 0f
                    delay(150)
                }
            }
        }
    }

    // Spoken announcement per target: "next: above you, to your left". Fires on
    // the hand-over, when the user has just completed a shot and is about to
    // aim somewhere new.
    LaunchedEffect(run.activeIndex, run.plan, guidanceEnabled, tracker.isSensorAvailable) {
        if (!guidanceEnabled || !tracker.isSensorAvailable) return@LaunchedEffect
        // The first target is wherever the user is already facing; announcing
        // it would be noise.
        if (buffer.frames.value.isEmpty()) return@LaunchedEffect
        // The active target can also move because the aim swept across another
        // dot; a short settle means only a target the user stays on is spoken,
        // rather than every marker the reticle passes over.
        delay(600)
        val target = run.plan?.getOrNull(run.activeIndex) ?: return@LaunchedEffect
        val orientation = orientationState.value
        if (!orientation.hasFix) return@LaunchedEffect
        val relation = GuidanceProfile.targetRelation(orientation, target)
        guidance.announce(
            context.getString(
                R.string.guidance_target,
                context.targetPhrase(relation),
            )
        )
    }

    // Bind preview + capture once per lifecycle owner. CameraX unbinds on its own
    // when that lifecycle is destroyed. Re-keyed on [bindAttempt] so a failed
    // bind can be retried without leaving and re-entering the screen.
    LaunchedEffect(lifecycleOwner, previewView, bindAttempt) {
        cameraError = null
        isThreeALocked = false
        hasLockedOnce = false
        val cameraProvider = try {
            context.awaitCameraProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Camera provider unavailable", e)
            cameraError = context.getString(R.string.capture_failed, e.message.orEmpty())
            return@LaunchedEffect
        }

        val profile = resolveSphereCaptureProfile(context, deviceProfile)
        captureProfile = profile

        // Which lens the sphere is shot on, per the device profile. Speed-first
        // profiles take the widest back lens, because a sphere is captured
        // faster the more each frame covers; sharpness-first profiles (the S23
        // among them) take the default back camera, which Android requires to
        // be the primary rear one — the main lens, not the ultrawide. Either
        // way the optics are re-read afterwards from whatever actually bound.
        val cameraSelector = if (deviceProfile.preferWidestCamera) {
            widestCameraSelector(context) ?: CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Exposure, white balance and focus are held for the whole session (see
        // SphereCaptureProfile): a sphere's seams are exposure and colour seams,
        // so no frame is allowed to re-meter on its own. The locks must not sit
        // in the stream's very first request, though — locking AE from frame one
        // freezes the exposure at the HAL's stream-start defaults (a short
        // exposure and low ISO chosen for a bright scene), so a dark scene never
        // brightens and the night viewfinder stays black. The session starts
        // with the 3A running, waits for it to converge on the scene, and locks
        // to the values it settled on (see [lockThreeA]). The stills carry the
        // same locks so a capture fired mid-convergence cannot walk away from
        // what the viewfinder is showing.
        //
        // Both holders are atomic: the convergence callback runs on the camera
        // thread while the bind (and the 6 s timeout fallback) run on the main
        // thread, so a plain local var would be a data race. `camera2Control`
        // also becomes readable before the bind assigns it, so the guard has to
        // survive a null read and let the timeout retry.
        val camera2Control = AtomicReference<Camera2CameraControl?>(null)
        val threeALocked = AtomicBoolean(false)
        // Locks AE and AWB on the repeating request. Deliberately a lambda
        // (rather than a local fun) so the session capture callback below can
        // capture it. Called from the camera thread when the 3A converges, and
        // from the main thread by the timeout fallback; `addCaptureRequestOptions`
        // is safe from either. The options are applied to the session's repeating
        // request — the preview — and to the stills, keeping the viewfinder and
        // every frame on one exposure and colour temperature.
        val lockThreeA = {
            // CAS states the intent: whichever caller wins the flag applies the
            // options once, and a caller that found no control yet leaves the
            // flag clear so the timeout can try again. A failed apply releases
            // the claim so the timeout retries it.
            val control = camera2Control.get()
            if (control != null && threeALocked.compareAndSet(false, true)) {
                runCatching {
                    control.addCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                            .build()
                    )
                }
                    .onSuccess {
                        isThreeALocked = true
                        hasLockedOnce = true
                        Log.i(TAG, "AE/AWB locked")
                    }
                    .onFailure { error ->
                        threeALocked.set(false)
                        Log.w(TAG, "Could not lock 3A", error)
                    }
            }
        }
        val preview = Preview.Builder()
            // Pinned to the stills' shape. Left to itself CameraX picks a
            // preview close to the display's aspect ratio — 16:9 or taller on a
            // modern phone — which off a 4:3 sensor is a *crop*, not a squeeze:
            // it throws away a quarter of one axis. The overlay would then be
            // drawing the field of view of a frame the viewfinder never shows,
            // and the markers would sit where the capture reaches rather than
            // where the user can see. One shape for both keeps a single field of
            // view honest about the preview and the stills at once.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .also { builder ->
                val extender = Camera2Interop.Extender(builder)
                applySphereCaptureOptions(extender, profile)
                // Watch the repeating request for the 3A settling, then lock AE
                // and AWB to the converged values. The exposure ramps up over the
                // first moments in low light, so locking when the HAL reports
                // CONVERGED (or FLASH_REQUIRED, the dark-scene equivalent when
                // flash is off) pins the session to an exposure that is actually
                // bright enough — instead of the stream-start default that left
                // the night viewfinder black. [lockThreeA] is idempotent and
                // null-guarded, so the first settled frame that arrives after the
                // camera control is ready wins and the rest are no-ops.
                extender.setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val state = result.get(CaptureResult.CONTROL_AE_STATE)
                            val settled = state == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                            if (settled) lockThreeA()
                        }
                    }
                )
            }
            .build()
            .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Frames are stitched, so a flash firing on some of them would leave
            // seams no amount of blending can hide.
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            // Capture is capped to what the stitch can use: it reads ~1024 px
            // per frame, so a 50 MP still (the S23's main sensor at full size)
            // is pure waste — it is slower to write, slows the per-target burst,
            // and heats the phone up for nothing. 12 MP 4:3 is several times
            // more resolution than the stitch reads.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(
                                deviceProfile.captureMaxLongEdgePx,
                                deviceProfile.captureMaxLongEdgePx * 3 / 4,
                            ),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setTargetRotation(context.currentDisplayRotation())
            .also { applySphereCaptureOptions(Camera2Interop.Extender(it), profile) }
            .also { applyStillImageOptions(Camera2Interop.Extender(it), profile) }
            .build()

        try {
            cameraProvider.unbindAll()
            val camera = try {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
            } catch (e: Exception) {
                Log.w(TAG, "Widest camera unavailable, binding the default", e)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
            }
            imageCapture = capture
            boundCamera = camera
            // The lock target for the convergence callback: the session's
            // repeating request can only be touched once the camera is bound.
            camera2Control.set(
                runCatching { Camera2CameraControl.from(camera.cameraControl) }.getOrNull()
            )
            camera2ControlState = camera2Control.get()
            // Exposure-compensation range of the bound lens; a 0..0 range
            // means the HAL offers no compensation and the slider stays hidden.
            val evRange = camera.cameraInfo.exposureState.exposureCompensationRange
            exposureRange = evRange.lower..evRange.upper
            exposureIndex = camera.cameraInfo.exposureState.exposureCompensationIndex
            // Re-apply the requested compensation on the fresh session so a
            // rebind does not silently drop a manual brightness adjustment.
            applyExposure(exposureIndex, force = true)
            // The lens that answered, not the one that was asked for: a filter
            // that matched nothing, or a device that substitutes a logical
            // camera, both land here and both would otherwise leave the optics
            // describing a lens the session is not looking through.
            boundCameraId = runCatching {
                Camera2CameraInfo.from(camera.cameraInfo).cameraId
            }.getOrNull()
            // The capture profile is resolved again against the lens that
            // actually bound: when the widest-camera bind fell back to the
            // default, the focus strategy and lock support may describe the
            // other lens (a FIXED_FOCUS guess on a lens that can focus would
            // kill tap-to-focus).
            captureProfile = resolveSphereCaptureProfile(context, deviceProfile, boundCameraId)
            streamAspectRatio = capture.resolutionInfo
                ?.resolution
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width.toFloat() / it.height }
                ?: 0f
            Log.i(TAG, "Bound camera $boundCameraId, stills $streamAspectRatio:1")

            // Watch for the camera being taken away mid-run — another camera
            // app grabbing the lens, the camera service dying, a thermal
            // shutdown. Without this the capture loop would keep arming the
            // shutter against a dead use case: every dwell would fail with a
            // snackbar and nothing would recover. On an error the use case is
            // dropped (which stops the loop and the HUD's capture offer) and
            // the retry card goes up; its retry re-binds, and the buffered
            // frames are untouched, so the run carries on where it was.
            // The observer is tied to the lifecycle owner, so it goes away
            // with the screen.
            camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                state.error?.let { error ->
                    Log.e(TAG, "Camera error ${error.code}, capture disabled", error.cause)
                    imageCapture = null
                    boundCamera = null
                    cameraError = context.getString(R.string.capture_camera_lost)
                }
            }

            // Some HALs never report CONVERGED for a scene; by the timeout the
            // repeating request has long since settled, so locking then is still
            // locking to a real exposure rather than the stream-start default.
            // The callback wins the race on devices that do report convergence.
            scope.launch {
                delay(THREE_A_LOCK_TIMEOUT_MS)
                lockThreeA()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            // Nothing is bound, so there is no viewfinder to put a transient
            // snackbar over — the failure stays on screen with a way out of it.
            imageCapture = null
            boundCamera = null
            cameraError = context.getString(R.string.capture_failed, e.message.orEmpty())
        }
    }

    // CameraX releases the camera when the *lifecycle* is destroyed, which for a
    // single-activity app is not when this screen goes away. Leaving the session
    // bound behind the result screen keeps the sensor streaming and the preview
    // converging — measurable battery and heat for a viewfinder nobody is
    // looking at — so the bind is undone explicitly on the way out.
    DisposableEffect(lifecycleOwner) {
        onDispose {
            imageCapture = null
            boundCamera = null
            // Only if the provider has already resolved: this runs on the main
            // thread, and a future that is still pending has nothing bound to
            // release anyway.
            val future = ProcessCameraProvider.getInstance(context)
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
                    .onFailure { Log.w(TAG, "Could not release the camera", it) }
            }
        }
    }

    // The stills' EXIF rotation has to follow the display. The activity locks
    // portrait on phones, but Android 16+ ignores fixed orientation on large
    // screens, so a tablet can be force-rotated mid-session: the optics and the
    // sensor frame re-read on the configuration change, and this keeps the
    // frames CameraX records carrying the rotation they were actually shot at.
    // (The bind itself is keyed on the lifecycle, not the configuration, so the
    // camera stays bound across the rotation.)
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        imageCapture?.targetRotation = context.currentDisplayRotation()
    }

    // Old sessions are dead weight once a new one starts; clearing them keeps the
    // cache from growing by a sphere's worth of full-resolution JPEGs per run.
    LaunchedEffect(sessionId) {
        buffer.pruneStaleSessions()
    }

    // The capture loop's camera half. The rule that decides whether the shutter
    // fires lives in [SphereCaptureCoordinator]; what is left here is the part
    // that needs an ImageCapture behind it.
    LaunchedEffect(imageCapture) {
        val capture = imageCapture ?: return@LaunchedEffect
        val profile = captureProfile ?: return@LaunchedEffect

        // Until the user taps, focus holds the centre of the first scene the
        // lens saw. AE and AWB are already locked by the session, so this pins
        // the last free variable before any frame is taken; the trigger lands
        // before the first dwell can complete, so it cannot race a shutter. On
        // a fixed-focus lens there is nothing to aim, and tapping is a no-op.
        if (profile.focusMode == FocusMode.FOCUS_POINT) {
            lockFocusAt(0.5f, 0.5f)
        }

        // Collecting suspends across the shutter, which is what keeps a second
        // capture from starting while one is still being written — the
        // StateFlow simply conflates the samples that arrive meanwhile.
        tracker.orientation.collect { orientation ->
            accuracy = orientation.accuracy

            val decision = coordinator.onSample(
                orientation = orientation,
                nowMillis = SystemClock.elapsedRealtime(),
                // A frame landing halfway through a stitch would not be in the
                // set being stitched, and would be deleted when that set is
                // cleared.
                isStitching = stitchJob != null,
                captured = { buffer.frames.value.mapTo(HashSet()) { it.index } },
                // No frame before the session's AE/AWB lock has landed once: a
                // still fired mid-convergence is pinned to whatever exposure
                // has been reached so far — on a dark scene, near black. A
                // later manual unlock (brightness slider) does not re-arm this.
                canTrigger = hasLockedOnce,
                createPlan = { yawNow ->
                    // Rebuilt on the bearing it was originally anchored at
                    // (saved across configuration changes) so a recreation
                    // mid-run keeps the target numbering the buffered frames
                    // were shot against.
                    val startYaw =
                        if (planStartYawDegrees.isNaN()) yawNow else planStartYawDegrees
                    planStartYawDegrees = startYaw
                    SphereTargetPlan.createForFieldOfView(
                        startYawDegrees = startYaw,
                        fieldOfView = currentFieldOfView,
                        scope = captureScope,
                    )
                },
            )

            when (decision) {
                CaptureDecision.Ignore -> Unit

                is CaptureDecision.Guide -> {
                    alignment = decision.alignment
                    isHolding = decision.isHolding
                }

                is CaptureDecision.Capture -> {
                    alignment = decision.alignment
                    // A trigger only ever comes out of an aligned reading.
                    isHolding = true

                    val result = capture.saveFrame(
                        context = context,
                        buffer = buffer,
                        index = decision.index,
                        dwellOrientation = decision.pose,
                        currentOrientation = { tracker.orientation.value },
                        burstPerTarget = deviceProfile.burstPerTarget,
                    )
                    alignment = alignment.copy(isCapturing = false)

                    result
                        .onSuccess {
                            // The token is what makes this safe: a run restarted
                            // or stepped back while the frame was being written
                            // refuses the advance, so the reticle never lands
                            // past a target nothing was shot at. The tick is
                            // confirmation the frame counted, so it follows the
                            // same answer.
                            Log.i(TAG, "Frame ${decision.index} captured")
                            val advanced = coordinator.onCaptured(
                                token = decision.token,
                                captured = buffer.frames.value.mapTo(HashSet()) { it.index },
                                orientation = tracker.orientation.value,
                            )
                            if (advanced) feedback.onFrameCaptured()
                        }
                        .onFailure { error ->
                            // Stays on the same target, so a failed capture is
                            // retried rather than silently skipped.
                            coordinator.onCaptureFailed(decision.token)
                            if (error is SessionSupersededException) {
                                // The user restarted the run while the shutter
                                // was in flight; the frame disappearing is the
                                // expected outcome, not a failure to report.
                                Log.i(TAG, "Frame ${decision.index} dropped with its superseded session")
                                return@onFailure
                            }
                            Log.e(TAG, "Frame ${decision.index} failed", error)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.capture_failed,
                                        error.message.orEmpty(),
                                    )
                                )
                            }
                        }
                }
            }
        }
    }

    // A plan is laid out from the optics, so a change has to throw it away and
    // start again. Only while the session is still empty: re-planning around
    // captured frames would renumber the targets they were shot against. The
    // optics settle as the camera binds, before the first frame in practice.
    // The scope changes do the same thing — and the toggle is disabled once a
    // frame exists, so this is really just the initial layout.
    LaunchedEffect(fieldOfView, captureScope) {
        if (buffer.frames.value.isEmpty()) {
            resetGuidance()
        }
    }

    // Straight from the buffer, so a marker turns green the moment its frame
    // lands rather than on the next sensor sample.
    val capturedIndices = remember(bufferedFrames) { bufferedFrames.mapTo(HashSet()) { it.index } }

    /**
     * Saves every buffered frame as its own gallery photo, after asking.
     *
     * A sphere is dozens of full-resolution JPEGs landing in the gallery at
     * once, so the call site confirms first (see SaveFramesConfirmation).
     */
    fun exportFrames() {
        val files = buffer.frames.value.map { it.file }
        if (files.isEmpty()) return
        Log.i(TAG, "Exporting ${files.size} frames")
        scope.launch {
            MediaExporter.exportFrames(context, files)
                .onSuccess { count ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.capture_frames_saved, count)
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "Frame export failed", error)
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.capture_frames_save_failed, error.message.orEmpty())
                    )
                }
        }
    }

    /**
     * Hands the buffered frames to the stitcher and passes on what comes back.
     *
     * Started lazily so [stitchJob] is set before the body can reach its own
     * `finally` and clear it.
     */
    fun startStitch() {
        if (stitchJob != null) return
        Log.i(TAG, "Stitch started with ${buffer.frames.value.size} frames")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A shutter can be mid-flight when Finish is tapped: the frame it
            // is writing would land in the buffer after the snapshot below,
            // and would be deleted along with the stitched set — a silent
            // coverage hole. Hold until the in-flight capture settles before
            // freezing the frame set. `snapshotFlow` waits on the state change
            // instead of spinning the main dispatcher for the duration of the
            // capture.
            snapshotFlow { alignment.isCapturing }.first { !it }

            // The stitcher works from where each frame was shot, not just its
            // pixels: the capture attitude is what places it on the sphere.
            val frames = buffer.frames.value.map { frame ->
            SphereFrame(
                file = frame.file,
                pose = CameraPose(
                    yawDegrees = frame.yawDegrees,
                    pitchDegrees = frame.pitchDegrees,
                    rollDegrees = frame.rollDegrees,
                    // The measured basis travels alongside the angles so a
                    // frame shot near the zenith keeps its true orientation:
                    // there yaw and roll collapse into each other and the
                    // angles alone cannot place it.
                    matrix = frame.cameraBasis?.let { basis ->
                        DoubleArray(basis.size) { basis[it].toDouble() }
                    },
                ),
            )
        }
        stitchProgress.value = StitchProgress.Preparing

        try {
                PhotoSphereStitcher.stitchPhotos(
                    frames = frames,
                    horizontalFovDegrees = fieldOfView.horizontalDegrees,
                    verticalFovDegrees = fieldOfView.verticalDegrees,
                    radialDistortion = if (distortionEnabled) optics.radialDistortion else null,
                    maxInputDimension = deviceProfile.stitchMaxInputDimension,
                    maxOutputWidth = deviceProfile.stitchMaxOutputWidth,
                    unsharpAmount = deviceProfile.unsharpAmount,
                    // Guided capture is shot standing up and turning on the
                    // spot, so the lens swings on the end of an arm rather than
                    // sitting on the axis. See PivotModel.
                    pivot = deviceProfile.pivot,
                    // The turn CameraX records in each still's EXIF; used to put
                    // a frame back upright when that tag was lost.
                    portraitRotationDegrees = optics.portraitRotationDegrees,
                    useRefinement = refinementEnabled,
                    useSeams = seamEnabled,
                    debugColorFrames = colorFrames,
                    // The canvas region: a full 360° of longitude either way,
                    // and either the whole 180° of latitude (sphere) or the band
                    // a level ring actually covers.
                    longitudeSpanDegrees = 360f,
                    // Centred on the first shot's bearing. The sensor's yaw
                    // zero is arbitrary, so this is what puts the scene the
                    // user started facing in the middle of the photo — where
                    // every 360 viewer opens — and the ±180° wrap directly
                    // behind them. (GPano stays centred at 0: the metadata
                    // describes the image's own columns, not the sensor's.)
                    centerLongitudeDegrees = if (planStartYawDegrees.isNaN()) {
                        0f
                    } else {
                        planStartYawDegrees
                    },
                    latitudeSpanDegrees = latitudeSpanDegrees,
                    centerLatitudeDegrees = 0f,
                ) { stitchProgress.value = it }
                    .onSuccess { sphere ->
                        val stitched = try {
                            withContext(Dispatchers.IO) {
                                SphereImageStore.writeStitchedSphere(
                                    context = context,
                                    bitmap = sphere,
                                    gpano = GPanoMetadata.forSphereRegion(
                                        imageWidth = sphere.width,
                                        imageHeight = sphere.height,
                                        longitudeSpanDegrees = 360f,
                                        centerLongitudeDegrees = 0f,
                                        latitudeSpanDegrees = latitudeSpanDegrees,
                                        centerLatitudeDegrees = 0f,
                                    ),
                                )
                                    .copy(
                                        diagnostics = stitchDiagnostics(
                                            frames,
                                            fieldOfView,
                                            optics,
                                            distortionEnabled,
                                            refinementEnabled,
                                            seamEnabled,
                                        )
                                    )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A full sphere that will not fit on disk. The
                            // frames survive, so a retry after clearing space
                            // costs nothing more than the stitch.
                            Log.e(TAG, "Could not write the stitched sphere", e)
                            null
                        } finally {
                            sphere.recycle()
                        }

                        if (stitched == null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.stitch_failed,
                                    context.getString(R.string.stitch_error_write_failed),
                                )
                            )
                            return@onSuccess
                        }

                        // The frames have done their job. Clearing before the
                        // handover matters: this coroutine is cancelled the
                        // moment the result screen replaces this one.
                        buffer.clear()
                        resetGuidance()
                        onSphereReady(stitched)
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Stitch failed", error)
                        // Frames are deliberately kept: the usual fix is to
                        // capture a few more and try again.
                        snackbarHostState.showSnackbar(context.stitchFailureMessage(error))
                    }
            } finally {
                stitchJob = null
            }
        }
        stitchJob = job
        job.start()
    }

    var showSaveFramesDialog by remember { mutableStateOf(false) }

    val stitchState by stitchProgress.collectAsStateWithLifecycle()
    if (showSaveFramesDialog) {
        SaveFramesConfirmation(
            frameCount = bufferedFrames.size,
            onDismiss = { showSaveFramesDialog = false },
            onConfirm = {
                showSaveFramesDialog = false
                exportFrames()
            },
        )
    }
    if (stitchJob != null) {
        StitchingDialog(
            progress = stitchState,
            onCancel = { stitchJob?.cancel() },
        )
    }

    // Brightness slider: only when a lens is bound and the HAL actually
    // offers a compensation range wider than a point.
    val evRange = exposureRange
    val showExposure = boundCamera != null && evRange != null && evRange.last > evRange.first

    // What the focus badge says. FOCUS_POINT starts as an invitation to tap and
    // settles on "locked" once the first sweep has converged.
    val lockBadge: String? = when (captureProfile?.focusMode) {
        FocusMode.FIXED_FOCUS -> stringResource(R.string.capture_lock_fixed)
        FocusMode.FOCUS_POINT -> when {
            focusReticle == null -> stringResource(R.string.capture_lock_hint)
            focusReticle?.isWorking == true -> stringResource(R.string.capture_lock_focusing)
            else -> stringResource(R.string.capture_lock_locked)
        }
        null -> null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tap-to-focus: a tap anywhere on the viewfinder aims and locks
                // the lens on that part of the scene. The buttons and the
                // instructions card sit on top and consume their own taps.
                .pointerInput(captureProfile) {
                    detectTapGestures { offset ->
                        if (captureProfile?.focusMode == FocusMode.FOCUS_POINT) {
                            lockFocusAt(
                                (offset.x / size.width).coerceIn(0f, 1f),
                                (offset.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )

            TargetOverlay(
                orientation = { orientationState.value },
                alignment = { alignment },
                plan = run.plan,
                activeIndex = run.activeIndex,
                captured = capturedIndices,
                fieldOfView = fieldOfView,
            )

            FocusReticleOverlay(
                focus = focusReticle,
                modifier = Modifier.fillMaxSize(),
            )

            if (showInstructions && run.activeIndex == 0 && bufferedFrames.isEmpty()) {
                CaptureInstructions(
                    onDismiss = { showInstructions = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            CaptureHud(
                capturedCount = run.capturedTargets,
                totalTargets = run.totalTargets,
                completedRings = run.completedRings,
                ringCount = run.ringCount,
                hint = captureHint(
                    isSensorAvailable = tracker.isSensorAvailable,
                    isCameraReady = imageCapture != null,
                    hasPlan = run.plan != null,
                    isComplete = run.isComplete,
                    isHolding = hintHolding,
                    accuracy = accuracy,
                    completedRings = run.completedRings,
                    ringCount = run.ringCount,
                ),
                lockBadge = lockBadge,
                isComplete = run.isComplete,
                captureScope = captureScope,
                // A mode change would renumber the targets already-shot frames
                // were aimed at, so the choice is made before the first frame.
                canChangeScope = bufferedFrames.isEmpty() && stitchJob == null,
                onScopeChange = { captureScope = it },
                guidanceEnabled = guidanceEnabled,
                onToggleGuidance = { guidanceEnabled = !guidanceEnabled },
                // Three overlapping frames are already a panorama. Whether one
                // is worth keeping is the user's call, made on the result
                // screen; the button's job is not to stand between them and it.
                canStitch = bufferedFrames.size >= PhotoSphereStitcher.MIN_FRAMES &&
                    stitchJob == null,
                onFinish = { startStitch() },
                onRestart = {
                    scope.launch {
                        resetGuidance()
                        buffer.cancelSession()
                    }
                },
                // A blurred frame, or one shot while something moved through the
                // scene: undoing pops it off the buffer and puts its target back
                // on the reticle so it can simply be shot again.
                canUndo = bufferedFrames.isNotEmpty() && stitchJob == null,
                // Inert while a shutter is in flight: that frame is not in the
                // buffer yet, so an undo would drop the one before it and then
                // be overwritten when the capture advances the run. The
                // coordinator refuses it regardless — dimming is what makes the
                // refusal visible rather than a tap that does nothing. It stays
                // on screen rather than disappearing, because a control that
                // vanished on every one of thirty-odd captures would read as a
                // glitch.
                undoEnabled = !run.isCapturing,
                onUndo = {
                    scope.launch {
                        val undone = coordinator.undo { buffer.undoLastFrame()?.index }
                        if (undone) {
                            isHolding = false
                            alignment = AlignmentState()
                        }
                    }
                },
                // Debug A/B for the lens model; hidden in release builds.
                distortionEnabled = distortionEnabled,
                onToggleDistortion = { distortionEnabled = !distortionEnabled },
                refinementEnabled = refinementEnabled,
                onToggleRefinement = { refinementEnabled = !refinementEnabled },
                seamEnabled = seamEnabled,
                onToggleSeams = { seamEnabled = !seamEnabled },
                colorFrames = colorFrames,
                onToggleColorFrames = { colorFrames = !colorFrames },
                evIndex = exposureIndex,
                evRange = exposureRange,
                aeLocked = isThreeALocked,
                showExposure = showExposure,
                onEvChange = { applyExposure(it) },
                onToggleAeLock = { setAeLocked(!isThreeALocked) },
                frameCount = bufferedFrames.size,
                canSaveFrames = bufferedFrames.isNotEmpty() && stitchJob == null,
                onSaveFrames = { showSaveFramesDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            )

            // Nothing bound: no preview under it and no targets to guide toward,
            // so this covers the screen rather than sitting alongside a HUD that
            // has nothing to report.
            cameraError?.let { message ->
                CameraErrorCard(
                    message = message,
                    onRetry = { bindAttempt++ },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                )
            }
        }
    }
}

/**
 * What the screen shows when the camera will not start.
 *
 * A bind fails for reasons that pass — another app holding the camera, a
 * provider that had not finished starting, a lens the device withdrew — so the
 * failure is worth stating and worth offering a second go at. Without this the
 * screen keeps a live-looking viewfinder over a camera that never bound, and
 * the only way out is to kill the app.
 */
@Composable
private fun CameraErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(ChromeScrim),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 28.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = SphereSurface,
            tonalElevation = 6.dp,
            shadowElevation = 20.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.capture_camera_unavailable),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SphereOnSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SphereOnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onRetry,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SphereAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_camera_retry),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The capture HUD: a glass progress pill up top, the focus lock chip, the one
 * line of guidance the user needs, and the action that finishes a run.
 *
 * Designed to sit over a live viewfinder, so every surface is translucent dark
 * glass with high-contrast text, and the chrome is limited to what the
 * workflow needs: aim, hold, shoot, and stop once the area is covered.
 */
@Composable
private fun CaptureHud(
    capturedCount: Int,
    totalTargets: Int,
    completedRings: Int,
    ringCount: Int,
    hint: String,
    lockBadge: String?,
    isComplete: Boolean,
    canStitch: Boolean,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
    canUndo: Boolean,
    undoEnabled: Boolean,
    onUndo: () -> Unit,
    captureScope: SphereCaptureScope,
    canChangeScope: Boolean,
    onScopeChange: (SphereCaptureScope) -> Unit,
    guidanceEnabled: Boolean,
    onToggleGuidance: () -> Unit,
    distortionEnabled: Boolean,
    onToggleDistortion: () -> Unit,
    refinementEnabled: Boolean,
    onToggleRefinement: () -> Unit,
    seamEnabled: Boolean,
    onToggleSeams: () -> Unit,
    colorFrames: Boolean,
    onToggleColorFrames: () -> Unit,
    evIndex: Int,
    evRange: IntRange?,
    aeLocked: Boolean,
    showExposure: Boolean,
    onEvChange: (Int) -> Unit,
    onToggleAeLock: () -> Unit,
    frameCount: Int,
    canSaveFrames: Boolean,
    onSaveFrames: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // A sideways screen (Android 16 force-rotating a tablet past the
        // portrait lock) cannot stack the chrome top and bottom — the bottom
        // column would cover the middle of the viewfinder, which is where the
        // user is aiming. The controls move to the right edge instead, and the
        // finishes stay reachable because the stack is tall, not wide.
        val isLandscape = maxWidth > maxHeight

        // Soft gradient washes top and bottom, so the chrome stays legible
        // whatever the lens is pointed at. Both ends need one: the guidance line
        // and the finish button sit over live scene just as the progress pill
        // does, and white-on-bright-sky at the bottom of the frame is exactly as
        // unreadable as it is at the top.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(if (isLandscape) 120.dp else 200.dp)
                .background(Brush.verticalGradient(listOf(ChromeScrim, Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (isLandscape) {
                        Modifier.fillMaxHeight().width(120.dp)
                    } else {
                        Modifier.fillMaxWidth().height(260.dp)
                    }
                )
                .background(
                    if (isLandscape) {
                        Brush.horizontalGradient(listOf(Color.Transparent, ChromeScrim))
                    } else {
                        Brush.verticalGradient(listOf(Color.Transparent, ChromeScrim))
                    }
                ),
        )

        // The StreetView-style undo: drop the last shot and put its target back
        // on the reticle. Sits top-left, clear of the centred progress pill.
        AnimatedVisibility(
            visible = canUndo,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 22.dp),
        ) {
            UndoButton(onUndo = onUndo, enabled = undoEnabled)
        }

        // The sound-guidance toggle: beeps, dwell haptics and spoken target
        // cues for driving capture without sight. Sits top-right, clear of the
        // undo button on the other side of the pill.
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 20.dp, top = 22.dp),
            shape = CircleShape,
            color = if (guidanceEnabled) {
                SphereAccent.copy(alpha = 0.9f)
            } else {
                GlassSurface
            },
            shadowElevation = 8.dp,
        ) {
            IconButton(onClick = onToggleGuidance) {
                Icon(
                    imageVector = if (guidanceEnabled) {
                        Icons.Filled.VolumeUp
                    } else {
                        Icons.Filled.VolumeOff
                    },
                    contentDescription = stringResource(
                        if (guidanceEnabled) {
                            R.string.guidance_toggle_hide
                        } else {
                            R.string.guidance_toggle_show
                        }
                    ),
                    tint = if (guidanceEnabled) Color.Black else GlassContent,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptureScopeSelector(
                scope = captureScope,
                enabled = canChangeScope,
                onScopeChange = onScopeChange,
            )
            CaptureProgressPill(
                capturedCount = capturedCount,
                totalTargets = totalTargets,
                completedRings = completedRings,
                ringCount = ringCount,
            )
            if (lockBadge != null) {
                FocusLockChip(text = lockBadge)
            }
        }

        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (isLandscape) {
                        // A capped width so the finish button stays thumb-sized
                        // instead of stretching across half a tablet.
                        Modifier.fillMaxHeight().widthIn(max = 360.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showExposure && evRange != null) {
                ExposureRow(
                    evIndex = evIndex,
                    evRange = evRange,
                    aeLocked = aeLocked,
                    onEvChange = onEvChange,
                    onToggleAeLock = onToggleAeLock,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HintCard(text = hint)
            // Debug-only A/B control for the lens model: a device that reports
            // LENS_DISTORTION in the wrong convention warps every frame, and
            // re-stitching the same frames as a pinhole isolates it.
            if (BuildConfig.DEBUG && canStitch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onToggleDistortion) {
                        Text(
                            text = "Dist: ${if (distortionEnabled) "on" else "off"}",
                            color = GlassContentDim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onToggleRefinement) {
                        Text(
                            text = "Refine: ${if (refinementEnabled) "on" else "off"}",
                            color = GlassContentDim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onToggleSeams) {
                        Text(
                            text = "Seam: ${if (seamEnabled) "on" else "off"}",
                            color = GlassContentDim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onToggleColorFrames) {
                        Text(
                            text = "Color: ${if (colorFrames) "on" else "off"}",
                            color = GlassContentDim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            // Offered as soon as there is enough to stitch, not only at the
            // end: a user who has covered what they care about should not have
            // to walk the remaining targets to get a sphere out of it.
            //
            // It arrives on an animation rather than appearing between frames.
            // The moment the third frame lands is the moment the run stops being
            // an all-or-nothing walk and becomes something the user can end
            // whenever they like — that is worth a beat of motion to notice,
            // where a button materialising under a thumb is just a mis-tap
            // waiting to happen.
            AnimatedVisibility(
                visible = canSaveFrames,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TextButton(onClick = onSaveFrames) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = SphereActive,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.capture_save_frames, frameCount),
                        color = GlassContent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            AnimatedVisibility(
                visible = canStitch,
                enter = fadeIn() + expandVertically() + scaleIn(initialScale = 0.94f),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Button(
                    onClick = onFinish,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SphereAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_finish_stitch),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            AnimatedVisibility(
                visible = isComplete,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TextButton(onClick = onRestart) {
                    Text(
                        text = stringResource(R.string.capture_restart),
                        color = GlassContent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Live brightness: exposure-compensation slider plus the AE lock toggle.
 *
 * Dragging the slider unlocks AE (a locked AE ignores compensation changes on
 * most HALs) and requests the new index; the button re-pins the converged
 * values so the rest of the run keeps one exposure.
 */
@Composable
private fun ExposureRow(
    evIndex: Int,
    evRange: IntRange,
    aeLocked: Boolean,
    onEvChange: (Int) -> Unit,
    onToggleAeLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = GlassSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.exposure_brightness),
                style = MaterialTheme.typography.labelMedium,
                color = GlassContentDim,
            )
            Slider(
                value = evIndex.toFloat(),
                onValueChange = { onEvChange(it.roundToInt()) },
                valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                steps = (evRange.last - evRange.first - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.exposure_ev_value, evIndex),
                style = MaterialTheme.typography.labelMedium,
                color = GlassContent,
            )
            TextButton(onClick = onToggleAeLock) {
                Text(
                    text = stringResource(
                        if (aeLocked) R.string.exposure_unlock else R.string.exposure_lock
                    ),
                    color = GlassContent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * The choice of how much of the sphere the run walks: the whole thing, or just
 * the horizon ring. A small two-way pill above the progress readout; dimmed and
 * inert once a frame has been captured, because re-planning under buffered
 * frames would renumber the targets they were shot against.
 */
@Composable
private fun CaptureScopeSelector(
    scope: SphereCaptureScope,
    enabled: Boolean,
    onScopeChange: (SphereCaptureScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One track holding two segments, rather than two free-floating pills: the
    // choice is exclusive, and a shared trough is what says so before the label
    // is even read.
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(GlassSurfaceDim)
            .padding(3.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SphereCaptureScope.entries.forEach { option ->
            val selected = option == scope
            Surface(
                shape = PillShape,
                color = if (selected) SphereAccent else Color.Transparent,
                // Passed to the Surface rather than checked inside onClick. The
                // old form left a disabled segment fully clickable as far as the
                // framework was concerned: it took the ripple, and TalkBack
                // announced an actionable button that silently did nothing once
                // the first frame had locked the choice in.
                enabled = enabled,
                selected = selected,
                onClick = { onScopeChange(option) },
            ) {
                Text(
                    text = when (option) {
                        SphereCaptureScope.Sphere -> stringResource(R.string.capture_scope_sphere)
                        SphereCaptureScope.Ring -> stringResource(R.string.capture_scope_ring)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        selected -> Color.Black
                        enabled -> GlassContent
                        else -> GlassContentDim
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * The translucent pill that reports how much of the sphere is covered.
 *
 * [ringCount] is shown only when the plan has more than one band, so a
 * single-band run reads as a plain frame count rather than "band 0 of 1".
 */
@Composable
private fun CaptureProgressPill(
    capturedCount: Int,
    totalTargets: Int,
    completedRings: Int,
    ringCount: Int,
    modifier: Modifier = Modifier,
) {
    // Spoken as one sentence. Left to itself the pill hands a screen reader
    // "12", "/ 48", "frames", a bare progress bar and then "1 of 3 bands" as
    // five unrelated announcements — the one number that matters during a
    // capture, arriving as rubble.
    val spoken = pluralStringResource(
        R.plurals.capture_progress_description,
        capturedCount,
        capturedCount,
        totalTargets,
    )
    val spokenWithBands = if (ringCount > 1) {
        spoken + ", " + pluralStringResource(
            R.plurals.capture_rings,
            completedRings,
            completedRings,
            ringCount,
        )
    } else {
        spoken
    }

    // The bar animates to each new value instead of stepping. A frame landing is
    // the one moment of feedback in a capture the user is not watching the screen
    // for, and a bar that slides is visible in peripheral vision where a jump is
    // not.
    val fraction by animateFloatAsState(
        targetValue = if (totalTargets > 0) capturedCount.toFloat() / totalTargets else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "capture-progress",
    )

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = spokenWithBands },
        shape = PillShape,
        color = GlassSurface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The count is the hero of the HUD — it is what the user glances
            // down at between targets — so it is set at display weight with the
            // total trailing it as quiet metadata rather than as its equal.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "$capturedCount",
                    style = MaterialTheme.typography.displaySmall,
                    color = GlassContent,
                )
                Text(
                    text = "/ $totalTargets",
                    style = MaterialTheme.typography.titleSmall,
                    color = GlassContentDim,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.capture_progress_frames,
                        capturedCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassContentDim,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            if (totalTargets > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.8f)
                        .height(3.dp)
                        .clip(PillShape),
                    color = SphereAccent,
                    trackColor = GlassContent.copy(alpha = 0.22f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            if (ringCount > 1) {
                Text(
                    text = pluralStringResource(
                        R.plurals.capture_rings,
                        completedRings,
                        completedRings,
                        ringCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassContentDim,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}

/**
 * The StreetView-style "undo that shot" control: a small glass button that
 * drops the most recently captured frame and puts its target back on the
 * reticle. Only appears once there is something to undo.
 *
 * Dimmed and inert while a shutter is in flight — the frame being written is
 * not in the buffer yet, so there is a moment where "the last shot" is not the
 * one the user means.
 */
@Composable
private fun UndoButton(
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (enabled) GlassSurface else GlassSurfaceDim,
        shadowElevation = 8.dp,
    ) {
        IconButton(onClick = onUndo, enabled = enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.capture_undo),
                tint = if (enabled) GlassContent else GlassContentDim,
            )
        }
    }
}

/** The small status chip that says what the lens is doing. */
@Composable
private fun FocusLockChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = GlassSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 13.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(SphereAccent),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = GlassContent.copy(alpha = 0.88f),
            )
        }
    }
}

/**
 * The single line of guidance, in a glass card over the bottom of the frame.
 *
 * The text crossfades rather than swapping. This one line is the app's entire
 * running commentary — searching, holding, band covered, compass unreliable —
 * and a hard cut between two similar-length sentences at arm's length is easy
 * to miss entirely. A short dissolve is what makes "something just changed"
 * register without the user having to be reading it at that instant.
 */
@Composable
private fun HintCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = GlassSurface,
        shadowElevation = 6.dp,
    ) {
        Crossfade(
            targetState = text,
            animationSpec = tween(durationMillis = 220),
            label = "capture-hint",
        ) { current ->
            Text(
                text = current,
                style = MaterialTheme.typography.bodyLarge,
                color = GlassContent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
            )
        }
    }
}

/**
 * The one-shot welcome shown before the first frame.
 *
 * A dimmed overlay with a floating card, rather than a modal dialog: it sits
 * over the viewfinder without taking focus or pausing the camera, and a single
 * tap anywhere dismisses it for the session. The steps are the whole workflow
 * — stay put, aim and hold, tap to focus, cover the scene — so the user never
 * has to learn a control to shoot a sphere.
 */
@Composable
private fun CaptureInstructions(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, animationSpec = tween(280, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .alpha(appear.value)
            .background(Color.Black.copy(alpha = 0.5f * appear.value))
            // Tapping anywhere dismisses the card and starts capture.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = 0.92f + 0.08f * appear.value
                    scaleY = 0.92f + 0.08f * appear.value
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = SphereSurface,
            tonalElevation = 6.dp,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_instructions_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = GlassContent,
                    )
                    Text(
                        text = stringResource(R.string.capture_instructions_subtitle),
                        style = MaterialTheme.typography.labelLarge,
                        color = SphereAccent,
                        letterSpacing = 2.sp,
                    )
                }

                listOf(
                    1 to (R.string.capture_step_1 to R.string.capture_instructions_1),
                    2 to (R.string.capture_step_2 to R.string.capture_instructions_2),
                    3 to (R.string.capture_step_3 to R.string.capture_instructions_3),
                    4 to (R.string.capture_step_4 to R.string.capture_instructions_4),
                ).forEach { (number, step) ->
                    val (label, detail) = step
                    InstructionStep(
                        number = number,
                        label = stringResource(label),
                        detail = stringResource(detail),
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SphereAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_instructions_dismiss),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** One numbered step of the welcome card. */
@Composable
private fun InstructionStep(
    number: Int,
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SphereAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SphereAccent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = GlassContent,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = GlassContent.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Confirms exporting every buffered frame as its own gallery photo.
 *
 * Dozens of full-resolution JPEGs landing in the gallery at once is storage
 * and clutter worth a caution icon and an exact count — not a silent dump.
 * The attitude EXIF stamped at capture travels with each frame.
 */
@Composable
private fun SaveFramesConfirmation(
    frameCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = SphereActive,
            )
        },
        title = { Text(text = stringResource(R.string.capture_save_frames_title)) },
        text = {
            Text(text = stringResource(R.string.capture_save_frames_message, frameCount))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.capture_save_frames_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.capture_save_frames_cancel))
            }
        },
    )
}

/**
 * Blocks the screen while OpenCV works.
 *
 * Deliberately modal and not dismissable by tapping outside: the stitch owns the
 * frames for its duration, so the capture loop is paused behind it and there is
 * nothing useful to go back to. Cancelling is offered explicitly, and takes
 * effect at the next stage boundary — the native call cannot be interrupted
 * partway.
 */
@Composable
private fun StitchingDialog(
    progress: StitchProgress,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // The indicator is the subject here rather than a decoration
                // beside the text: a stitch is minutes of a blocked screen, and
                // the one thing the user wants from it is visible evidence that
                // it is still moving.
                val fraction = progress.fraction
                Box(contentAlignment = Alignment.Center) {
                    // A full-circle track behind the sweep, so an early stage at
                    // 4% still reads as a ring rather than as a stray tick.
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(72.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                    if (fraction != null) {
                        // Animated so the ring sweeps between stages instead of
                        // teleporting: the stages are coarse, and a jump from
                        // "reading" to "blending" looks like a glitch.
                        val animated by animateFloatAsState(
                            targetValue = fraction,
                            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                            label = "stitch-progress",
                        )
                        CircularProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.size(72.dp),
                            color = SphereAccent,
                            strokeWidth = 5.dp,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                        )
                        Text(
                            text = "${(animated * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        // Everything inside OpenCV's stitch call is opaque, so the
                        // spinner spins rather than reporting a made-up percentage.
                        CircularProgressIndicator(
                            modifier = Modifier.size(72.dp),
                            color = SphereAccent,
                            strokeWidth = 5.dp,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.stitch_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Crossfade(
                        targetState = progress.label(),
                        animationSpec = tween(durationMillis = 220),
                        label = "stitch-stage",
                    ) { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.stitch_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The one line describing what the stitcher is currently doing. */
@Composable
private fun StitchProgress.label(): String = when (stage) {
    StitchStage.Preparing -> stringResource(R.string.stitch_stage_preparing)
    StitchStage.Reading -> stringResource(R.string.stitch_stage_reading, completed, total)
    StitchStage.Refining -> stringResource(R.string.stitch_stage_refining, completed, total)
    StitchStage.Seaming -> stringResource(R.string.stitch_stage_seaming)
    StitchStage.Stitching -> stringResource(R.string.stitch_stage_stitching)
    StitchStage.Projecting -> stringResource(R.string.stitch_stage_projecting)
}

/** Turns a failed stitch into something worth showing a user. */
private fun Context.stitchFailureMessage(error: Throwable): String {
    val status = (error as? StitchException)?.status ?: StitchStatus.Unknown
    val reason = when (status) {
        StitchStatus.NeedMoreImages,
        StitchStatus.NoInputImages,
        -> getString(R.string.stitch_error_need_more_images)

        StitchStatus.AlignmentFailed -> getString(R.string.stitch_error_alignment)
        StitchStatus.CameraEstimationFailed -> getString(R.string.stitch_error_camera_estimation)
        StitchStatus.OpenCvUnavailable -> getString(R.string.stitch_error_opencv_unavailable)
        StitchStatus.UnreadableInput -> getString(R.string.stitch_error_unreadable_input)
        StitchStatus.OutOfMemory -> getString(R.string.stitch_error_out_of_memory)
        StitchStatus.EmptyResult,
        StitchStatus.Unknown,
        StitchStatus.Ok,
        -> getString(R.string.stitch_error_unknown, status.code)
    }
    return getString(R.string.stitch_failed, reason)
}

/** Picks the single most useful thing to tell the user right now. */
@Composable
private fun captureHint(    isSensorAvailable: Boolean,
    isCameraReady: Boolean,
    hasPlan: Boolean,
    isComplete: Boolean,
    isHolding: Boolean,
    accuracy: OrientationAccuracy,
    completedRings: Int,
    ringCount: Int,
): String = when {
    !isSensorAvailable -> stringResource(R.string.capture_orientation_unavailable)
    !isCameraReady -> stringResource(R.string.capture_starting)
    isComplete -> stringResource(R.string.capture_sphere_complete)
    !hasPlan -> stringResource(R.string.capture_waiting_for_orientation)
    // The shutter is held only at Unreliable, so that case says so plainly.
    // Low still shoots, but the compass is worth calibrating, so it is offered
    // as advice rather than as an explanation for a stalled capture.
    !accuracy.allowsCapture -> stringResource(R.string.capture_accuracy_blocked)
    !accuracy.isUsable && accuracy != OrientationAccuracy.Unknown ->
        stringResource(R.string.capture_low_accuracy)
    isHolding -> stringResource(R.string.capture_hint_hold)
    // A closed ring is a complete band of sphere and a perfectly good result.
    // Saying so is what turns "keep going or lose it" into a real choice.
    completedRings > 0 -> stringResource(R.string.capture_hint_ring_done, completedRings, ringCount)
    else -> stringResource(R.string.capture_hint_search)
}

/**
 * Debug-only summary of what a stitch was built from, for the result screen.
 *
 * The pose column is what decides whether frames land where they were shot:
 * yaw should sweep across a capture, pitch should follow the rings, and roll
 * should stay small when the phone is held level. When a sphere comes out
 * scrambled this is the first place to look.
 *
 * The EXIF column is the other half of the puzzle: the stitch decodes each
 * JPEG upright via its `ORIENTATION` tag, and a frame that lost that tag
 * decodes landscape and lands rotated 90° against its pose.
 */
private fun stitchDiagnostics(
    frames: List<SphereFrame>,
    fieldOfView: FieldOfView,
    optics: SphereOptics,
    distortionApplied: Boolean,
    refinementApplied: Boolean,
    seamsApplied: Boolean,
): String {
    val poses = frames.mapIndexed { index, frame ->
        val exif = runCatching {
            ExifInterface(frame.file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        "  #$index yaw ${frame.pose.yawDegrees.roundToInt()}° " +
            "pitch ${frame.pose.pitchDegrees.roundToInt()}° roll ${frame.pose.rollDegrees.roundToInt()}°" +
            " exif=$exif"
    }.joinToString(separator = "\n")
    val device = listOfNotNull(
        Build.MANUFACTURER,
        Build.MODEL,
    ).joinToString(" ")
    val distortion = optics.radialDistortion
        ?.coefficients
        ?.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
        ?: "none"
    return "Device: $device\n" +
        "FOV: ${fieldOfView.horizontalDegrees.roundToInt()}° x " +
        "${fieldOfView.verticalDegrees.roundToInt()}° (screen) " +
        "rotation=${optics.portraitRotationDegrees}°\n" +
        "Distortion k: $distortion ${if (distortionApplied) "applied" else "OFF (pinhole)"}\n" +
        "Refinement: ${if (refinementApplied) "on" else "off (sensor poses)"}\n" +
        "Seams: ${if (seamsApplied) "on (carved)" else "off (blend)"}\n" +
        "Frames (capture order):\n$poses"}

/** Writes one frame to the session's cache directory and buffers it.
 *
 * The device's attitude at the moment of capture goes into the buffer entry and
 * into the file's EXIF: the stitcher gets a free initial guess at where each
 * frame belongs, which is worth far more than the couple of milliseconds it
 * costs here.
 *
 * With [burstPerTarget] > 1 the target is shot [burstPerTarget] times at the
 * same locked settings and the sharpest of the burst is kept. The losers are
 * deleted in [ImageBufferManager.commitBestFrame], so the session still holds
 * one file per index. A frame halfway through its burst that fails leaves the
 * reserved candidates behind; they are deleted here so the session directory
 * stays clean.
 */
private suspend fun ImageCapture.saveFrame(
    context: Context,
    buffer: ImageBufferManager,
    index: Int,
    dwellOrientation: OrientationData,
    currentOrientation: () -> OrientationData,
    burstPerTarget: Int,
): Result<File> {
    val requests = try {
        if (burstPerTarget > 1) {
            buffer.reserveBurstFrames(index, burstPerTarget)
        } else {
            listOf(buffer.reserveFrame(index))
        }
    } catch (e: Exception) {
        return Result.failure(e)
    }

    return try {
        // Each shot keeps the attitude the phone had at *its* shutter. A burst
        // of max-quality stills takes seconds, and the dwell mean describes
        // only the moment before the first one: a hand that eases off after
        // the first flash would otherwise put the second or third shot on the
        // sphere where the first was aimed — a misplaced frame no blend hides.
        val shotOrientations = requests.map { request ->
            var atShutter: OrientationData? = null
            takePictureTo(context, request.outputOptions) {
                atShutter = currentOrientation()
            }
            shotPose(dwellOrientation, atShutter ?: currentOrientation())
        }

        val bestIndex = if (burstPerTarget > 1) {
            // Scoring decodes each candidate, which is cheap next to the captures
            // that just ran but is still real work — keep it off the main thread.
            withContext(Dispatchers.Default) {
                SharpnessSelection.sharpestIndex(requests.map { it.file })
            }
        } else {
            0
        }

        // A null commit means the frame belonged to a session that has since
        // been cancelled (Restart while the shutter was in flight) and was
        // dropped with its file. Reporting it as success would advance the
        // plan past a target that was never captured, leaving a hole in the
        // sphere; the failure path leaves the index in place for a retry.
        val committed = buffer.commitBestFrame(
            requests[bestIndex].file,
            index,
            shotOrientations[bestIndex],
        )
            ?: return Result.failure(SessionSupersededException())
        Result.success(committed.file)
    } catch (e: CancellationException) {
        // Leaving the screen mid-shutter is not a capture failure; let the
        // cancellation travel rather than reporting it to the user. The
        // reserved candidates still need clearing — a cancelled burst would
        // otherwise litter the session directory until the next prune.
        withContext(NonCancellable + Dispatchers.IO) { deleteReserved(requests) }
        throw e
    } catch (e: Exception) {
        // Half a burst written, none of it buffered: clear the candidates so the
        // session directory does not accumulate rejected frames.
        withContext(NonCancellable + Dispatchers.IO) { deleteReserved(requests) }
        Result.failure(e)
    }
}

/** Deletes the reserved files of a burst that never committed. */
private suspend fun deleteReserved(requests: List<SphereImageStore.TempFrameRequest>) {
    requests.forEach { request ->
        if (request.file.exists() && !request.file.delete()) {
            Log.w(TAG, "Could not delete failed burst file ${request.file.name}")
        }
    }
}

/**
 * Aims and locks autofocus at a normalised point in the preview, awaiting
 * convergence.
 *
 * This is tap-to-focus: AE and AWB are already held by the session request, so
 * the trigger only moves focus. After the trigger completes in `AF_MODE_AUTO`
 * the lens stays where it is until another trigger comes along — the same lock
 * a regular camera's tap-to-focus leaves behind, and the thing that keeps a
 * sphere's frames on one focal plane.
 *
 * @return false when the preview is not laid out yet and no sweep could run.
 */
private suspend fun Camera.focusAt(
    nx: Float,
    ny: Float,
    previewView: PreviewView,
): Boolean {
    if (previewView.width <= 0 || previewView.height <= 0) return false
    val point = previewView.meteringPointFactory.createPoint(
        previewView.width * nx.coerceIn(0f, 1f),
        previewView.height * ny.coerceIn(0f, 1f),
    )
    val future = cameraControl.startFocusAndMetering(
        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build(),
    )
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                // A failed focus sweep is not worth failing the session over; the
                // lens just stays where auto left it.
                runCatching { future.get() }
                continuation.resume(Unit)
            },
            ContextCompat.getMainExecutor(previewView.context),
        )
    }
    return true
}

/**
 * How far (in degrees of rotation) a shot may sit from its dwell's mean pose and
 * still be stamped with that mean.
 *
 * Inside it, the shot is where the dwell said it was, and the mean of twenty
 * samples is a quieter estimate than the one sample at the shutter. Past it the
 * phone has genuinely moved, and the shutter's own sample is the only honest
 * answer.
 */
private const val SHOT_POSE_TOLERANCE_DEGREES = 0.4

/** The pose to stamp on a shot: see [SHOT_POSE_TOLERANCE_DEGREES]. */
private fun shotPose(dwell: OrientationData, atShutter: OrientationData): OrientationData {
    if (!atShutter.hasFix) return dwell
    val a = dwell.cameraBasis ?: return atShutter
    val b = atShutter.cameraBasis ?: return atShutter
    // The angle of the relative rotation aᵀb, from its trace — which is just
    // the element-wise dot product of the two matrices.
    var trace = 0.0
    for (i in 0 until 9) trace += a[i].toDouble() * b[i]
    val cosAngle = ((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)
    val degrees = Math.toDegrees(acos(cosAngle))
    return if (degrees <= SHOT_POSE_TOLERANCE_DEGREES) dwell else atShutter
}

/**
 * Suspending [ImageCapture.takePicture]; the callback form fits nothing here.
 *
 * [onCaptureStarted] runs on the main thread at the moment the sensor begins
 * the exposure — the instant whose attitude the frame records.
 */
private suspend fun ImageCapture.takePictureTo(
    context: Context,
    outputOptions: ImageCapture.OutputFileOptions,
    onCaptureStarted: () -> Unit = {},
): ImageCapture.OutputFileResults = suspendCancellableCoroutine { continuation ->
    takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                onCaptureStarted()
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                continuation.resume(output)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        },
    )
}

/**
 * A [CameraSelector] for the widest physical back camera, or null when it
 * cannot be identified.
 *
 * CameraX filters the available cameras down to the one with the matching
 * camera2 id, so the preview and stills bind to the same lens
 * [widestBackCameraId] picks for the optics. A filter that matches nothing
 * makes [ProcessCameraProvider.bindToLifecycle] throw, which the caller turns
 * into a fall back to the default back camera.
 */
private fun widestCameraSelector(context: Context): CameraSelector? {
    val id = widestBackCameraId(context) ?: return null
    return CameraSelector.Builder()
        .addCameraFilter { cameras ->
            cameras.filter { camera ->
                runCatching { Camera2CameraInfo.from(camera).cameraId }.getOrNull() == id
            }
        }
        .build()
}

/**
 * Suspending wrapper around [ProcessCameraProvider.getInstance].
 *
 * The provider arrives as a `ListenableFuture`; bridging it here keeps the call
 * site free of callbacks without a coroutines-guava dependency.
 */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

/** True when a screen reader (TalkBack and friends) is active. */
private fun Context.isScreenReaderActive(): Boolean = runCatching {
    Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
}.getOrDefault(false)

/** "behind you, above you, to your left" — the spoken direction of a target. */
private fun Context.targetPhrase(relation: TargetRelation): String {
    val parts = buildList {
        if (relation.isBehind) add(getString(R.string.guidance_behind))
        when (relation.vertical) {
            TargetRelation.Vertical.Above -> add(getString(R.string.guidance_above))
            TargetRelation.Vertical.Below -> add(getString(R.string.guidance_below))
            TargetRelation.Vertical.Level -> {}
        }
        when (relation.horizontal) {
            TargetRelation.Horizontal.Left -> add(getString(R.string.guidance_left))
            TargetRelation.Horizontal.Right -> add(getString(R.string.guidance_right))
            TargetRelation.Horizontal.Centre -> {}
        }
    }
    return parts.joinToString(", ")
}
