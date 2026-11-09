package com.n30dyn4m1c.photosphere.settings

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
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
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.metadata.GPanoXmpInjector
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
 * Full-screen GPano Metadata injector with native photo import.
 * Opens from Settings → Advanced settings → Add GPano Metadata to 360 photo.
 * Uses system photo picker (PickVisualMedia) like ImportPhotosScreen.
 * User imports a photo, GPano XMP is injected, then it opens in 360 viewer with Save button.
 */
@Composable
fun GpanoMetadataEditorScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val sourceUri = remember { mutableStateOf<Uri?>(null) }
    val sourceBitmap = remember { mutableStateOf<AndroidBitmap?>(null) }
    val processedBitmap = remember { mutableStateOf<AndroidBitmap?>(null) }
    val xmpInjectedFile = remember { mutableStateOf<File?>(null) }
    val showViewer = remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    val notEquirect = remember { mutableStateOf(false) }

    // Native photo picker like ImportPhotosScreen
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            sourceUri.value = uri
            processedBitmap.value = null
            showViewer.value = false
            notEquirect.value = false
            scope.launch { loadAndDecodeGpano(context, sourceBitmap, uri, notEquirect) }
        }
    }

    // Auto inject GPano metadata when source bitmap changes
    LaunchedEffect(sourceBitmap.value) {
        val bmp = sourceBitmap.value
        if (bmp != null && xmpInjectedFile.value == null && !busy && !showViewer.value) {
            busy = true
            injectGpanoMetadata(context, bmp, xmpInjectedFile, processedBitmap, snackbarHostState)
            busy = false
            showViewer.value = true
        }
    }

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
            // Top bar with safe top padding (system bars)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    showViewer.value = false
                    sourceUri.value = null
                    sourceBitmap.value = null
                    processedBitmap.value = null
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Add GPano Metadata to 360 photo",
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

            // Preview/Viewer area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val processed = processedBitmap.value
                val source = sourceBitmap.value
                when {
                    showViewer.value && processed != null -> {
                        Image(
                            bitmap = processed.asImageBitmap(),
                            contentDescription = "360 Photo with GPano Metadata",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    busy && source != null -> {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                    source != null && !showViewer.value -> {
                        Image(
                            bitmap = source.asImageBitmap(),
                            contentDescription = "Selected 360 Panorama",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    sourceUri.value == null -> {
                        Text(
                            text = "Import a 360 photo to add GPano metadata",
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    else -> {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                }
            }

            // Import button (when no image)
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
                    Text("Import Photo")
                }
            }

            // Open in 360 Viewer button (when source loaded but not yet viewing)
            if (!busy && !showViewer.value && sourceBitmap.value != null) {
                Button(
                    onClick = { showViewer.value = true },
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(56.dp),
                ) {
                    Text("Open in 360 Viewer", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Save button (pinned at bottom when in viewer)
            if (showViewer.value) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    saveWithGpanoMetadata(context, xmpInjectedFile, snackbarHostState)
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && processedBitmap.value != null,
                        shape = PillShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp)
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
                            Text("Saving…", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "Save")
                            Spacer(modifier = Modifier.padding(start = 8.dp))
                            Text("Save with GPano Metadata", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            // Change Image button
            if (sourceBitmap.value != null && !busy && !showViewer.value) {
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
 * Decodes the picked image on IO and checks aspect ratio.
 * Downsamples large images to max 4096px wide to avoid OOM.
 */
private suspend fun loadAndDecodeGpano(
    context: Context,
    target: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    uri: Uri,
    notEquirect: androidx.compose.runtime.MutableState<Boolean>,
) {
    val decoded = withContext(Dispatchers.IO) {
        // First decode bounds only to check size
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        // Calculate sample size to limit max dimension to 4096
        val MAX_DIM = 4096
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DIM || bounds.outHeight / sampleSize > MAX_DIM) {
            sampleSize *= 2
        }

        // Decode with sample size
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            android.graphics.BitmapFactory.decodeStream(stream, null, opts)
        }
    }
    if (decoded != null) {
        val ratio = decoded.width.toFloat() / decoded.height.toFloat()
        notEquirect.value = ratio < 1.8f || ratio > 2.2f
        if (notEquirect.value) {
            Logging.w("GPano import: ${decoded.width}x${decoded.height} is not 2:1 equirectangular")
        }
        target.value = decoded
    }
}

/**
 * Writes the bitmap to a temp JPEG, injects GPano XMP, and stores the file.
 * The temp file is kept (not deleted) so we can save it with XMP intact.
 */
private suspend fun injectGpanoMetadata(
    context: Context,
    source: AndroidBitmap,
    xmpInjectedFile: androidx.compose.runtime.MutableState<File?>,
    processed: androidx.compose.runtime.MutableState<AndroidBitmap?>,
    snackbarHostState: SnackbarHostState,
) {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File.createTempFile("gpano_", ".jpg", context.cacheDir)
            tempFile.outputStream().use { out ->
                source.compress(AndroidBitmap.CompressFormat.JPEG, 92, out)
            }
            val metadata = GPanoMetadata(
                fullPanoWidthPixels = source.width,
                fullPanoHeightPixels = source.height,
                croppedAreaImageWidthPixels = source.width,
                croppedAreaImageHeightPixels = source.height,
                croppedAreaLeftPixels = 0,
                croppedAreaTopPixels = 0,
            )
            GPanoXmpInjector.inject(tempFile, metadata).getOrThrow()
            // Return the file with XMP injected (don't decode to bitmap, don't delete)
            tempFile
        }
    }
    result.onSuccess { file ->
        xmpInjectedFile.value = file
        // Also decode for preview
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path)
        if (bmp != null) {
            processed.value = bmp
        } else {
            snackbarHostState.showSnackbar("Could not read the photo back")
        }
    }.onFailure { error ->
        Logging.w("GPano inject failed", error)
        snackbarHostState.showSnackbar("GPano inject failed: ${error.message}")
    }
}

/**
 * Copies the XMP-injected file to Pictures/360Panoramas via MediaStore,
 * preserving the GPano XMP metadata.
 */
private suspend fun saveWithGpanoMetadata(
    context: Context,
    xmpInjectedFile: androidx.compose.runtime.MutableState<File?>,
    snackbarHostState: SnackbarHostState,
) {
    val file = xmpInjectedFile.value ?: return
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "gpano_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/360Panoramas")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open output stream for $uri")
            uri
        }
    }
    result.onSuccess { uri ->
        snackbarHostState.showSnackbar("Saved to gallery")
    }.onFailure { error ->
        Logging.w("GPano save failed", error)
        snackbarHostState.showSnackbar("Save failed: ${error.message}")
    }
}