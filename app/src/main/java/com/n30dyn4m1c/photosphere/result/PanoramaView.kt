package com.n30dyn4m1c.photosphere.result
import com.n30dyn4m1c.photosphere.util.Logging

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val TAG = Logging.TAG

/** Vertical field of view the sphere opens at, degrees. Wide enough to feel immersive. */
private const val DEFAULT_FOV_DEGREES = 70f

/** How far a drag of one screen width turns the view, degrees. */
private const val DRAG_SENSITIVITY_DEGREES = 160f

/** Pitch is clamped inside ±[PITCH_LIMIT_DEGREES]: at ±90° the mapping degenerates. */
private const val PITCH_LIMIT_DEGREES = 89f

/** Long edge the equirectangular texture is decoded to. A sphere is 2:1, so 2048 → 2048×1024. */
const val PANO_TEXTURE_MAX_DIMENSION = 2048

/**
 * A pannable, zoomable inside-out view of an equirectangular sphere.
 *
 * The finished photo is a 2:1 frame that nobody will actually *look at* flat —
 * the seams and the horizon are judged by panning around the sphere, which is
 * what this view is for. It is deliberately an *inspection* tool: the result
 * screen keeps the flat frame as the honest overview (the black wedges at the
 * poles are the parts the run never reached) and offers this beside it.
 *
 * The equirectangular remap cannot hold 60 fps as a per-pixel software pass in
 * Compose, so this is a [GLSurfaceView] running a fragment shader: one ray per
 * pixel is cast from the centre of the sphere, rotated by the current
 * yaw/pitch, and sampled from the equirectangular texture. The poles remain
 * legible — the viewport simply looks up and shows the black wedges, exactly
 * as the flat frame does.
 *
 * Drag to look around, pinch to zoom (the vertical field of view narrows).
 * [setSphere] uploads the pixels; until then the view stays black.
 */
class PanoramaView(context: Context) : GLSurfaceView(context) {

    private val renderer = SphereRenderer()

    /** Vertical field of view, degrees. 15° (telephoto) .. 120° (fisheye). */
    var fovDegrees: Float = DEFAULT_FOV_DEGREES
        private set

    /** Compass bearing the view is centred on, degrees. */
    var yawDegrees: Float = 0f
        private set

    /** Elevation the view is centred on, degrees, positive up. */
    var pitchDegrees: Float = 0f
        private set

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        // Redraw only when the view or the texture changes; the gesture layer
        // calls [invalidateView] after every update.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Uploads [bitmap] as the sphere's texture. Safe to call from any thread. */
    fun setSphere(bitmap: Bitmap) {
        queueEvent { renderer.setBitmap(bitmap) }
        requestRender()
    }

    /** Turns the view by [deltaYaw]°/ [deltaPitch]° around the current centre. */
    fun rotateBy(deltaYawDegrees: Float, deltaPitchDegrees: Float) {
        yawDegrees = (yawDegrees + deltaYawDegrees).mod(360f)
        pitchDegrees = (pitchDegrees + deltaPitchDegrees)
            .coerceIn(-PITCH_LIMIT_DEGREES, PITCH_LIMIT_DEGREES)
        invalidateView()
    }

    /** Narrows or widens the view: [scale] > 1 zooms in. */
    fun zoomBy(scale: Float) {
        fovDegrees = (fovDegrees / scale).coerceIn(15f, 120f)
        invalidateView()
    }

    /** Pushes the current view state to the GL thread and asks for one frame. */
    private fun invalidateView() {
        queueEvent {
            renderer.setView(
                yawDegrees = yawDegrees,
                pitchDegrees = pitchDegrees,
                fovDegrees = fovDegrees,
            )
        }
        requestRender()
    }
}

/**
 * The GL side of [PanoramaView]: program, texture, and the view uniforms.
 *
 * All entry points run on the GL thread (they are only called from
 * [GLSurfaceView.queueEvent] and the render callbacks), so no locking is needed.
 */
private class SphereRenderer : GLSurfaceView.Renderer {

    private var program = 0
    private var textureId = 0
    private var textureReady = false
    private var pendingBitmap: Bitmap? = null
    private var textureBitmap: Bitmap? = null

    // View uniforms, updated from the gesture layer.
    private var yawRadians = 0f
    private var pitchRadians = 0f
    private var fovRadians = Math.toRadians(DEFAULT_FOV_DEGREES.toDouble()).toFloat()
    private var aspectRatio = 1f

    /**
     * Latitude span the texture covers, radians. A full sphere is π; a ring
     * capture renders a narrower band (see canvasHeightFor) that must sit at
     * its true elevation instead of stretching over the poles — the flat
     * preview looks right either way, but the sphere view deforms a band
     * mapped as a sphere. Derived from the aspect (square pixels make
     * width:height = 360°:span).
     */
    private var spanLatRadians = Math.PI.toFloat()

    private var vertexBuffer: FloatBuffer? = null

    /** GL thread only; logs the first presented sphere frame once. */
    private var loggedFirstFrame = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram()
        if (program == 0) {
            Log.e(TAG, "Shader program failed to link; the pano view stays black")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspectRatio = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        // The texture is uploaded lazily here: surface creation and the bitmap
        // arriving can happen in either order.
        pendingBitmap?.let { bitmap ->
            uploadTexture(bitmap)
            pendingBitmap = null
            textureBitmap = bitmap
        }

        if (program == 0 || !textureReady) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uYaw = GLES20.glGetUniformLocation(program, "uYaw")
        val uPitch = GLES20.glGetUniformLocation(program, "uPitch")
        val uTanHalfFov = GLES20.glGetUniformLocation(program, "uTanHalfFov")
        val uAspect = GLES20.glGetUniformLocation(program, "uAspect")
        val uSpanLat = GLES20.glGetUniformLocation(program, "uSpanLat")

        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1f(uYaw, yawRadians)
        GLES20.glUniform1f(uPitch, pitchRadians)
        GLES20.glUniform1f(uTanHalfFov, tan(fovRadians / 2f))
        GLES20.glUniform1f(uAspect, aspectRatio)
        GLES20.glUniform1f(uSpanLat, spanLatRadians)

        val buffer = vertexBuffer ?: fullScreenQuad().also { vertexBuffer = it }
        GLES20.glEnableVertexAttribArray(aPosition)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Log.i(TAG, "First sphere frame rendered")
        }
    }

    fun setBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
        spanLatRadians = (2.0 * Math.PI * bitmap.height / bitmap.width)
            .toFloat().coerceIn(0.05f, Math.PI.toFloat())
        Log.i(TAG, "Sphere texture ${bitmap.width}x${bitmap.height} (span %.0f°)"
            .format(spanLatRadians * 180f / Math.PI))
    }

    fun setView(yawDegrees: Float, pitchDegrees: Float, fovDegrees: Float) {
        yawRadians = Math.toRadians(yawDegrees.toDouble()).toFloat()
        pitchRadians = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        fovRadians = Math.toRadians(fovDegrees.toDouble()).toFloat()
    }

    private fun uploadTexture(bitmap: Bitmap) {
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // Longitude wraps: the ±180° seam of the equirectangular frame is a
        // single pixel column in the texture, and REPEAT lets the seam samples
        // bleed across it instead of showing the black clamp edge.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        // The bitmap lives on the Kotlin heap for the caller's decode lifecycle;
        // GL has its own copy now.
        textureReady = true
    }

    private fun buildProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return 0
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Link error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun fullScreenQuad(): FloatBuffer {
        // A triangle strip covering the whole viewport; the fragment shader
        // casts one ray per pixel.
        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        return java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices).flip() }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        /**
         * Casts a ray per pixel and samples the equirectangular texture.
         *
         * The camera basis follows the app's own convention (see
         * [Equirectangular] and SphereProjection): forward is `(sin yaw cos
         * pitch, cos yaw cos pitch, sin pitch)` in the X-east, Y-north, Z-up
         * world frame, right is the horizontal bearing 90° off the aim, and up
         * completes the triple. The screen mapping mirrors
         * `SphereProjection.focalLengthPx`: the vertical half-angle is the
         * field of view, and the horizontal one is stretched by the aspect
         * ratio. The sampling then inverts [Equirectangular.direction] —
         * longitude `atan2(x, y)`, latitude `asin(z)` — so the sphere shown
         * here is the same sphere the flat frame draws, centred on north.
         */
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uYaw;
            uniform float uPitch;
            uniform float uTanHalfFov;
            uniform float uAspect;
            uniform float uSpanLat;
            void main() {
                float x = (vUv.x * 2.0 - 1.0) * uTanHalfFov * uAspect;
                float y = (vUv.y * 2.0 - 1.0) * uTanHalfFov;
                float sinYaw = sin(uYaw);
                float cosYaw = cos(uYaw);
                float sinPitch = sin(uPitch);
                float cosPitch = cos(uPitch);
                vec3 forward = vec3(sinYaw * cosPitch, cosYaw * cosPitch, sinPitch);
                vec3 right = vec3(cosYaw, -sinYaw, 0.0);
                vec3 up = cross(right, forward);
                vec3 dir = normalize(forward + x * right + y * up);
                float u = 0.5 + atan(dir.x, dir.y) / 6.2831853;
                float v = 0.5 - asin(clamp(dir.z, -1.0, 1.0)) / uSpanLat;
                gl_FragColor = texture2D(uTexture, vec2(u, v));
            }
        """
    }
}

/**
 * The pure maths behind [PanoramaView], mirrored from the GLSL so the
 * convention can be pinned by unit tests.
 *
 * @param xScreen rightward screen offset in `-1..1` (0 is the centre)
 * @param yScreen upward screen offset in `-1..1`
 * @param yawDegrees compass bearing the view is centred on (0 = north)
 * @param pitchDegrees elevation the view is centred on, positive up
 * @param fovDegrees vertical field of view
 * @param spanLatRadians latitude span the texture covers (π for a full sphere,
 *   less for a ring band); the mirror of the renderer's `uSpanLat` uniform
 * @return the equirectangular texture coordinate, `u` across (0 = the ±180°
 *   seam), `v` down (0 = the north pole)
 */
internal fun equirectUvForView(
    xScreen: Float,
    yScreen: Float,
    yawDegrees: Float,
    pitchDegrees: Float,
    fovDegrees: Float,
    aspect: Float,
    spanLatRadians: Float = Math.PI.toFloat(),
): Pair<Float, Float> {
    val tanHalf = tan(Math.toRadians(fovDegrees / 2.0))
    val x = xScreen * tanHalf * aspect
    val y = yScreen * tanHalf

    val yaw = Math.toRadians(yawDegrees.toDouble())
    val pitch = Math.toRadians(pitchDegrees.toDouble())
    val sinYaw = sin(yaw)
    val cosYaw = cos(yaw)
    val sinPitch = sin(pitch)
    val cosPitch = cos(pitch)

    val forwardX = sinYaw * cosPitch
    val forwardY = cosYaw * cosPitch
    val forwardZ = sinPitch
    val rightX = cosYaw
    val rightY = -sinYaw
    val rightZ = 0.0
    // up = right × forward
    val upX = rightY * forwardZ
    val upY = -rightX * forwardZ
    val upZ = rightX * forwardY - rightY * forwardX

    val dirX = forwardX + x * rightX + y * upX
    val dirY = forwardY + x * rightY + y * upY
    val dirZ = forwardZ + x * rightZ + y * upZ

    // The shader normalises the ray before sampling; the mirror must too, or
    // the asin below reads the unnormalised vertical component (atan2 is
    // scale-invariant, which is why u needs no correction).
    val length = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)

    val u = 0.5 + atan2(dirX, dirY) / (2.0 * Math.PI)
    val v = 0.5 - asin((dirZ / length).coerceIn(-1.0, 1.0)) / spanLatRadians
    return (u.toFloat() to v.toFloat())
}

/**
 * The gesture layer for [PanoramaView]: drag to look, pinch to zoom.
 *
 * Implemented here rather than inside the view so the pointer handling uses
 * Compose's gesture detectors and stays testable at the composable level.
 */
@Composable
fun PanoramaSphereView(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val view = remember { PanoramaView(context) }

    // The decoded texture arrives from IO; hand it to the GL thread.
    LaunchedEffect(bitmap) {
        bitmap?.let { view.setSphere(it) }
    }

    // GLSurfaceView must be told about the lifecycle or its thread keeps
    // running (and its surface keeps rendering) behind a backgrounded screen.
    LifecycleResumeEffect(view) {
        view.onResume()
        onPauseOrDispose { view.onPause() }
    }

    AndroidView(
        factory = { view },
        modifier = modifier
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Drag: one screen width of travel turns DRAG_SENSITIVITY
                    // degrees. Street-View convention — dragging right turns the
                    // view right (yaw grows, matching the compass convention);
                    // dragging up looks up.
                    val widthPx = view.width.coerceAtLeast(1)
                    view.rotateBy(
                        deltaYawDegrees = pan.x / widthPx * DRAG_SENSITIVITY_DEGREES,
                        deltaPitchDegrees = -pan.y / widthPx * DRAG_SENSITIVITY_DEGREES,
                    )
                    view.zoomBy(zoom)
                }
            },
    )
}
