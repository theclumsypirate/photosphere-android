package com.n30dyn4m1c.photosphere.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.RestitchOffer

/**
 * Debug-only video twin of [PhotoSphereCameraScreen].
 *
 * Exact same capture underneath (same composable, same params), only
 * wrapped with a video-mode chrome placeholder. Release builds never
 * reach here: the entry button and the navigation branch are both
 * gated on [BuildConfig.DEBUG].
 */
@Composable
fun PhotoSphereCameraVideoScreen(
    onSphereReady: (StitchedSphere, RestitchOffer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.DEBUG) return
    var recording by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxSize()) {
        PhotoSphereCameraScreen(onSphereReady = onSphereReady)
        FilledTonalButton(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 64.dp)
                .background(if (recording) Color.Red.copy(alpha = 0.2f) else Color.Transparent),
            onClick = { recording = !recording },
        ) {
            Text(if (recording) "REC stop" else "REC start")
        }
    }
}
