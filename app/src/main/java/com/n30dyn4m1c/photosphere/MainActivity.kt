package com.n30dyn4m1c.photosphere

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.n30dyn4m1c.photosphere.camera.PhotoSphereCameraScreen
import com.n30dyn4m1c.photosphere.importer.ImportPhotosScreen
import com.n30dyn4m1c.photosphere.result.PanoramaResultScreen
import com.n30dyn4m1c.photosphere.sensor.OrientationDebugScreen
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(Logging.TAG, "MainActivity.onCreate")
        // Both bars stay transparent over the viewfinder, and both are forced to
        // *light* icons. The default follows the system's day/night setting,
        // which on a phone in light mode paints dark status-bar icons — over a
        // black viewfinder, or a night scene, that is a clock nobody can read.
        // The app's own scheme is dark in every configuration (see
        // PhotoSphereTheme), so the bars are pinned to match it rather than the
        // system.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PhotoSphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PhotoSphereApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(Logging.TAG, "MainActivity.onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d(Logging.TAG, "MainActivity.onResume")
    }

    override fun onPause() {
        Log.d(Logging.TAG, "MainActivity.onPause")
        super.onPause()
    }

    override fun onStop() {
        Log.d(Logging.TAG, "MainActivity.onStop")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(Logging.TAG, "MainActivity.onDestroy")
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(Logging.TAG, "MainActivity.onLowMemory: memorie redusa")
    }

    companion object {
        /**
         * Runtime (dangerous) permissions this app needs.
         *
         * `HIGH_SAMPLING_RATE_SENSORS` is deliberately absent: it is a *normal*
         * permission granted at install time, so requesting it at runtime always
         * fails. Declaring it in the manifest is all that is required.
         *
         * Storage is only requested on API 28 and below. From API 29 the app
         * writes through MediaStore, which needs no permission for its own media.
         */
        val REQUIRED_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}

/**
 * Where a run starts: live capture, or photos already on the device.
 *
 * Import needs no camera permission (the system picker grants per-photo
 * access), so it sits beside the permission gate rather than behind it.
 */
private enum class Entry {
    Capture,
    Import,
}

/**
 * The whole app: capture, then what to do with what came out.
 *
 * There are only two destinations, so this is a `when` over one piece of state
 * rather than a navigation graph. The sphere held here is the app's single
 * source of truth for "there is a finished photo waiting" — non-null puts the
 * result screen up, and clearing it deletes the cached JPEG and returns to
 * capture with a fresh buffer.
 *
 * The camera screen is behind the permission gate; the result screen and the
 * import screen are not separately gated, since the only way to reach them is
 * through a capture that already passed or through photos the user picked.
 */
@Composable
private fun PhotoSphereApp(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    // The orientation readout needs no permissions, so it sits beside the
    // permission gate rather than behind it.
    var showOrientationDebug by rememberSaveable { mutableStateOf(false) }

    // Capture or import: chosen on the entry screen, before any permission.
    var entry by rememberSaveable { mutableStateOf<Entry?>(null) }

    // Deliberately not `rememberSaveable`: on a configuration change the file is
    // still in the cache, but a File is not something to smuggle through a
    // Bundle, and re-showing a stale result would be worse than starting over.
    var sphere by remember { mutableStateOf<StitchedSphere?>(null) }

    /** Throws away the finished sphere and goes back to capture. */
    fun discardSphere() {
        val finished = sphere ?: return
        sphere = null
        // NonCancellable: this runs as the result screen is being torn down, and
        // a several-megabyte JPEG left in the cache is exactly what the next
        // stitch would have to clean up.
        scope.launch(Dispatchers.IO + NonCancellable) {
            SphereImageStore.deleteCachedSphere(finished.file)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val finished = sphere
        when {
            showOrientationDebug -> OrientationDebugScreen()

            finished != null -> PanoramaResultScreen(
                sphere = finished,
                onTakeAnother = { discardSphere() },
            )

            entry == null -> EntryChooser(
                onCapture = { entry = Entry.Capture },
                onImport = { entry = Entry.Import },
            )

            entry == Entry.Import -> ImportPhotosScreen(
                onSphereReady = { sphere = it },
                onBack = { entry = null },
            )

            else -> RequirePermissions(
                permissions = MainActivity.REQUIRED_PERMISSIONS,
            ) {
                PhotoSphereCameraScreen(onSphereReady = { sphere = it })
            }
        }

        // Debug-only entry point. BuildConfig.DEBUG is a compile time constant,
        // so R8 drops this from release builds.
        if (BuildConfig.DEBUG) {
            FilledTonalButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(12.dp),
                onClick = { showOrientationDebug = !showOrientationDebug },
            ) {
                Text(
                    stringResource(
                        if (showOrientationDebug) {
                            R.string.orientation_debug_close
                        } else {
                            R.string.orientation_debug_open
                        }
                    )
                )
            }
        }
    }
}

/**
 * The first screen: capture with the camera, or stitch photos already on the
 * device. Two full-width buttons, no permission needed to choose.
 */
@Composable
private fun EntryChooser(
    onCapture: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.entry_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onCapture,
            shape = PillShape,
            modifier = Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.entry_capture),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        FilledTonalButton(
            onClick = onImport,
            shape = PillShape,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.entry_import),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Where the user currently stands with a set of runtime permissions. */
internal enum class PermissionStatus {
    /** Not asked yet, or the system will show the dialog on the next request. */
    Unknown,

    /** Every permission in the set is granted. */
    Granted,

    /** Denied once; the system will still show the dialog, so explain why first. */
    ShowRationale,

    /** Denied with "don't ask again" (or blocked by policy) — only Settings helps. */
    PermanentlyDenied,
}

/**
 * The verdict after a runtime-permission request returns.
 *
 * Extracted from the launcher callback so the state machine can be pinned by
 * unit tests. The decision deliberately reads the *grants*, not the result
 * map, and treats an empty map as a cancellation, not a grant:
 *
 * - Asked of the system, not inferred from `results`. When the dialog is
 *   dismissed without an answer — a swipe away, a phone call taking the
 *   foreground, the screen locking — the contract delivers an *empty* map, and
 *   `emptyMap().values.all { }` is vacuously true. Read straight from the map,
 *   that cancellation would have counted as a grant and dropped the user into
 *   a camera screen with no camera permission, where the bind fails and the
 *   viewfinder is simply black.
 * - A real denial the system will still show a dialog for, or that same
 *   cancellation, both land in [PermissionStatus.ShowRationale]. The retry is
 *   what matters: `shouldShowRationale` reads false after a cancel, so trusting
 *   it here would send someone who never answered the dialog off to the
 *   Settings app to fix a setting they had not touched. Only an explicit
 *   "deny" that the system will no longer surface a dialog for earns that trip.
 */
internal fun resolvePermissionStatus(
    results: Map<String, Boolean>,
    hasAll: Boolean,
    anyShouldShowRationale: Boolean,
): PermissionStatus = when {
    hasAll -> PermissionStatus.Granted
    results.isEmpty() -> PermissionStatus.ShowRationale
    anyShouldShowRationale -> PermissionStatus.ShowRationale
    else -> PermissionStatus.PermanentlyDenied
}

/**
 * Gates [content] behind the given runtime permissions.
 *
 * The request is fired once when the gate first appears. If the user denies it,
 * a rationale with a retry button is shown; if the OS will no longer surface the
 * dialog, the button deep-links into the app's settings page instead. Returning
 * from Settings re-checks the grant, so the UI recovers without a restart.
 */
@Composable
private fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()

    var status by remember {
        mutableStateOf(
            if (context.hasAllPermissions(permissions)) {
                PermissionStatus.Granted
            } else {
                PermissionStatus.Unknown
            }
        )
    }
    // Saveable so an activity recreation (font scale, dark mode, foldable
    // resize) does not re-fire the system dialog at a user who already answered
    // it — a permanently-denied user would get a silent no-op request, and a
    // merely-denied one an unprompted dialog.
    var hasRequested by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        status = resolvePermissionStatus(
            results = results,
            hasAll = context.hasAllPermissions(permissions),
            anyShouldShowRationale = activity != null &&
                permissions.any(activity::shouldShowRationale),
        )
    }

    // Ask once, automatically, the first time the gate is shown.
    LaunchedEffect(permissions) {
        if (status != PermissionStatus.Granted && !hasRequested) {
            hasRequested = true
            launcher.launch(permissions.toTypedArray())
        }
    }

    // Re-check on resume: the user may have granted the permission in Settings.
    LifecycleResumeEffect(permissions) {
        if (context.hasAllPermissions(permissions)) {
            status = PermissionStatus.Granted
        }
        onPauseOrDispose { }
    }

    when (status) {
        PermissionStatus.Granted -> content()

        PermissionStatus.Unknown -> PermissionMessage(
            modifier = modifier,
            message = stringResource(R.string.permission_camera_rationale),
            actionLabel = null,
            onAction = null,
        )

        PermissionStatus.ShowRationale -> PermissionMessage(
            modifier = modifier,
            message = stringResource(R.string.permission_camera_rationale),
            actionLabel = stringResource(R.string.permission_grant),
            onAction = { launcher.launch(permissions.toTypedArray()) },
        )

        PermissionStatus.PermanentlyDenied -> PermissionMessage(
            modifier = modifier,
            message = stringResource(R.string.permission_camera_denied_permanently),
            actionLabel = stringResource(R.string.permission_open_settings),
            onAction = { context.openAppSettings() },
        )
    }
}

/**
 * The permission gate's own screen.
 *
 * This is the app's front door — for a first-time user it is the whole app
 * until they say yes — so it is composed rather than dumped: the lens glyph
 * gives the request a subject, the title carries the ask, and the body explains
 * why a camera app that never uploads anything still needs the camera. The
 * action, when there is one, is full-width at the bottom where a thumb is.
 */
@Composable
private fun PermissionMessage(
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // A ring around the lens glyph, echoing the capture reticle the user
            // is about to spend the next few minutes aiming.
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }

            Text(
                modifier = Modifier.padding(top = 28.dp),
                text = stringResource(R.string.permission_camera_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = PillShape,
                    onClick = onAction,
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private fun Context.hasAllPermissions(permissions: List<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

private fun Activity.shouldShowRationale(permission: String): Boolean =
    shouldShowRequestPermissionRationale(permission)

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
