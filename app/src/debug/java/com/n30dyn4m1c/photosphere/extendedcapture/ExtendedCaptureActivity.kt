package com.n30dyn4m1c.photosphere.extendedcapture

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
import androidx.lifecycle.lifecycleScope
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme
import com.n30dyn4m1c.photosphere.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Launcher entry for the extended capture test, debug builds only.
 *
 * Declared in `app/src/debug/AndroidManifest.xml`, so it exists in the debug
 * variant and is absent from release, and it is never referenced by
 * `MainActivity`. It hosts [ExtendedCaptureScreen] and nothing else, so the
 * normal capture path is untouched by construction.
 */
class ExtendedCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logging.i("ExtendedCaptureActivity.onCreate")
        // Old test runs are not keepsakes either: clear anything older than
        // the retention before a new sweep starts.
        lifecycleScope.launch(Dispatchers.IO) {
            ExtendedStore.pruneOldRuns(
                this@ExtendedCaptureActivity,
                System.currentTimeMillis() - ExtendedStore.RETENTION_MILLIS,
            )
        }
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
                    ExtendedCaptureScreen(onExit = { finish() })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Logging.i("ExtendedCaptureActivity.onStart")
    }

    override fun onStop() {
        super.onStop()
        Logging.i("ExtendedCaptureActivity.onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logging.i("ExtendedCaptureActivity.onDestroy")
    }
}
