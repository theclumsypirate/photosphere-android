package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OrientationSmootherTest {

    private fun fix(yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f) =
        OrientationData(yawDegrees = yaw, pitchDegrees = pitch, rollDegrees = roll, timestampNanos = 1L)

    /** A sample from a phone with no gyroscope: magnetometer only. */
    private fun noisy(yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f) =
        OrientationData(
            yawDegrees = yaw,
            pitchDegrees = pitch,
            rollDegrees = roll,
            timestampNanos = 1L,
            isGyroAssisted = false,
        )

    @Test
    fun `a held aim converges onto the sample`() {
        val smoother = OrientationSmoother()
        smoother.update(fix(yaw = 10f))
        val settled = (1..30).fold(fix(yaw = 10.5f)) { _, sample -> smoother.update(sample) }
        assertEquals(10.5f, settled.yawDegrees, 0.01f)
    }

    @Test
    fun `small steps are smoothed rather than passed through`() {
        val smoother = OrientationSmoother(alpha = 0.3f, snapDegrees = 3f)
        smoother.update(fix(yaw = 0f))
        val first = smoother.update(fix(yaw = 1f))
        assertEquals(0.3f, first.yawDegrees, 0.001f)
    }

    @Test
    fun `a fast pan snaps through with no lag`() {
        val smoother = OrientationSmoother()
        smoother.update(fix(yaw = 0f))
        val jumped = smoother.update(fix(yaw = 50f))
        assertEquals(50f, jumped.yawDegrees, 0.001f)
    }

    @Test
    fun `smoothing takes the short way across the yaw wrap`() {
        val smoother = OrientationSmoother(alpha = 0.5f, snapDegrees = 3f)
        smoother.update(fix(yaw = -179f))
        // +179° is 2° away through ±180°, not 358° back through 0°.
        val wrapped = smoother.update(fix(yaw = 179f))
        assertEquals(-180f, wrapped.yawDegrees, 0.001f)
    }

    @Test
    fun `samples without a fix pass through and reset the average`() {
        val smoother = OrientationSmoother()
        smoother.update(fix(yaw = 20f))
        val noFix = smoother.update(OrientationData())
        assertEquals(0f, noFix.yawDegrees, 0.001f)
        // After the gap the next fix restarts the average instead of gliding.
        val restarted = smoother.update(fix(yaw = 40f))
        assertEquals(40f, restarted.yawDegrees, 0.001f)
    }

    @Test
    fun `a magnetometer only spike never reaches the marker`() {
        // The bug this guards: on a phone without a gyroscope the yaw jumps by
        // several degrees between samples, the snap test reads each jump as a
        // pan, and the target marker wanders. The window median must swallow an
        // isolated jump whole.
        val smoother = OrientationSmoother()
        smoother.update(noisy(yaw = 0f))
        repeat(3) { smoother.update(noisy(yaw = 0f)) }
        val spiked = smoother.update(noisy(yaw = 12f))
        assertTrue(
            "spike leaked through: ${spiked.yawDegrees}",
            abs(spiked.yawDegrees) < 0.5f,
        )
    }

    @Test
    fun `a magnetometer only sustained move is still followed`() {
        // Steadiness must not become stickiness: a real turn has to come
        // through, only a sample or two behind the median window.
        val smoother = OrientationSmoother()
        smoother.update(noisy(yaw = 0f))
        repeat(3) { smoother.update(noisy(yaw = 0f)) }
        smoother.update(noisy(yaw = 5f))
        smoother.update(noisy(yaw = 10f))
        val moved = smoother.update(noisy(yaw = 15f))
        assertTrue("sustained turn was not followed: ${moved.yawDegrees}", moved.yawDegrees >= 9f)
    }

    @Test
    fun `gyro assisted samples keep the plain snap through`() {
        // The gate: a gyro-assisted phone must behave exactly as before, so a
        // big jump is passed through with no lag and no median.
        val smoother = OrientationSmoother()
        smoother.update(fix(yaw = 0f))
        repeat(3) { smoother.update(fix(yaw = 0f)) }
        val jumped = smoother.update(fix(yaw = 12f))
        assertEquals(12f, jumped.yawDegrees, 0.001f)
    }
}
