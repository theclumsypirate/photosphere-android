package com.n30dyn4m1c.photosphere.orbit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Swipe through the finished walk, one angle per page.
 *
 * Drag side to side to turn the subject around. "Save all" files every
 * angle to the gallery; "Stitch 360" sews the walk into one continuous
 * turntable photo with the main stitching pipeline; "New walk" throws
 * the set away and starts over.
 *
 * @param onNewWalk discards this set and returns to the orbit camera.
 * @param onStitched hands a finished orbit sphere to the result screen.
 */
@Composable
fun OrbitViewerScreen(
    files: List<File>,
    onNewWalk: () -> Unit,
    onStitched: (StitchedSphere) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf<Int?>(null) }
    var stitching by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { files.size })

    // System back returns to the camera for another walk.
    BackHandler(onBack = onNewWalk)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Drag to turn around",
                style = MaterialTheme.typography.titleMedium,
            )
            Box(modifier = Modifier.weight(1f)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val bitmap by produceState<Bitmap?>(initialValue = null, files[page]) {
                        value = withContext(Dispatchers.IO) {
                            decodeUpright(files[page], MAX_PREVIEW_EDGE)
                        }
                    }
                    val image = bitmap
                    if (image == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "Orbit angle ${page + 1} of ${files.size}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Text(
                text = "Angle ${pagerState.currentPage + 1} of ${files.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onNewWalk) { Text("New walk") }
                Button(
                    enabled = !saving && !stitching,
                    onClick = {
                        stitching = true
                        scope.launch {
                            runCatching {
                                OrbitStitch.stitch(context, files)
                            }.onSuccess { sphere ->
                                Logging.i("Orbit stitched ${sphere.width}x${sphere.height}")
                                onStitched(sphere)
                            }.onFailure { error ->
                                Logging.e("Orbit stitch failed", error)
                                snackbarHostState.showSnackbar(
                                    "Could not stitch: ${error.message.orEmpty()}",
                                )
                            }
                            stitching = false
                        }
                    },
                ) {
                    Text(if (stitching) "Stitching…" else "Stitch 360")
                }
                Button(
                    enabled = !saving && savedCount == null,
                    onClick = {
                        saving = true
                        scope.launch {
                            MediaExporter.exportFrames(context, files)
                                .onSuccess { count ->
                                    savedCount = count
                                    Logging.i("Orbit saved $count angles")
                                }
                                .onFailure { error ->
                                    Logging.e("Orbit save failed", error)
                                    snackbarHostState.showSnackbar(
                                        "Could not save: ${error.message.orEmpty()}",
                                    )
                                }
                            saving = false
                        }
                    },
                ) {
                    Text(
                        when {
                            saving -> "Saving…"
                            savedCount != null -> "Saved $savedCount"
                            else -> "Save all"
                        },
                    )
                }
            }
        }
    }
}

/**
 * Decodes [file] scaled so its long edge fits [maxEdge], rotated upright
 * per its EXIF tag. Screen-size bitmap, never the full 50 MP.
 */
fun decodeUpright(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = BitmapFactory.decodeFile(file.path, options) ?: return null
    val rotation = runCatching {
        ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (rotation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return raw
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

private const val MAX_PREVIEW_EDGE = 1600
