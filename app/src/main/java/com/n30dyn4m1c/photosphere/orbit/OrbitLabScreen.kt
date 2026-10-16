package com.n30dyn4m1c.photosphere.orbit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.BuildConfig

/**
 * Debug-only lab for orbit pieces. Placeholder screen; wiring comes later.
 *
 * No camera binding, no analyzer wiring, no navigation changes.
 */
@Composable
fun OrbitLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.DEBUG) {
        Text("Debug only")
        return
    }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Orbit lab",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text("Pitch plan targets: 12 + 8 + 8")
            Text("Ring overlay: placeholder")
            Text("Analyzer: not bound")
            Text("Tracker: idle")
            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
