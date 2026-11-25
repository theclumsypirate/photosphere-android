package com.n30dyn4m1c.photosphere.harness

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.camera.rememberSphereOptics
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugHarnessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val optics = rememberSphereOptics()
    var pickedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var importDir by remember { mutableStateOf<File?>(null) }
    var state by remember { mutableStateOf(HarnessRunState()) }
    var compare by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "harness_import_${System.currentTimeMillis()}").apply { mkdirs() }
            importDir?.takeIf { it != dir }?.deleteRecursively()
            val files = uris.mapIndexedNotNull { i, uri ->
                runCatching {
                    val f = File(dir, "frame_${"%03d".format(i)}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                importDir = dir
                pickedFiles = files
                state = HarnessRunState(message = "Selected ${files.size} photos")
                compare = false
            }
        }
    }

    fun loadLatestSession() {
        scope.launch(Dispatchers.IO) {
            val sessionsRoot = File(context.cacheDir, "sphere_sessions")
            val latest = sessionsRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
            val files = latest?.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") && !it.name.contains("_t") }
                ?.sortedBy { it.name }
                ?: emptyList()
            withContext(Dispatchers.Main) {
                if (files.isEmpty()) {
                    state = HarnessRunState(message = "No capture session frames found")
                } else {
                    pickedFiles = files
                    state = HarnessRunState(message = "Loaded ${files.size} frames from last capture session")
                    compare = false
                }
            }
        }
    }

    fun runHarness() {
        if (pickedFiles.size < 3 || state.running) return
        scope.launch {
            val frames = withContext(Dispatchers.IO) { buildFrames(pickedFiles, optics) }
            state = state.copy(running = true, message = "Running tests...", results = emptyList(), bestId = null)
            val final = DebugHarnessRunner.run(context, frames, optics.fieldOfView, optics) { state = it }
            state = final
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Harness tests (debug)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (compare && state.results.any { it.error == null }) {
            CompareResults(
                results = state.results.filter { it.error == null }.sortedByDescending { it.score },
                bestId = state.bestId,
                onClose = { compare = false },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Debug-only stitch harness. Pick your manual 38-photo set (or load last capture session), run all stitch test configs, auto-score them, and swipe compare equirectangular 2:1 results.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("Pick photos (${pickedFiles.size})") }
                            OutlinedButton(onClick = { loadLatestSession() }) { Text("Use last session") }
                        }
                        Button(onClick = { runHarness() }, enabled = pickedFiles.size >= 3 && !state.running) {
                            Text(if (state.running) "Running..." else "Run harness tests")
                        }
                    }
                }
                if (state.running || state.total > 0) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("${state.completed}/${state.total} ${state.currentLabel}")
                        }
                    }
                }
                state.message?.let { msg ->
                    item { Text(msg, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                }
                if (state.results.any { it.error == null }) {
                    item {
                        Button(onClick = { compare = true }) { Text("Compare / swipe results") }
                    }
                }
                val ranked = state.results.sortedByDescending { it.score }
                itemsIndexed(ranked, key = { _, r -> r.config.id }) { _, r ->
                    ResultCard(r, best = r.config.id == state.bestId)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: HarnessResult, best: Boolean) {
    val border = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, border).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${r.config.id} ${r.config.label}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (best) Text("BEST", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        if (r.error != null) {
            Text("Error: ${r.error}", color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "Score ${"%.1f".format(r.score)} | coverage ${"%.1f".format(r.metrics.coverage * 100)}% | sharp ${"%.1f".format(r.metrics.sharpness)} | seam ${"%.2f".format(r.metrics.seamPenalty)} | exposure ${"%.2f".format(r.metrics.exposureBalance)} | ${r.metrics.runtimeMs}ms",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "seam=${r.config.seamMode} refine=${r.config.useRefinement} dist=${r.config.distortionEnabled} block=${r.config.perBlockExposure} warp=${r.config.localWarpAlignment} graph=${r.config.graphSubsetSelection} percept=${r.config.perceptualSeamCost} mask=${r.config.movingObjectSeamMask}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CompareResults(results: List<HarnessResult>, bestId: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { results.size })
    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onClose) { Text("Back to list") }
            Spacer(Modifier.width(8.dp))
            Text("Swipe to compare equirectangular results", style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val r = results[page]
            val bmp = remember(r.bitmapFile.path) {
                runCatching { BitmapFactory.decodeFile(r.bitmapFile.path) }.getOrNull()
            }
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${r.config.id} ${r.config.label}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (r.config.id == bestId) Text("BEST", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Text("Score ${"%.1f".format(r.score)} | coverage ${"%.1f".format(r.metrics.coverage * 100)}% | sharp ${"%.1f".format(r.metrics.sharpness)} | seam ${"%.2f".format(r.metrics.seamPenalty)} | exposure ${"%.2f".format(r.metrics.exposureBalance)}")
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = r.config.label,
                        modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("Cannot load image") }
                }
            }
        }
        Text("Result ${pagerState.currentPage + 1}/${results.size}. Swipe left/right.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun buildFrames(files: List<File>, optics: com.n30dyn4m1c.photosphere.camera.SphereOptics): List<SphereFrame> {
    val stamped = files.map { readStampedPose(it) }
    val useStamped = stamped.all { it != null }
    val poses = if (useStamped) stamped.requireNoNulls() else fallbackRing(files.size, optics.fieldOfView.horizontalDegrees)
    return files.mapIndexed { i, f -> SphereFrame(file = f, pose = poses[i], index = i) }
}

private fun readStampedPose(file: File): CameraPose? = runCatching {
    val comment = ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return@runCatching null
    val m = Regex("""yaw=(-?\d+(?:\.\d+)?);pitch=(-?\d+(?:\.\d+)?);roll=(-?\d+(?:\.\d+)?)""").find(comment) ?: return@runCatching null
    CameraPose(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
}.getOrNull()

private fun fallbackRing(count: Int, hFov: Float): List<CameraPose> {
    val step = (hFov * 0.75f).coerceIn(20f, 90f)
    return List(count) { i -> CameraPose(yawDegrees = i * step, pitchDegrees = 0f, rollDegrees = 0f) }
}
