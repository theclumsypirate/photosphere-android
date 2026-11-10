package com.n30dyn4m1c.photosphere.extendedcapture

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.stitching.OpenCv360Stitcher
import com.n30dyn4m1c.photosphere.stitching.Stitch360Result
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only OpenCV 360° Stitcher UI
 * 
 * Provides a debug screen to test the native OpenCV 360° equirectangular
 * stitcher on captured frames. Only available in debug builds.
 * 
 * This screen allows you to:
 * - Select a directory of captured frames
 * - Run the OpenCV native stitching pipeline
 * - View the resulting 360° equirectangular panorama
 * - Compare with the Kotlin-based stitcher
 */
@Composable
fun OpenCv360StitcherDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedDir by remember { mutableStateOf<File?>(null) }
    var imageFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var stitching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String>("") }
    var result by remember { mutableStateOf<Stitch360Result?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val stitcherAvailable = remember { OpenCv360Stitcher.isAvailable() }
    
    LaunchedEffect(Unit) {
        // Auto-load stitcher on screen entry
        if (!stitcherAvailable) {
            errorMessage = "Native library not available. Build with CMake first."
        }
    }
    
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "OpenCV 360° Stitcher (Debug)",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (stitcherAvailable) 
                    androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer 
                else 
                    androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (stitcherAvailable) {
                        "Native Library: LOADED ✓"
                    } else {
                        "Native Library: NOT FOUND ✗"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    color = if (stitcherAvailable) 
                        androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                if (!stitcherAvailable) {
                    Text(
                        text = "Build the native library via CMake:\n./gradlew assembleDebug (requires CMake + NDK)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        // Directory selection
        if (selectedDir == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Select Frames Directory",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Choose a directory containing captured frames (JPG/PNG)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { pickDirectory() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !stitching
                    ) {
                        Text("Browse for Frames Folder")
                    }
                }
            }
        } else {
            // Show selected directory and files
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Selected: ${selectedDir?.name}",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${imageFiles.size} images found",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (imageFiles.isNotEmpty()) {
                        // Preview first image
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val firstImage = imageFiles.firstOrNull()
                            if (firstImage != null) {
                                androidx.compose.foundation.Image(
                                    imageBitmap = androidx.compose.ui.graphics.BitmapFactory.decodeFile(firstImage.absolutePath),
                                    contentDescription = "First frame preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = { runStitching() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !stitching && imageFiles.size >= 2 && stitcherAvailable
                    ) {
                        if (stitching) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                                Text("Stitching...")
                            }
                        } else {
                            Text("Run OpenCV 360° Stitching")
                        }
                    }
                }
            }
        }
        
        // Progress display
        if (stitching && progress.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = progress,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
        
        // Result display
        result?.let { res ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (res.success) 
                        androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer 
                    else 
                        androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (res.success) "Stitching Successful!" else "Stitching Failed",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = if (res.success) 
                            androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer 
                        else 
                            androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (res.success) {
                        Text(
                            text = "Output: ${res.outputPath}\nDimensions: ${res.width}x${res.height}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                        
                        // Preview result
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val resultFile = File(res.outputPath)
                            if (resultFile.exists()) {
                                Image(
                                    imageBitmap = androidx.compose.ui.graphics.BitmapFactory.decodeFile(resultFile.absolutePath),
                                    contentDescription = "Stitched 360° panorama",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Error: ${res.errorMessage ?: "Unknown error"}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        
        // Error message
        errorMessage?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = msg,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
    
    private fun pickDirectory() {
        // In a real implementation, you'd use Storage Access Framework
        // For debug, we'll use a known test directory
        val testDirs = listOf(
            File(Environment.getExternalStorageDirectory(), "DCIM/Camera"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoSphere"),
            File(context.filesDir, "debug_frames"),
            File(context.cacheDir, "test_frames")
        )
        
        val foundDir = testDirs.find { it.exists() && it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        if (foundDir != null) {
            loadDirectory(foundDir)
        } else {
            errorMessage = "No test frames directory found. Create one at:\n${testDirs.joinToString("\n")}"
        }
    }
    
    private fun loadDirectory(dir: File) {
        val files = dir.listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            ?: emptyList()
        
        if (files.size < 2) {
            errorMessage = "Need at least 2 images in directory"
            return
        }
        
        selectedDir = dir
        imageFiles = files
        errorMessage = null
        result = null
        Logging.i("Loaded ${files.size} frames from ${dir.absolutePath}")
    }
    
    private fun runStitching() {
        if (imageFiles.size < 2 || stitching) return
        
        stitching = true
        progress = "Initializing OpenCV 360° stitcher..."
        errorMessage = null
        result = null
        
        scope.launch(Dispatchers.IO) {
            try {
                updateProgress("Loading native stitcher...")
                
                val stitcher = OpenCv360Stitcher.create() ?: throw IllegalStateException("Native library not loaded")
                
                updateProgress("Running 360° equirectangular stitching on ${imageFiles.size} images...")
                
                val outputFile = File(
                    context.filesDir,
                    "pano_360_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                )
                
                val stitchResult = stitcher.stitch360Auto(
                    imagePaths = imageFiles.map { it.absolutePath },
                    outputPath = outputFile.absolutePath
                )
                
                withContext(Dispatchers.Main) {
                    stitching = false
                    progress = ""
                    result = stitchResult
                    
                    if (stitchResult.success) {
                        Logging.i("OpenCV 360° stitching completed: ${stitchResult.width}x${stitchResult.height}")
                    } else {
                        Logging.e("OpenCV 360° stitching failed: ${stitchResult.errorMessage}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stitching = false
                    progress = ""
                    errorMessage = "Stitching error: ${e.message}"
                    Logging.e("OpenCV 360° stitching exception", e)
                }
            }
        }
    }
    
    private fun updateProgress(message: String) {
        withContext(Dispatchers.Main) {
            progress = message
        }
    }
}

/**
 * Helper to get file extension
 */
private fun File.extension(): String {
    val name = this.name
    val lastDot = name.lastIndexOf('.')
    return if (lastDot > 0) name.substring(lastDot + 1) else ""
}

@Composable
private fun LocalContext.current: Context = androidx.compose.ui.platform.LocalContext.current