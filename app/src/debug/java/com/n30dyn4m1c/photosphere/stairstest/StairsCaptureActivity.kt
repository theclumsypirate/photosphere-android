package com.n30dyn4m1c.photosphere.stairstest

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Launcher entry for the stairs grid capture test, debug builds only.
 *
 * Declared in `app/src/debug/AndroidManifest.xml`, so it exists in the debug
 * variant and is absent from release, and it is never referenced by
 * `MainActivity`. It hosts [StairsCaptureScreen] and nothing else, so the
 * normal capture path is untouched by construction.
 */
class StairsCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logging.i("StairsCaptureActivity.onCreate")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PhotoSphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    StairsCaptureScreen(onExit = { finish() })
                }
            }
        }
    }
}
