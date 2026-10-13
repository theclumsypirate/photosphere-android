package com.n30dyn4m1c.photosphere.importer
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile
import com.n30dyn4m1c.photosphere.camera.rememberSphereOptics
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.stitching.StitchStage
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = Logging.TAG

/** How many photos the system picker may return at once. */
private const val MAX_PICK = 30

/** `UserComment` form SphereImageStore stamps capture attitudes in. */
private val ATTITUDE_RE =
    Regex("""yaw=(-?\d+(?:\.\d+)?);pitch=(-?\d+(?:\.\d+)?);roll=(-?\d+(?:\.\d+)?)""")

/**
 * Stitches photos already on the device: gallery series, or frames this app
 * saved earlier through "Save frames".
 *
 * Poses come from the `UserComment` EXIF this app stamps at capture when every
 * picked photo has one; otherwise the picks are laid on a horizon ring in pick
 * order, spaced by the lens's overlap step — the honest assumption for an
 * ordered left-to-right series, and what the pose refinement (when enabled)
 * corrects from there. Mixed sets always take the ring: half a run of stamped
 * poses plus half a run of guesses is neither.
 *
 * The stitch itself is the same [PhotoSphereStitcher] capture uses; only the
 * pixels' origin differs. A foreign lens's distortion is unknown, so none is
 * applied.
 */
@Composable
fun ImportPhotosScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val optics = rememberSphereOptics()
    val deviceProfile = remember { SphereDeviceProfile.forDevice() }

    var picked by remember { mutableStateOf<List<File>>(emptyList()) }
    var posesStamped by remember { mutableStateOf(false) }
    var importDir by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stitchJob by remember { mutableStateOf<Job?>(null) }
    // Progress travels as a flow: the stitcher reports from a worker thread,
    // and Compose state must not be written from there.
    val stitchProgress = remember { MutableStateFlow<StitchProgress?>(null) }
    val progress by stitchProgress.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Picked ${uris.size} photos")
            val dir = File(File(context.cacheDir, "imports"), "import_${System.currentTimeMillis()}")
            dir.mkdirs()
            importDir?.let { old -> if (old != dir) old.deleteRecursively() }
            val files = uris.mapIndexedNotNull { index, uri ->
                copyUri(context, uri, File(dir, "import_%02d.jpg".format(index)))
            }
            if (files.size < PhotoSphereStitcher.MIN_STITCHABLE_FRAMES) {
                error = context.getString(R.string.import_need_more)
                return@launch
            }
            error = null
            picked = files
            posesStamped = files.all { readStampedPose(it) != null }
            importDir = dir
        }
    }

    fun startImportStitch() {
        val files = picked
        if (stitchJob != null || files.size < PhotoSphereStitcher.MIN_STITCHABLE_FRAMES) return
        stitchProgress.value = StitchProgress.Preparing
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val fieldOfView = optics.fieldOfView
            Log.i(TAG, "Import stitch started with ${files.size} photos")
            try {
                val stamped = files.map { readStampedPose(it) }
                val useStamped = stamped.all { it != null }
                Log.i(TAG, "Import poses: ${if (useStamped) "stamped EXIF" else "even ring"}")
                val poses = if (useStamped) {
                    stamped.requireNoNulls()
                } else {
                    fallbackRingPoses(files.size, fieldOfView.horizontalDegrees)
                }
                val frames = files.mapIndexed { index, file ->
                    val pose = poses[index]
                    SphereFrame(
                        file = file,
                        pose = CameraPose(
                            yawDegrees = pose.yawDegrees,
                            pitchDegrees = pose.pitchDegrees,
                            rollDegrees = pose.rollDegrees,
                        ),
                    )
                }
                PhotoSphereStitcher.stitchPhotos(
                    frames = frames,
                    horizontalFovDegrees = fieldOfView.horizontalDegrees,
                    verticalFovDegrees = fieldOfView.verticalDegrees,
                    radialDistortion = null,
                    maxInputDimension = deviceProfile.stitchMaxInputDimension,
                    maxOutputWidth = deviceProfile.stitchMaxOutputWidth,
                    unsharpAmount = deviceProfile.unsharpAmount,
                    portraitRotationDegrees = optics.portraitRotationDegrees,
                    onProgress = { stitchProgress.value = it },
                ).onSuccess { bitmap ->
                    val stitched = try {
                        withContext(Dispatchers.IO) {
                            SphereImageStore.writeStitchedSphere(
                                context = context,
                                bitmap = bitmap,
                            ).copy(
                                diagnostics = "Import: ${files.size} photos\n" +
                                    "Poses: ${if (useStamped) "stamped EXIF" else "even ring"}\n" +
                                    "FOV: ${fieldOfView.horizontalDegrees}° x " +
                                    "${fieldOfView.verticalDegrees}° (screen)",
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not write the imported sphere", e)
                        null
                    } finally {
                        bitmap.recycle()
                    }
                    if (stitched == null) {
                        error = context.getString(
                            R.string.stitch_failed,
                            context.getString(R.string.stitch_error_write_failed),
                        )
                        return@onSuccess
                    }
                    Log.i(TAG, "Import stitch done")
                    onSphereReady(stitched)
                }.onFailure { failure ->
                    Log.e(TAG, "Import stitch failed", failure)
                    error = context.getString(
                        R.string.import_failed,
                        failure.message.orEmpty(),
                    )
                }
            } finally {
                stitchJob = null
            }
        }
        stitchJob = job
        job.start()
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
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                stitchJob != null && progress != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = progress.stageLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { stitchJob?.cancel() }) {
                        Text(text = stringResource(R.string.stitch_cancel))
                    }
                }

                picked.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.import_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.import_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Button(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        shape = PillShape,
                        modifier = Modifier
                            .padding(top = 28.dp)
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.import_choose),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                        Text(text = stringResource(R.string.import_back))
                    }
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.import_ready,
                            picked.size,
                            stringResource(
                                if (posesStamped) {
                                    R.string.import_poses_stamped
                                } else {
                                    R.string.import_poses_ring
                                }
                            ),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Button(
                        onClick = { startImportStitch() },
                        shape = PillShape,
                        modifier = Modifier
                            .padding(top = 28.dp)
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.import_stitch, picked.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    TextButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.import_change))
                    }
                    TextButton(onClick = onBack) {
                        Text(text = stringResource(R.string.import_back))
                    }
                }
            }
        }
    }
}

/** The one line describing what the import stitch is currently doing. */
@Composable
private fun StitchProgress.stageLabel(): String = when (stage) {
    StitchStage.Preparing -> stringResource(R.string.stitch_stage_preparing)
    StitchStage.Reading -> stringResource(R.string.stitch_stage_reading, completed, total)
    StitchStage.Refining -> stringResource(R.string.stitch_stage_refining, completed, total)
    StitchStage.Seaming -> stringResource(R.string.stitch_stage_seaming)
    StitchStage.Stitching -> stringResource(R.string.stitch_stage_stitching)
    StitchStage.Projecting -> stringResource(R.string.stitch_stage_projecting)
}

/** Copies a picked photo into the import directory, or null when unreadable. */
private fun copyUri(context: Context, uri: Uri, dest: File): File? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IOException("Could not open $uri")
    dest
}.onFailure { error ->
    Log.w(TAG, "Could not copy $uri", error)
}.getOrNull()

/** This app's stamped attitude from a frame it saved, or null for foreign photos. */
private fun readStampedPose(file: File): CameraPose? {
    val comment = runCatching {
        ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT)
    }.getOrNull() ?: return null
    val match = ATTITUDE_RE.find(comment) ?: return null
    val (yaw, pitch, roll) = match.destructured
    return runCatching {
        CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
    }.getOrNull()
}

/**
 * Poses for an ordered left-to-right series: a horizon ring spaced by the
 * lens's overlap step, centred on 0°.
 */
private fun fallbackRingPoses(count: Int, horizontalFovDegrees: Float): List<CameraPose> {
    val step = horizontalFovDegrees * 0.65f
    return List(count) { index ->
        CameraPose(
            yawDegrees = (index - (count - 1) / 2f) * step,
            pitchDegrees = 0f,
            rollDegrees = 0f,
        )
    }
}
