package com.n30dyn4m1c.photosphere.settings

import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.metadata.LittlePlanetConverter
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.storage.MediaExporter.ExportedPanorama
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen Little Planet editor with native photo import.
 * Opens from Settings → Advanced settings → Convert Panorama to Little Planet.
 * Uses system photo picker (PickVisualMedia) like ImportPhotosScreen.
 */
@Composable
fun LittlePlanetEditorScreen(
    onBack: () -> Unit,
) {
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
    val notEquirect = remember { mutableStateOf(false) }

    // Native photo picker like ImportPhotosScreen
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            sourceUri.value = uri
            previewBitmap.value = null
            notEquirect.value = false
            scope.launch { loadAndDecodeLittlePlanet(context, sourceBitmap, uri, notEquirect) }
        }
    }

    // Auto-generate preview when source bitmap changes
    LaunchedEffect(sourceBitmap.value) {
        val bmp = sourceBitmap.value
        if (bmp != null && previewBitmap.value == null && !busy) {
            generatePreview(scope, bmp, previewBitmap)
        }
    }

    // Update preview when sliders change (debounced)
    LaunchedEffect(key1 = listOf(tilt, zoom, pan, spin), block = {
        val bmp = sourceBitmap.value
        if (bmp != null && !busy) {
            generatePreview(scope, bmp, previewBitmap)
        }
    })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BackHandler { onBack() }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Little Planet Editor",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                // Spacer for centering
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(48.dp))
            }

            // Persistent warning when the upload is not a 2:1 360 photo
            if (notEquirect.value) {
                Text(
                    text = "Not a 2:1 photo, this is not a 360 pano",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
            }

            // Preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val preview = previewBitmap.value
                val source = sourceBitmap.value
                when {
                    preview != null -> {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "Little Planet Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    busy && source != null -> {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                    source != null -> {
                        Image(
                            bitmap = source.asImageBitmap(),
                            contentDescription = "Selected 360 Panorama",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        Text(
                            text = "Import a 2:1 equirectangular panorama to start",
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            // Sliders
            if (sourceBitmap.value != null) {
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
            }

            // Import button (when no image) or Change Image button
            if (!busy && sourceBitmap.value == null) {
                Button(
                    onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text("Import Panorama")
                }
            }

            // Save button (when image loaded)
            if (sourceBitmap.value != null) {
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                saveLittlePlanet(context, sourceBitmap, tilt, zoom, pan, spin, snackbarHostState)
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
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

            // Change Image button
            if (sourceBitmap.value != null && !busy) {
                Button(
                    onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = !busy,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .height(44.dp),
                ) {
                    Text("Change Image")
                }
            }
        }
    }

    SnackbarHost(hostState = snackbarHostState)
}

/**
 * Generates a preview at 512px with current slider values.
 */
private fun generatePreview(
    scope: CoroutineScope,
    source: AndroidBitmap,
    previewBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
) {
    scope.launch {
        val preview = withContext(Dispatchers.IO) {
            LittlePlanetConverter.convert(
                source,
                512,
                180.0f,
            )
        }
        previewBitmap.value = preview
    }
}

/**
 * Saves the little planet at 2048px.
 */
private suspend fun saveLittlePlanet(
    context: Context,
    sourceBitmap: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    tilt: Float,
    zoom: Float,
    pan: Float,
    spin: Float,
    snackbarHostState: SnackbarHostState,
) {
    val bmp = sourceBitmap.value ?: return
    val result = withContext(Dispatchers.IO) {
        val fullRes = LittlePlanetConverter.convert(
            bmp,
            2048,
            180.0f,
        )
        val name = "little_planet_${System.currentTimeMillis()}.jpg"
        MediaExporter.exportBitmap(context, fullRes, name, fullRes.width, fullRes.height)
    }
    result.onSuccess { exported: ExportedPanorama ->
        snackbarHostState.showSnackbar("Saved as ${exported.displayName}")
    }.onFailure { error ->
        Logging.w("Little Planet save failed", error)
        snackbarHostState.showSnackbar("Save failed: ${error.message}")
    }
}

/**
 * Decodes the picked image on IO and checks aspect ratio.
 */
private suspend fun loadAndDecodeLittlePlanet(
    context: Context,
    target: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    uri: Uri,
    notEquirect: androidx.compose.runtime.MutableState<Boolean>,
) {
    val decoded = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    }
    if (decoded != null) {
        val ratio = decoded.width.toFloat() / decoded.height.toFloat()
        notEquirect.value = ratio < 1.8f || ratio > 2.2f
        if (notEquirect.value) {
            Logging.w("Little Planet import: ${decoded.width}x${decoded.height} is not 2:1 equirectangular")
        }
        target.value = decoded
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
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).toInt(),
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}