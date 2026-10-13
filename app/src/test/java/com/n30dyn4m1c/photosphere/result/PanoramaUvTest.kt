package com.n30dyn4m1c.photosphere.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE = 0.002f

/**
 * Pins the equirectangular sampling convention of the pano view against the
 * app's own world frame (X east, Y north, Z up): the sphere shown pannable is
 * the same sphere the flat frame draws, centred on north.
 */
class PanoramaUvTest {

    private fun uv(
        x: Float,
        y: Float,
        yaw: Float = 0f,
        pitch: Float = 0f,
        fov: Float = 70f,
        aspect: Float = 1f,
        spanLatRadians: Float = Math.PI.toFloat(),
    ) = equirectUvForView(x, y, yaw, pitch, fov, aspect, spanLatRadians)

    @Test
    fun `looking north shows the middle of the equirectangular frame`() {
        val (u, v) = uv(x = 0f, y = 0f)
        assertEquals(0.5f, u, TOLERANCE) // column centre: longitude 0°
        assertEquals(0.5f, v, TOLERANCE) // row centre: the horizon
    }

    @Test
    fun `turning east moves the view to the frame's right quarter`() {
        val (u, v) = uv(x = 0f, y = 0f, yaw = 90f)
        assertEquals(0.75f, u, TOLERANCE) // longitude +90° sits three quarters across
        assertEquals(0.5f, v, TOLERANCE)
    }

    @Test
    fun `turning south lands on the seam`() {
        val (u, _) = uv(x = 0f, y = 0f, yaw = 180f)
        // Longitude ±180° is the canvas edge; the GL wrap makes 1.0 == 0.0.
        assertEquals(1f, u, TOLERANCE)
    }

    @Test
    fun `looking up moves toward the top row`() {
        val (_, v) = uv(x = 0f, y = 0f, pitch = 45f)
        assertEquals(0.25f, v, TOLERANCE) // latitude +45° sits a quarter from the top
    }

    @Test
    fun `looking down moves toward the bottom row`() {
        val (_, v) = uv(x = 0f, y = 0f, pitch = -45f)
        assertEquals(0.75f, v, TOLERANCE)
    }

    @Test
    fun `the right edge of the view shows half the vertical fov to the east`() {
        val (u, v) = uv(x = 1f, y = 0f, fov = 70f)
        // x = tanHalf spans the half-fov; the ray at the right edge is 35° east.
        assertEquals(0.5f + 35f / 360f, u, TOLERANCE)
        assertEquals(0.5f, v, TOLERANCE)
    }

    @Test
    fun `the top edge of the view shows half the vertical fov above the horizon`() {
        val (u, v) = uv(x = 0f, y = 1f, fov = 70f)
        assertEquals(0.5f, u, TOLERANCE)
        assertEquals(0.5f - 35f / 180f, v, TOLERANCE)
    }

    @Test
    fun `a wide aspect stretches the horizontal reach`() {
        val wide = uv(x = 1f, y = 0f, fov = 70f, aspect = 2f)
        val square = uv(x = 1f, y = 0f, fov = 70f, aspect = 1f)
        // The same screen-space x covers more degrees when the view is wider.
        assertTrue("expected a wider reach, was ${wide.first}", wide.first > square.first)
    }

    @Test
    fun `a ring band keeps the horizon centred`() {
        // A 360°×72° band canvas is 5:1; its equator is still the middle row.
        val span = Math.toRadians(72.0).toFloat()
        val (_, v) = uv(x = 0f, y = 0f, spanLatRadians = span)
        assertEquals(0.5f, v, TOLERANCE)
    }

    @Test
    fun `a ring band pushes the poles off the texture`() {
        // Looking 45° up with only ±36° of band: v leaves [0, 1], i.e. black —
        // the view must not stretch the band over the pole.
        val span = Math.toRadians(72.0).toFloat()
        val (_, v) = uv(x = 0f, y = 0f, pitch = 45f, spanLatRadians = span)
        assertTrue("expected v outside [0, 1], was $v", v < 0f)
    }

    @Test
    fun `a full sphere keeps the old mapping`() {
        // Span π is exactly the historical behaviour: latitude +45° sits a
        // quarter from the top.
        val (_, v) = uv(x = 0f, y = 0f, pitch = 45f, spanLatRadians = Math.PI.toFloat())
        assertEquals(0.25f, v, TOLERANCE)
    }
}
