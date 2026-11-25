package com.n30dyn4m1c.photosphere.camera

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.RestitchOffer

/**
 * Debug-only harness camera flow.
 *
 * Reuses the normal PhotoSphere camera UI/behavior so capture is identical,
 * but lives in debug source set and can be wired to harness-only results.
 */
@Composable
fun DebugHarnessCameraScreen(
    onSphereReady: (StitchedSphere, RestitchOffer?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    PhotoSphereCameraScreen(
        onSphereReady = onSphereReady,
        modifier = modifier,
    )
}
