package com.n30dyn4m1c.photosphere.settings

import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
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
 * Debug-only: full-screen GPano metadata wizard.
 * Opens from Settings, Advanced: "Add GPano Metadata to Photo".
 * The user imports a photo, GPano XMP is injected, a 360 preview opens and
 * the result can be saved from a button pinned at the bottom.
 */
@Composable
fun GpanoMetadataEditorScreen(
    onClose: () -> Unit,
) {
    if (!BuildConfig.DEBUG) return // debug-only

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val sourceUri = remember { mutableStateOf<Uri?>(null) }
    val sourceBitmap = remember { mutableStateOf<AndroidBitmap?>(null) }
    val processedBitmap = remember { mutableStateOf<AndroidBitmap?>(null) }
    val busy = remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            sourceUri.value = uri
            processedBitmap.value = null
            scope.launch { loadAndDecode(context, sourceBitmap, uri) }
        }
    }

    // Import then auto inject then auto open the viewer.
    LaunchedEffect(sourceBitmap.value) {
        val bmp = sourceBitmap.value
        if (bmp != null && processedBitmap.value == null && !busy.value) {
            busy.value = true
            injectGpanoMetadata(context, bmp, processedBitmap, snackbarHostState)
            busy.value = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    sourceUri.value = null
                    sourceBitmap.value = null
                    processedBitmap.value = null
                    onClose()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Add GPano Metadata",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                // Spacer so the title stays centered.
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(48.dp))
            }

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
                    processed != null -> {
                        Image(
                            bitmap = processed.asImageBitmap(),
                            contentDescription = "360 photo with GPano metadata",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    busy.value -> {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                    source != null -> {
                        Image(
                            bitmap = source.asImageBitmap(),
                            contentDescription = "Selected photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        Text(
                            text = "Import a photo to inject GPano metadata",
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            if (processedBitmap.value == null && sourceUri.value != null) {
                Text(
                    text = if (busy.value) "Injecting GPano metadata..." else "",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Save pinned at the bottom of the viewer.
            Button(
                onClick = {
                    if (!busy.value) {
                        busy.value = true
                        scope.launch {
                            saveWithGpanoMetadata(context, processedBitmap, snackbarHostState)
                            busy.value = false
                        }
                    }
                },
                enabled = !busy.value && processedBitmap.value != null,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .height(52.dp),
            ) {
                if (busy.value) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text("Saving...")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Save")
                }
            }

            // Re-import row when there is already a photo loaded.
            if (!busy.value && sourceBitmap.value != null) {
                Button(
                    onClick = { pickImage.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                    enabled = !busy.value,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .height(44.dp),
                ) {
                    Text("Import another photo")
                }
            }
        }
    }

    SnackbarHost(hostState = snackbarHostState)
}

/**
 * Decodes the picked image on IO and hands it back through [target].
 */
private suspend fun loadAndDecode(
    context: Context,
    target: MutableState<AndroidBitmap?>,
    uri: Uri,
) {
    val decoded = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    }
    if (decoded != null) {
        val ratio = decoded.width.toFloat() / decoded.height.toFloat()
        if (ratio < 1.8f || ratio > 2.2f) {
            Logging.w("GPano import: ${decoded.width}x${decoded.height} is not 2:1 equirectangular")
        }
        target.value = decoded
    }
}

/**
 * Writes the bitmap to a temp JPEG, injects GPano XMP, and hands the decoded
 * result back through [processed]. The temp file is always cleaned up.
 */
private suspend fun injectGpanoMetadata(
    context: Context,
    source: AndroidBitmap,
    processed: MutableState<AndroidBitmap?>,
    snackbarHostState: SnackbarHostState,
) {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File.createTempFile("gpano_", ".jpg", context.cacheDir)
            try {
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
                android.graphics.BitmapFactory.decodeFile(tempFile.path)
            } finally {
                tempFile.delete()
            }
        }
    }
    result.onSuccess { bmp ->
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
 * Saves the processed bitmap to Pictures/360Panoramas via MediaExporter.
 */
private suspend fun saveWithGpanoMetadata(
    context: Context,
    processed: MutableState<AndroidBitmap?>,
    snackbarHostState: SnackbarHostState,
) {
    val bmp = processed.value ?: return
    val result = withContext(Dispatchers.IO) {
        MediaExporter.exportBitmap(
            context = context,
            bitmap = bmp,
            displayName = "gpano_${System.currentTimeMillis()}.jpg",
            width = bmp.width,
            height = bmp.height,
        )
    }
    result.onSuccess { exported: ExportedPanorama ->
        snackbarHostState.showSnackbar("Saved as ${exported.displayName}")
    }.onFailure { error ->
        Logging.w("GPano save failed", error)
        snackbarHostState.showSnackbar("Save failed: ${error.message}")
    }
}
