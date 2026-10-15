package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeout

/**
 * Samsung-only full-resolution stills (the 64 MP / 108 MP modes).
 *
 * CameraX only exposes [android.hardware.camera2.params.StreamConfigurationMap.getOutputSizes],
 * so its stills top out at the binned 12-17 MP output even on phones whose
 * sensor does far more. The full sensor is advertised separately through
 * [android.hardware.camera2.params.StreamConfigurationMap.getHighResolutionOutputSizes]
 * and needs a dedicated raw Camera2 session: one output, no preview, normal
 * session type, still-capture template. There is no special session mode and
 * no vendor key; this is the documented AOSP path, just not one CameraX walks.
 *
 * Samsung-gated throughout: other phones never see these buttons. Preview
 * must be released first, so callers unbind CameraX (or run this from a
 * screen that holds no camera, like Settings) before capturing.
 */
object SamsungHighRes {

    /** Largest high-res JPEG target across the back cameras, if any. */
    data class HighResTarget(
        val cameraId: String,
        val size: Size,
    )

    fun isSamsung(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * Finds the back camera with the biggest high-resolution JPEG size.
     * Fails when the phone is not a Samsung, is too old for the session
     * APIs, or exposes no high-res sizes to third parties.
     */
    fun probe(context: Context): Result<HighResTarget> = runCatching {
        check(isSamsung()) { "not a Samsung phone" }
        check(Build.VERSION.SDK_INT >= 29) { "needs Android 10 or later" }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: error("no camera service")
        var best: HighResTarget? = null
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: continue
            val sizes = runCatching { map.getHighResolutionOutputSizes(ImageFormat.JPEG) }
                .getOrNull().orEmpty()
            for (size in sizes) {
                val area = size.width.toLong() * size.height
                val bestArea = best?.let { it.size.width.toLong() * it.size.height } ?: -1L
                if (area > bestArea) best = HighResTarget(id, size)
            }
        }
        best ?: error("no high-res JPEG size exposed on this phone")
    }

    /**
     * Captures one full-resolution still to [file] and returns it.
     *
     * Opens the camera directly, runs a single-output session, fires one
     * still request, then closes everything. Takes seconds on 64 MP
     * sensors; call off the main thread. The caller must hold the camera
     * permission and must not have another session open on the camera.
     */
    @RequiresApi(29)
    suspend fun captureStill(
        context: Context,
        target: HighResTarget,
        file: File,
    ): File = withContext(Dispatchers.IO) {
        check(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "camera permission needed" }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: error("no camera service")
        val thread = HandlerThread("samsung-highres").apply { start() }
        val handler = Handler(thread.looper)
        try {
            withTimeout(HIGH_RES_TIMEOUT_MILLIS) {
                captureOnce(manager, handler, target, file)
            }
        } finally {
            thread.quitSafely()
        }
    }

    private suspend fun captureOnce(
        manager: CameraManager,
        handler: Handler,
        target: HighResTarget,
        file: File,
    ): File {
        val device = openDevice(manager, target.cameraId, handler)
        try {
            val reader = ImageReader.newInstance(
                target.size.width, target.size.height, ImageFormat.JPEG, 1,
            )
            try {
                return suspendCancellableCoroutine { continuation ->
                    fun fail(error: Throwable) {
                        if (!continuation.isCompleted) continuation.resumeWithException(error)
                    }
                    reader.setOnImageAvailableListener({ r ->
                        val image = runCatching { r.acquireNextImage() }.getOrNull()
                        if (image == null) {
                            fail(IllegalStateException("no high-res image arrived"))
                            return@setOnImageAvailableListener
                        }
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            FileOutputStream(file).use { it.write(bytes) }
                            Logging.i(
                                "Samsung high-res still: ${target.size.width}x${target.size.height}, " +
                                    "${bytes.size / 1024} KB -> ${file.name}",
                            )
                            if (!continuation.isCompleted) continuation.resume(file)
                        } catch (e: Exception) {
                            fail(e)
                        } finally {
                            image.close()
                        }
                    }, handler)
                    val output = OutputConfiguration(reader.surface)
                    val config = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(output),
                        Executor { handler.post(it) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val request = device.createCaptureRequest(
                                    CameraDevice.TEMPLATE_STILL_CAPTURE,
                                ).apply {
                                    addTarget(reader.surface)
                                    set(
                                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                                        CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
                                    )
                                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                                }.build()
                                runCatching {
                                    session.capture(
                                        request,
                                        object : CameraCaptureSession.CaptureCallback() {},
                                        handler,
                                    )
                                }.onFailure(::fail)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                fail(IllegalStateException("high-res session rejected"))
                            }
                        },
                    )
                    if (!manager.isSessionConfigurationSupported(config)) {
                        fail(IllegalStateException("high-res combination not supported"))
                        return@suspendCancellableCoroutine
                    }
                    runCatching { device.createCaptureSession(config) }.onFailure(::fail)
                }
            } finally {
                runCatching { reader.close() }
            }
        } finally {
            runCatching { device.close() }
        }
    }

    private suspend fun openDevice(
        manager: CameraManager,
        cameraId: String,
        handler: Handler,
    ): CameraDevice = suspendCancellableCoroutine { continuation ->
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        continuation.resume(device)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (!continuation.isCompleted) {
                            continuation.resumeWithException(
                                IllegalStateException("camera disconnected"),
                            )
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (!continuation.isCompleted) {
                            continuation.resumeWithException(
                                IllegalStateException("camera error $error"),
                            )
                        }
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            if (!continuation.isCompleted) continuation.resumeWithException(e)
        }
        continuation.invokeOnCancellation {
            // openCamera has no cancel; the callback guards completion.
        }
    }

    private const val HIGH_RES_TIMEOUT_MILLIS = 45_000L

    private const val PREFS = "samsung_highres"
    private const val KEY_ULTRAWIDE = "prefer_ultrawide"

    /** Whether capture should prefer the widest (ultrawide) back lens. */
    fun isUltrawidePreferred(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ULTRAWIDE, false)

    fun setUltrawidePreferred(context: Context, preferred: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ULTRAWIDE, preferred).apply()
        Logging.i("Samsung ultrawide preferred: $preferred")
    }
}
