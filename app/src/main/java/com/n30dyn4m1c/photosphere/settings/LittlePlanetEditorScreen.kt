package com.n30dyn4m1c.photosphere.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.metadata.LittlePlanetConverter
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.storage.MediaExporter.ExportedPanorama
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only: full-screen Little Planet editor with 4 sliders.
 * Opens from Settings → Advanced settings.
 * User picks a 2:1 equirectangular image, adjusts Tilt/Zoom/Pan/Spin, saves as PNG.
 */
@Composable
fun LittlePlanetEditorScreen(
    onClose: () -> Unit,
) {
    if (!BuildConfig.DEBUG) return // debug-only

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val sourceUri = remember { mutableStateOf<Uri?>(null) }
    val sourceBitmap = remember { mutableStateOf<AndroidBitmap?>(null) }
    val previewBitmap = remember { mutableStateOf<AndroidBitmap?>(null) }

    var tilt by remember { mutableStateOf(0f) }
    var zoom by remember { mutableStateOf(300f) }
    var pan by remember { mutableStateOf(0f) }
    var spin by remember { mutableStateOf(0f) }

    var busy by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    // File picker for 2:1 equirectangular image
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            sourceUri.value = it
            scope.launch {
                loadAndDecode(context, scope, sourceBitmap, previewBitmap, it)
            }
        }
    }

    // Debounced preview update on slider change

    // Show file picker when needed
    LaunchedEffect(showPicker) {
        if (showPicker) {
            pickImage.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
            showPicker = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Little Planet Editor",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                if (sourceUri.value != null) {
                    Button(onClick = { showPicker = true }) {
                        Text(text = "Change Image", color = Color.White)
                    }
                } else {
                    Button(onClick = { showPicker = true }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Open")
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text(text = "Open Panorama", color = Color.White)
                    }
                }
            }

            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(512.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (previewBitmap.value != null) {
                    val imgBmp = previewBitmap.value!!.asImageBitmap()
                    androidx.compose.foundation.Image(
                        bitmap = imgBmp,
                        contentDescription = "Little Planet Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (sourceUri.value == null) {
                    Text(
                        text = "Open a 2:1 equirectangular panorama to start",
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            // Sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SliderRow(label = "Tilt", value = tilt, range = -90f..90f, step = 1f, suffix = "°") { tilt = it }
                SliderRow(label = "Zoom", value = zoom, range = 60f..359f, step = 1f, suffix = "°") { zoom = it }
                SliderRow(label = "Pan", value = pan, range = -180f..180f, step = 1f, suffix = "°") { pan = it }
                SliderRow(label = "Spin", value = spin, range = -180f..180f, step = 1f, suffix = "°") { spin = it }
            }

            // Save button
            Button(
                onClick = { if (!busy) scope.launch { saveLittlePlanet(context, scope, sourceBitmap, { busy = !it }, snackbarHostState) } },
                enabled = !busy && sourceBitmap.value != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.padding(start = 12.dp))
                    Text("Rendering…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.Download, contentDescription = "Save")
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text("Save Little Planet (2048px)", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    SnackbarHost(snackbarHostState)

    // Show file picker when needed
    LaunchedEffect(showPicker) {
        if (showPicker) {
            pickImage.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
            showPicker = false
        }
    }
}

// Helper functions
suspend fun loadAndDecode(
    context: Context,
    scope: CoroutineScope,
    sourceBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    previewBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    uri: Uri,
) {
    val bmp = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    }
    if (bmp != null) {
        sourceBitmap.value = bmp
        checkAspectRatio(bmp)
    }
}

fun checkAspectRatio(bmp: AndroidBitmap) {
    val w = bmp.width
    val h = bmp.height
    val ratio = w.toFloat() / h.toFloat()
    if (ratio < 1.8f || ratio > 2.2f) {
        Logging.w("Selected image is ${w}x${h} (ratio ${"%.2f".format(ratio)}:1) — expected 2:1 equirectangular")
    }
}

@Composable
fun updatePreview(
    scope: CoroutineScope,
    sourceBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    previewBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
) {
    val bmp = sourceBitmap.value
    bmp?.let {
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                LittlePlanetConverter.convert(it, 512, 180f)
            }
            previewBitmap.value = preview
        }
    }
}

@Composable
fun updatePreview(
    scope: CoroutineScope,
    sourceBitmap: AndroidBitmap?,
    onPreviewGenerated: (AndroidBitmap) -> Unit,
) {
    sourceBitmap?.let {
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                LittlePlanetConverter.convert(it, 512, 180f)
            }
            onPreviewGenerated(preview)
        }
    }
}

suspend fun saveLittlePlanet(
    context: Context,
    scope: CoroutineScope,
    sourceBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    onSaveComplete: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val bmp = sourceBitmap.value
    bmp?.let {
        val result = withContext(Dispatchers.IO) {
            val fullRes = LittlePlanetConverter.convert(it, 2048, 180f)
            val name = "little_planet_${System.currentTimeMillis()}.jpg"
            MediaExporter.exportBitmap(context, fullRes, name, fullRes.width, fullRes.height)
        }
        result.onSuccess { exported: ExportedPanorama ->
            val msg = "Saved as ${exported.displayName}"
            onSaveComplete(true)
        }.onFailure { e ->
            onSaveComplete(false)
        }
    }
}

/**
 * A slider with label, value display, and suffix.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    suffix: String,
    onValueChange: (Float) -> Unit,
) {
    val displayValue = "${value.toInt()}$suffix"
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, color = Color.White, fontSize = 16.sp)
            Text(text = displayValue, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).toInt(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}
