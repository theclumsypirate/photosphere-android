package com.n30dyn4m1c.photosphere.result

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.util.Logging
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.sampleSizeFor
import com.n30dyn4m1c.photosphere.ui.theme.PhotoWell
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PanoramaResult"

/**
 * Long edge the preview is decoded down to.
 *
 * A 4096×2048 sphere is 32 MB decoded, on a screen that is a tenth of that wide.
 * 1440 keeps the flat preview sharp on any phone display for about 4 MB.
 */
private const val PREVIEW_MAX_DIMENSION = 1440

/** What has happened to the gallery export so far. */
private sealed interface ExportState {
    /** Not asked for yet. */
    data object Idle : ExportState

    /** Copying into the gallery. */
    data object Working : ExportState

    /** Published; [displayName] is the file name it landed under. */
    data class Done(val displayName: String) : ExportState
}

/** How the finished sphere is shown: the honest flat frame, or the pano view. */
private enum class PreviewMode { Flat, Panorama }

/**
 * What the user sees when a stitch finishes: the sphere, and what to do with it.
 *
 * The photo exists as a GPano-tagged JPEG in the app's cache by the time this
 * screen appears — nothing has been published yet. That is deliberate: a run
 * that came out badly should not have to be deleted out of the camera roll
 * afterwards. From here it can go to the gallery, out through the share sheet,
 * or nowhere at all.
 *
 * The preview defaults to the flat equirectangular frame, which is the honest
 * picture of what was captured: the black wedges at the poles are the parts of
 * the sphere the run never reached, and they are worth seeing before deciding
 * to keep it. A toggle switches to a pannable inside-out sphere view for
 * inspecting seams and the horizon where they actually matter.
 *
 * @param sphere the finished photo, as [SphereImageStore.writeStitchedSphere] left it
 * @param onTakeAnother discards this sphere and returns to capture
 */
@Composable
fun PanoramaResultScreen(
    sphere: StitchedSphere,
    onTakeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var preview by remember(sphere.file) { mutableStateOf<ImageBitmap?>(null) }
    var isPreviewFailed by remember(sphere.file) { mutableStateOf(false) }
    var exportState by remember(sphere.file) { mutableStateOf<ExportState>(ExportState.Idle) }
    var previewMode by remember(sphere.file) { mutableStateOf(PreviewMode.Flat) }
    // Decoded lazily the first time the user switches to the pano view: the GL
    // texture is a few megabytes and the flat preview is the default.
    var panoBitmap by remember(sphere.file) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(sphere.file) {
        val decoded = withContext(Dispatchers.IO) { decodePreview(sphere.file) }
        if (decoded == null) {
            isPreviewFailed = true
            Log.w(TAG, "Could not decode a preview of ${sphere.file.name}")
        } else {
            // The previous preview is dead the moment this one lands; recycle it
            // rather than letting ~4 MB of bitmap wait for GC on a low-memory
            // device with several result screens behind it.
            preview?.asAndroidBitmap()?.recycle()
            preview = decoded.asImageBitmap()
        }
    }

    LaunchedEffect(sphere.file, previewMode) {
        if (previewMode == PreviewMode.Panorama && panoBitmap == null) {
            val decoded = withContext(Dispatchers.IO) {
                decodePreview(sphere.file, PANO_TEXTURE_MAX_DIMENSION)
            }
            if (decoded != null) {
                panoBitmap?.recycle()
                panoBitmap = decoded
            }
        }
    }

    // The bitmaps are the screen's own scratch; hand them back when it goes.
    DisposableEffect(sphere.file) {
        onDispose {
            preview?.asAndroidBitmap()?.recycle()
            panoBitmap?.recycle()
        }
    }

    // Leaving discards the sphere, and the file is only in the cache — so if it
    // has not been exported, "back" is a delete. Both routes out ask first; see
    // [DiscardConfirmation].
    var isConfirmingDiscard by remember(sphere.file) { mutableStateOf(false) }
    val isSaved = exportState is ExportState.Done
    val isWorking = exportState is ExportState.Working

    /**
     * Leaves the screen, pausing to confirm if the photo would be lost.
     *
     * While an export is in flight the copy cannot be cancelled (it is
     * [NonCancellable] once the MediaStore row exists), so leaving now would
     * contradict the discard prompt: the photo would land in the gallery after
     * the user confirmed its destruction, with the success feedback swallowed.
     * The exit waits for the copy instead.
     */
    fun leave() {
        if (isWorking) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.result_export_in_progress)
                )
            }
            return
        }
        if (isSaved) onTakeAnother() else isConfirmingDiscard = true
    }

    // Back means "I'm done with this one" — the same thing the button does.
    // Without this, back would leave the activity with a sphere still cached.
    BackHandler(onBack = ::leave)

    if (isConfirmingDiscard) {
        DiscardConfirmation(
            onDismiss = { isConfirmingDiscard = false },
            onDiscard = {
                isConfirmingDiscard = false
                onTakeAnother()
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The header is deliberately compact — a badge, a title, a line of
            // metadata. Everything above the photograph is space taken from the
            // photograph, and on this screen the photograph is the reason the
            // user is here.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Surface(
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.result_badge),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.result_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.result_dimensions, sphere.width, sphere.height),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (BuildConfig.DEBUG && sphere.diagnostics != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            text = sphere.diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            PreviewModeSelector(
                mode = previewMode,
                onModeChange = { previewMode = it },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            SpherePreview(
                preview = preview,
                panoBitmap = panoBitmap,
                mode = previewMode,
                isFailed = isPreviewFailed,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ResultActions(
                exportState = exportState,
                onExport = {
                    exportState = ExportState.Working
                    Log.i(Logging.TAG, "PanoramaResultScreen: export started")
                    scope.launch {
                        val result = MediaExporter.export(
                            context = context,
                            source = sphere.file,
                            width = sphere.width,
                            height = sphere.height,
                        )
                        result
                            .onSuccess { exported ->
                                Log.i(Logging.TAG, "PanoramaResultScreen: exported ${exported.displayName}")
                                exportState = ExportState.Done(exported.displayName)
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.result_export_success,
                                        exported.relativePath,
                                    )
                                )
                            }
                            .onFailure { error ->
                                Log.e(Logging.TAG, "PanoramaResultScreen: export failed", error)
                                exportState = ExportState.Idle
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.result_export_failed,
                                        error.message.orEmpty(),
                                    )
                                )
                            }
                    }
                },
                onShare = {
                    if (!context.shareSphere(sphere.file)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.result_share_failed)
                            )
                        }
                    }
                },
                onTakeAnother = ::leave,
            )
        }
    }
}

/**
 * Asks before throwing away a sphere that only exists in the cache.
 *
 * Starting a new capture — or pressing back — deletes this one, and until it has
 * been exported the cached JPEG is the only copy there is. That is minutes of
 * standing in one place turning on the spot, undone by one tap on a button
 * sitting directly beside "Share". Once the photo has been saved to the gallery
 * the question stops being worth asking, and this never appears.
 */
@Composable
private fun DiscardConfirmation(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = { Text(stringResource(R.string.result_discard_title)) },
        text = { Text(stringResource(R.string.result_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.result_discard_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.result_discard_cancel))
            }
        },
    )
}

/** Flat frame or pannable sphere: the two ways of looking at the result. */
@Composable
private fun PreviewModeSelector(
    mode: PreviewMode,
    onModeChange: (PreviewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same exclusive segmented pill the capture screen uses for its scope
    // choice: Flat is the default — the honest overview — and Panorama is the
    // inspection tool.
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PreviewMode.entries.forEach { option ->
            val selected = option == mode
            Surface(
                shape = PillShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                onClick = { onModeChange(option) },
            ) {
                Text(
                    text = stringResource(
                        when (option) {
                            PreviewMode.Flat -> R.string.result_view_flat
                            PreviewMode.Panorama -> R.string.result_view_panorama
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The preview well: either the equirectangular frame letterboxed into the
 * space, or the pannable sphere view of the same pixels.
 */
@Composable
private fun SpherePreview(
    preview: ImageBitmap?,
    panoBitmap: Bitmap?,
    mode: PreviewMode,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // Clipped as well as filled, so the image inside takes the rounded
            // corners rather than painting over them.
            .clip(MaterialTheme.shapes.medium)
            // A well darker than the surface around it, with a hairline to
            // define the edge: an equirectangular frame has black wedges at the
            // poles wherever the run did not reach, and without a border those
            // wedges bleed into the background and the photo appears to have no
            // edges at all.
            .background(PhotoWell)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            mode == PreviewMode.Panorama && panoBitmap != null -> PanoramaSphereView(
                bitmap = panoBitmap,
                contentDescription = stringResource(R.string.result_view_panorama_description),
                modifier = Modifier.fillMaxSize(),
            )

            preview != null -> Image(
                bitmap = preview,
                contentDescription = stringResource(R.string.result_preview_description),
                modifier = Modifier.fillMaxSize(),
                // Fit, not Crop: the whole 2:1 frame is the point, including the
                // uncovered poles.
                contentScale = ContentScale.Fit,
            )

            isFailed -> Text(
                text = stringResource(R.string.result_preview_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Export, share, and start again. */
@Composable
private fun ResultActions(
    exportState: ExportState,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onTakeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExported = exportState is ExportState.Done
    val isWorking = exportState is ExportState.Working

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Save is the one action with consequences, so it gets the full width,
        // the filled treatment and a thumb-sized target; share and discard sit
        // below it as equals. The hierarchy is the recommendation.
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = PillShape,
            // Disabled once it has landed rather than hidden: "Saved to gallery"
            // is the answer to "did that work?", and re-tapping would only file a
            // second copy.
            enabled = !isWorking && !isExported,
            colors = ButtonDefaults.buttonColors(
                // A landed export keeps the accent instead of greying out. It is
                // disabled because the work is done, not because it is
                // unavailable, and a dimmed control reads as the latter.
                disabledContainerColor = if (isExported) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                disabledContentColor = if (isExported) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            ),
            onClick = onExport,
        ) {
            when {
                isWorking -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )

                isExported -> Icon(Icons.Filled.Check, contentDescription = null)
                else -> Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            }
            Text(
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                text = when {
                    isWorking -> stringResource(R.string.result_exporting)
                    isExported -> stringResource(R.string.result_exported)
                    else -> stringResource(R.string.result_export)
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = PillShape,
                enabled = !isWorking,
                onClick = onShare,
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.result_share),
                )
            }
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = PillShape,
                // Held back during an export: the sphere it is copying from is
                // the file this button deletes.
                enabled = !isWorking,
                onClick = onTakeAnother,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.result_take_another),
                )
            }
        }

        if (exportState is ExportState.Done) {
            Text(
                text = stringResource(
                    R.string.result_export_location,
                    MediaExporter.RELATIVE_PATH,
                    exportState.displayName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Hands the sphere to the system share sheet.
 *
 * The JPEG goes out as it is, GPano and all, so a receiving app that understands
 * 360 photos gets one. It travels as a [FileProvider] URI with a read grant
 * attached — the cache directory is private, and a `file://` URI would trip
 * `FileUriExposedException` on anything since API 24.
 *
 * Returns false if the device has nothing that can receive an image.
 */
private fun Context.shareSphere(file: File): Boolean {
    val uri = try {
        SphereImageStore.shareUri(this, file)
    } catch (e: IllegalArgumentException) {
        // Thrown when the file sits outside every path in file_paths.xml.
        Log.e(TAG, "No FileProvider path covers ${file.name}", e)
        return false
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Some targets read the grant off the ClipData rather than the extra.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return try {
        startActivity(
            Intent.createChooser(send, getString(R.string.result_share))
                // Started from a Context that may not be an Activity.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing on this device can receive an image", e)
        false
    }
}

/** Decodes [file] down to something a phone screen can hold. */
private fun decodePreview(file: File, maxDimension: Int = PREVIEW_MAX_DIMENSION): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    return BitmapFactory.decodeFile(file.path, options)
}
