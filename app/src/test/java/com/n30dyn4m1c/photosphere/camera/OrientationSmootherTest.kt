package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationSmootherTest {

    private fun fix(yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f) =
        OrientationData(yawDegrees = yaw, pitchDegrees = pitch, rollDegrees = roll, timestampNanos = 1L)

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
}
