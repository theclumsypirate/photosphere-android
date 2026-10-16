package com.n30dyn4m1c.photosphere.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import com.n30dyn4m1c.photosphere.ui.theme.SphereActive
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Circular progress ring for orbit capture. Pure display, no input.
 *
 * Dots are evenly spaced starting at top, clockwise. Green filled
 * dots are captured (index below capturedCount). The activeIndex
 * dot pulses amber. Remaining dots are gray outlines.
 */
@Composable
fun OrbitRingOverlay(
    capturedCount: Int,
    totalCount: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier,
) {
    val safeTotal = totalCount.coerceAtLeast(1)
    val pulse by rememberInfiniteTransition(label = "orbitRingPulse").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbitRingAlpha",
    )
    val dotAngles = remember(safeTotal) {
        List(safeTotal) { i -> -90f + i * (360f / safeTotal) }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val ringRadius = min(size.width, size.height) * ORBIT_RING_RADIUS_FRACTION
        dotAngles.forEachIndexed { index, angleDeg ->
            val rad = Math.toRadians(angleDeg.toDouble())
            val pos = Offset(
                x = center.x + cos(rad).toFloat() * ringRadius,
                y = center.y + sin(rad).toFloat() * ringRadius,
            )
            when {
                index < capturedCount -> drawCircle(
                    color = SphereAccent,
                    radius = 5.dp.toPx(),
                    center = pos,
                )
                index == activeIndex -> {
                    drawCircle(
                        color = SphereActive.copy(alpha = 0.25f + 0.35f * pulse),
                        radius = 14.dp.toPx(),
                        center = pos,
                        style = Stroke(width = 4.dp.toPx()),
                    )
                    drawCircle(
                        color = SphereActive,
                        radius = 5.dp.toPx(),
                        center = pos,
                    )
                }
                else -> drawCircle(
                    color = Color.White.copy(alpha = 0.32f),
                    radius = 6.dp.toPx(),
                    center = pos,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

private const val ORBIT_RING_RADIUS_FRACTION = 0.30f
