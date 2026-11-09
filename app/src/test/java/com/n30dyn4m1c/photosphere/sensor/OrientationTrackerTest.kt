package com.n30dyn4m1c.photosphere.sensor

import android.hardware.SensorManager
import android.view.Surface
import com.n30dyn4m1c.photosphere.stitching.CameraBasis
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.RotationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts of the tracker that are pure arithmetic.
 *
 * The sensor path itself needs a device (`SensorManager`'s matrix helpers are
 * stubbed out in local unit tests). The `Surface.ROTATION_*` and
 * `SensorManager.AXIS_*` values used here are compile-time constants, so they
 * survive the stub jar.
 */
class OrientationTrackerTest {

    @Test
    fun `normalizeDegrees leaves in-range angles alone`() {
        assertEquals(0f, normalizeDegrees(0f), TOLERANCE)
        assertEquals(90f, normalizeDegrees(90f), TOLERANCE)
        assertEquals(-90f, normalizeDegrees(-90f), TOLERANCE)
        assertEquals(-179.5f, normalizeDegrees(-179.5f), TOLERANCE)
    }

    @Test
    fun `normalizeDegrees wraps onto a half-open range`() {
        // 180 and -180 are the same bearing; the range is [-180, 180).
        assertEquals(-180f, normalizeDegrees(180f), TOLERANCE)
        assertEquals(-180f, normalizeDegrees(-180f), TOLERANCE)
        assertEquals(-90f, normalizeDegrees(270f), TOLERANCE)
        assertEquals(90f, normalizeDegrees(-270f), TOLERANCE)
        assertEquals(0f, normalizeDegrees(360f), TOLERANCE)
        assertEquals(1f, normalizeDegrees(721f), TOLERANCE)
    }

    @Test
    fun `each display rotation maps to its own axis pair`() {
        val pairs = listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        ).map { DisplayAxes.forDisplayRotation(it) }

        assertEquals(pairs.size, pairs.distinct().size)
    }

    @Test
    fun `portrait is the identity remap`() {
        val axes = DisplayAxes.forDisplayRotation(Surface.ROTATION_0)
        assertEquals(SensorManager.AXIS_X, axes.axisX)
        assertEquals(SensorManager.AXIS_Y, axes.axisY)
    }

    @Test
    fun `landscape swaps the chassis axes`() {
        val axes = DisplayAxes.forDisplayRotation(Surface.ROTATION_90)
        assertEquals(SensorManager.AXIS_Y, axes.axisX)
        assertEquals(SensorManager.AXIS_MINUS_X, axes.axisY)
    }

    @Test
    fun `an unknown rotation falls back to portrait`() {
        assertEquals(
            DisplayAxes.forDisplayRotation(Surface.ROTATION_0),
            DisplayAxes.forDisplayRotation(-1),
        )
    }

    @Test
    fun `sensor accuracy maps onto the readable enum`() {
        assertEquals(
            OrientationAccuracy.High,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH),
        )
        assertEquals(
            OrientationAccuracy.Medium,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM),
        )
        assertEquals(
            OrientationAccuracy.Low,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW),
        )
        assertEquals(
            OrientationAccuracy.Unreliable,
            OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_UNRELIABLE),
        )
        // SENSOR_STATUS_NO_CONTACT and anything else a vendor invents.
        assertEquals(OrientationAccuracy.Unknown, OrientationAccuracy.fromSensorAccuracy(-1))
    }

    @Test
    fun `only medium and high accuracy count as usable`() {
        assertTrue(OrientationAccuracy.High.isUsable)
        assertTrue(OrientationAccuracy.Medium.isUsable)
        assertFalse(OrientationAccuracy.Low.isUsable)
        assertFalse(OrientationAccuracy.Unreliable.isUsable)
        assertFalse(OrientationAccuracy.Unknown.isUsable)
    }

    @Test
    fun `capture is only refused when the sensor calls itself unreliable`() {
        // The capture plan is anchored on the bearing the run started at, so an
        // uncalibrated compass shifts every target together and costs the sphere
        // nothing. Gating capture on `isUsable` instead strands the user
        // indoors: the reticle tracks perfectly and the shutter never fires,
        // with nothing on screen to explain it.
        assertTrue(OrientationAccuracy.High.allowsCapture)
        assertTrue(OrientationAccuracy.Medium.allowsCapture)
        assertTrue(OrientationAccuracy.Low.allowsCapture)
        assertTrue(OrientationAccuracy.Unknown.allowsCapture)
        assertFalse(OrientationAccuracy.Unreliable.allowsCapture)
    }

    @Test
    fun `the default sample has no fix`() {
        assertFalse(OrientationData().hasFix)
        assertTrue(OrientationData(timestampNanos = 1L).hasFix)
    }

    @Test
    fun `camera angles round-trip through a device matrix`() {
        // `cameraAnglesDegrees` is the inverse of the axis construction
        // `CameraBasis.of` performs, so feeding it the matrix a phone with that
        // basis would produce must hand the same angles back — or every frame
        // lands on the sphere rotated.
        for (yaw in -150..150 step 30) {
            for (pitch in -60..60 step 20) {
                for (roll in -120..120 step 40) {
                    for (display in listOf(
                        Surface.ROTATION_0,
                        Surface.ROTATION_90,
                        Surface.ROTATION_180,
                        Surface.ROTATION_270,
                    )) {
                        val label = "yaw $yaw pitch $pitch roll $roll display $display"
                        val basis = CameraBasis.of(
                            CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
                        )
                        val out = FloatArray(3)
                        cameraAnglesDegrees(deviceMatrix(basis, display), display, out)
                        assertEquals("$label: yaw", yaw.toFloat(), out[0], 1e-3f)
                        assertEquals("$label: pitch", pitch.toFloat(), out[1], 1e-3f)
                        assertEquals("$label: roll", roll.toFloat(), out[2], 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun `a level pan reads as yaw, not roll`() {
        // The regression this file exists for: the camera-reference angles used
        // to report a level pan as *roll* and kept yaw pinned near zero, so the
        // stitcher placed every frame of a ring onto the same longitude, each
        // one rotated differently — the "aligned but at odd angles" failure.
        for (heading in listOf(0f, 30f, 60f, 90f, -45f, 170f)) {
            val basis = CameraBasis.of(CameraPose(heading, 0f, 0f))
            val out = FloatArray(3)
            cameraAnglesDegrees(deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out)
            assertEquals("yaw for heading $heading", heading, out[0], 1e-3f)
            assertEquals("roll for heading $heading", 0f, out[2], 1e-3f)
        }
    }

    @Test
    fun `a roll of the phone reads as roll, not yaw`() {
        for (roll in listOf(0f, 30f, 90f, -45f)) {
            val basis = CameraBasis.of(CameraPose(0f, 0f, roll))
            val out = FloatArray(3)
            cameraAnglesDegrees(deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out)
            assertEquals("roll $roll", roll, out[2], 1e-3f)
            assertEquals("yaw for roll $roll", 0f, out[0], 1e-3f)
        }
    }

    @Test
    fun `negative pitch is aimed above the horizon`() {
        val basis = CameraBasis.of(CameraPose(0f, -30f, 0f))
        val out = FloatArray(3)
        cameraAnglesDegrees(deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out)
        assertEquals("aimed up is negative pitch", -30f, out[1], 1e-3f)
    }

    @Test
    fun `yaw and roll wrap onto the half-open range`() {
        // The camera looks at the pole of the compass — a heading that could be
        // ±180 — and the extraction has to pick one side.
        val basis = CameraBasis.of(CameraPose(180f, 0f, 0f))
        val out = FloatArray(3)
        cameraAnglesDegrees(deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out)
        assertTrue("yaw ${out[0]} should sit in [-180, 180)", out[0] in -180f..180f)
        assertTrue(out[0] != 180f || out[0] == -180f)
    }

    @Test
    fun `the camera basis extracted from a device matrix round-trips`() {
        // cameraBasisMatrix reads the same axes cameraAnglesDegrees reads, so
        // feeding it the matrix a phone with a known basis would produce must
        // hand that basis back unchanged — including the axes the display
        // rotation remaps.
        for (display in listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        )) {
            val expected = CameraBasis.of(CameraPose(37f, -22f, 9f))
            val extracted = FloatArray(9)
            cameraBasisMatrix(deviceMatrix(expected, display), display, extracted)
            val basis = CameraBasis.fromRotationMatrix(
                doubleArrayOf(
                    extracted[0].toDouble(), extracted[1].toDouble(), extracted[2].toDouble(),
                    extracted[3].toDouble(), extracted[4].toDouble(), extracted[5].toDouble(),
                    extracted[6].toDouble(), extracted[7].toDouble(), extracted[8].toDouble(),
                )
            )
            assertEquals(
                "display $display",
                0.0,
                RotationMath.angle(basis.toRotationMatrix(), expected.toRotationMatrix()),
                1e-4,
            )
        }
    }

    private fun deviceMatrix(basis: CameraBasis, displayRotation: Int): FloatArray {
        // Build the device->world matrix a real phone with this camera basis
        // would report, given how the display rotation remaps which chassis axis
        // is the image's right/up.
        val right = doubleArrayOf(basis.rightX, basis.rightY, basis.rightZ)
        val up = doubleArrayOf(basis.upX, basis.upY, basis.upZ)
        val forward = doubleArrayOf(basis.forwardX, basis.forwardY, basis.forwardZ)
        fun neg(v: DoubleArray) = doubleArrayOf(-v[0], -v[1], -v[2])

        val devX: DoubleArray
        val devY: DoubleArray
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                devX = neg(up)
                devY = right
            }
            Surface.ROTATION_180 -> {
                devX = neg(right)
                devY = neg(up)
            }
            Surface.ROTATION_270 -> {
                devX = up
                devY = neg(right)
            }
            else -> {
                devX = right
                devY = up
            }
        }
        val devZ = neg(forward)

        fun put(v: DoubleArray, column: Int, out: FloatArray) {
            out[column] = v[0].toFloat()
            out[3 + column] = v[1].toFloat()
            out[6 + column] = v[2].toFloat()
        }
        return FloatArray(9).also {
            put(devX, 0, it)
            put(devY, 1, it)
            put(devZ, 2, it)
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
