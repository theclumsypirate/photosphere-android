package com.n30dyn4m1c.photosphere.metadata

import android.graphics.Bitmap
import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Converts an equirectangular (2:1) panorama to Little Planet (stereographic) projection.
 *
 * Pure Kotlin/CPU implementation, no RenderScript. Runs off the main thread.
 */
object LittlePlanetConverter {

    /**
     * Creates a Little Planet image from an equirectangular panorama.
     *
     * @param equirect 2:1 equirectangular source bitmap (width = 2 * height).
     * @param outputSize output image dimension (square, circle inscribed).
     * @param fovDegrees field of view to include (180 = full sphere, 120 = typical).
     * @return square bitmap with transparent corners outside the circle.
     */
    fun convert(
        equirect: Bitmap,
        outputSize: Int = 2048,
        fovDegrees: Float = 180f,
    ): Bitmap {
        require(equirect.width == equirect.height * 2) {
            "Equirectangular must be 2:1, got ${equirect.width}x${equirect.height}"
        }

        val eqW = equirect.width.toFloat()
        val eqH = equirect.height.toFloat()
        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(outputSize * outputSize)

        // Pre-fetch equirect pixels for fast bilinear sampling
        val eqPixels = IntArray(equirect.width * equirect.height)
        equirect.getPixels(eqPixels, 0, equirect.width, 0, 0, equirect.width, equirect.height)

        val radius = outputSize / 2f
        val center = radius
        val maxTheta = (fovDegrees / 2f) * PI / 180f // radians

        for (y in 0 until outputSize) {
            val dy = y - center
            for (x in 0 until outputSize) {
                val dx = x - center
                val dist = sqrt(dx * dx + dy * dy).toFloat()
                if (dist > radius) {
                    outPixels[x + y * outputSize] = 0 // transparent corner
                    continue
                }
                // Normalized radius 0..1 maps to theta 0..maxTheta
                val theta = (dist / radius) * maxTheta
                // Stereographic projection from nadir
                val azimuth = atan2(dx.toDouble(), -dy.toDouble()).toFloat()
                val wx = (sin(theta) * sin(azimuth)).toFloat()
                val wy = (-cos(theta)).toFloat() // nadir is -Y
                val wz = (sin(theta) * cos(azimuth)).toFloat()
                // Convert world direction to equirectangular UV
                val u = (atan2(wx.toDouble(), wz.toDouble()) / (2 * PI) + 0.5).toFloat()
                val v = (0.5 - acos(max(-1f, min(1f, wy))) / PI).toFloat()
                val px = u * (equirect.width - 1)
                val py = v * (equirect.height - 1)
                val ix = px.toInt()
                val iy = py.toInt()
                val fx = (px - ix).toFloat()
                val fy = (py - iy).toFloat()
                // Bilinear interpolation
                val c00 = eqPixels[max(0, min(equirect.width - 1, ix)) + max(0, min(equirect.height - 1, iy)) * equirect.width]
                val c10 = eqPixels[max(0, min(equirect.width - 1, ix + 1)) + max(0, min(equirect.height - 1, iy)) * equirect.width]
                val c01 = eqPixels[max(0, min(equirect.width - 1, ix)) + max(0, min(equirect.height - 1, iy + 1)) * equirect.width]
                val c11 = eqPixels[max(0, min(equirect.width - 1, ix + 1)) + max(0, min(equirect.height - 1, iy + 1)) * equirect.width]
                val r = lerpF(lerpF((c00 shr 16 and 0xFF).toFloat(), (c10 shr 16 and 0xFF).toFloat(), fx),
                             lerpF((c01 shr 16 and 0xFF).toFloat(), (c11 shr 16 and 0xFF).toFloat(), fx), fy)
                val g = lerpF(lerpF((c00 shr 8 and 0xFF).toFloat(), (c10 shr 8 and 0xFF).toFloat(), fx),
                             lerpF((c01 shr 8 and 0xFF).toFloat(), (c11 shr 8 and 0xFF).toFloat(), fx), fy)
                val b = lerpF(lerpF((c00 and 0xFF).toFloat(), (c10 and 0xFF).toFloat(), fx),
                             lerpF((c01 and 0xFF).toFloat(), (c11 and 0xFF).toFloat(), fx), fy)
                val ri = r.toInt()
                val gi = g.toInt()
                val bi = b.toInt()
                val pixel = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                outPixels[x + y * outputSize] = pixel
            }
        }
        out.setPixels(outPixels, 0, outputSize, 0, 0, outputSize, outputSize)
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()
    private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}