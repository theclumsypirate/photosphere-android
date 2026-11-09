package com.n30dyn4m1c.photosphere.metadata

import android.graphics.Bitmap
import com.n30dyn4m1c.photosphere.util.Logging
import kotlin.math.PI
import kotlin.math.abs
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
 * Stereographic projection from the nadir with configurable view center (tilt/pan),
 * output spin, and zoom (field of view).
 */
object LittlePlanetConverter {

    /**
     * Creates a Little Planet image from an equirectangular panorama.
     *
     * @param equirect 2:1 equirectangular source bitmap (width = 2 * height).
     * @param outputSize output image dimension (square, circle inscribed).
     * @param fovDegrees zoom as field of view (larger = wider view, smaller = more zoomed in).
     * @param tiltDegrees view tilt/pitch in degrees (-90 to 90).
     * @param panDegrees view pan/yaw in degrees (-180 to 180).
     * @param spinDegrees output image spin/roll in degrees (-180 to 180).
     * @return square bitmap with transparent corners outside the inscribed circle.
     */
    fun convert(
        equirect: Bitmap,
        outputSize: Int = 2048,
        fovDegrees: Float = 180f,
        tiltDegrees: Float = 0f,
        panDegrees: Float = 0f,
        spinDegrees: Float = 0f,
    ): Bitmap {
        require(equirect.width == equirect.height * 2) {
            "Equirectangular must be 2:1, got ${equirect.width}x${equirect.height}"
        }
        require(outputSize > 0) { "outputSize must be positive" }

        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(outputSize * outputSize)

        // Pre-fetch equirect pixels for fast bilinear sampling
        val eqW = equirect.width
        val eqH = equirect.height
        val eqPixels = IntArray(eqW * eqH)
        equirect.getPixels(eqPixels, 0, eqW, 0, 0, eqW, eqH)

        val radius = outputSize / 2.0
        val center = radius
        // Clamp FOV to a safe range to avoid degenerate projection at extremes.
        val fovClamped = fovDegrees.toDouble().coerceIn(20.0, 350.0)
        val halfFovRad = (fovClamped / 2.0) * PI / 180.0
        // Stereographic scale: maps screen edge (dist=radius) to halfFovRad from center.
        // r = 2*tan(theta/2) in unit stereographic; scaled so edge -> halfFov.
        val scale = 2.0 * tan(halfFovRad / 2.0)

        // Rotation angles in radians
        val tiltRad = tiltDegrees.toDouble() * PI / 180.0
        val panRad = panDegrees.toDouble() * PI / 180.0
        val spinRad = spinDegrees.toDouble() * PI / 180.0

        val cosTilt = cos(tiltRad); val sinTilt = sin(tiltRad)
        val cosPan = cos(panRad); val sinPan = sin(panRad)
        val cosSpin = cos(spinRad); val sinSpin = sin(spinRad)

        for (y in 0 until outputSize) {
            val dy = y - center
            val rowOff = y * outputSize
            for (x in 0 until outputSize) {
                val dx = x - center
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > radius) {
                    outPixels[rowOff + x] = 0 // transparent corner
                    continue
                }

                // Apply output spin to screen coordinates before projection
                val sx = dx * cosSpin - dy * sinSpin
                val sy = dx * sinSpin + dy * cosSpin

                // Stereographic projection from nadir (looking down, -Y).
                // theta is the angle from nadir.
                val r = (dist / radius) * scale
                val theta = 2.0 * kotlin.math.atan(r / 2.0)
                val azimuth = atan2(sx, -sy)

                val sinTheta = sin(theta)
                val cosTheta = cos(theta)
                var wx = sinTheta * sin(azimuth)
                var wy = -cosTheta // nadir is -Y
                var wz = sinTheta * cos(azimuth)

                // Apply view tilt (rotate around X axis): pitch the view center
                val wy1 = wy * cosTilt - wz * sinTilt
                val wz1 = wy * sinTilt + wz * cosTilt
                wy = wy1; wz = wz1

                // Apply view pan (rotate around Y axis): yaw the view center
                val wx2 = wx * cosPan + wz * sinPan
                val wz2 = -wx * sinPan + wz * cosPan
                wx = wx2; wz = wz2

                // Normalize direction to guard against drift
                val len = sqrt(wx * wx + wy * wy + wz * wz)
                if (len < 1e-12) {
                    outPixels[rowOff + x] = 0
                    continue
                }
                wx /= len; wy /= len; wz /= len

                // Convert world direction to equirectangular UV
                // longitude from -PI..PI mapped to 0..1
                var u = (atan2(wx, wz) / (2.0 * PI) + 0.5)
                // wrap u into [0,1)
                u -= kotlin.math.floor(u)
                val lat = acos(wy.coerceIn(-1.0, 1.0)) // 0 at north pole, PI at south
                val v = (lat / PI).coerceIn(0.0, 1.0)

                val px = u * (eqW - 1)
                val py = v * (eqH - 1)
                val ix = px.toInt().coerceIn(0, eqW - 2)
                val iy = py.toInt().coerceIn(0, eqH - 2)
                val fx = (px - ix).toFloat().coerceIn(0f, 1f)
                val fy = (py - iy).toFloat().coerceIn(0f, 1f)

                // Bilinear interpolation
                val i00 = iy * eqW + ix
                val i10 = i00 + 1
                val i01 = i00 + eqW
                val i11 = i01 + 1
                val c00 = eqPixels[i00]
                val c10 = eqPixels[i10]
                val c01 = eqPixels[i01]
                val c11 = eqPixels[i11]
                val rch = lerpF(lerpF((c00 ushr 16 and 0xFF).toFloat(), (c10 ushr 16 and 0xFF).toFloat(), fx),
                                lerpF((c01 ushr 16 and 0xFF).toFloat(), (c11 ushr 16 and 0xFF).toFloat(), fx), fy)
                val gch = lerpF(lerpF((c00 ushr 8 and 0xFF).toFloat(), (c10 ushr 8 and 0xFF).toFloat(), fx),
                                lerpF((c01 ushr 8 and 0xFF).toFloat(), (c11 ushr 8 and 0xFF).toFloat(), fx), fy)
                val bch = lerpF(lerpF((c00 and 0xFF).toFloat(), (c10 and 0xFF).toFloat(), fx),
                                lerpF((c01 and 0xFF).toFloat(), (c11 and 0xFF).toFloat(), fx), fy)
                val ri = rch.toInt().coerceIn(0, 255)
                val gi = gch.toInt().coerceIn(0, 255)
                val bi = bch.toInt().coerceIn(0, 255)
                outPixels[rowOff + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        out.setPixels(outPixels, 0, outputSize, 0, 0, outputSize, outputSize)
        Logging.i("LittlePlanet: ${equirect.width}x${equirect.height} -> ${outputSize}x${outputSize} fov=$fovClamped tilt=$tiltDegrees pan=$panDegrees spin=$spinDegrees")
        return out
    }

    private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}