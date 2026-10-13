package com.n30dyn4m1c.photosphere

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.n30dyn4m1c.photosphere.util.Logging

/**
 * Cold-start splash: the branded window background shows while the process
 * starts, then this hands straight to MainActivity with no UI of its own.
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logging.i("SplashActivity: handing off to MainActivity")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
