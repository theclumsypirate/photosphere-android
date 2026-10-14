package com.n30dyn4m1c.photosphere.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import kotlin.math.min

/**
 * The center aim point copied from the sphere capture screen: ring, dot,
 * four ticks and the dwell arc that fills as the shutter approaches.
 *
 * Same drawing, orbit inputs: the ring warms from white toward green as
 * [distanceDegrees] closes inside [thresholdDegrees], and [dwellProgress]
 * sweeps the arc from 0 to 1.
 */
@Composable
fun OrbitReticleOverlay(
    distanceDegrees: Float,
    thresholdDegrees: Float,
    dwellProgress: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = min(size.width, size.height) * ORBIT_RETICLE_RADIUS_FRACTION
        val strokeWidth = 3.dp.toPx()
        val tickLength = 10.dp.toPx()
        val closeness = (1f - (distanceDegrees - thresholdDegrees) / thresholdDegrees)
            .coerceIn(0f, 1f)
        val color = lerp(Color.White.copy(alpha = 0.9f), SphereAccent, closeness)

        drawCircle(
            color = SphereAccent.copy(alpha = 0.10f + 0.18f * closeness),
            radius = radius + 8.dp.toPx(),
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
        drawCircle(color = color, radius = 2.5.dp.toPx(), center = center)

        listOf(0f, 90f, 180f, 270f).forEach { angle ->
            rotate(degrees = angle, pivot = center) {
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - radius - 2.dp.toPx()),
                    end = Offset(center.x, center.y - radius - 2.dp.toPx() - tickLength),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        if (dwellProgress > 0f) {
            drawArc(
                color = SphereAccent,
                startAngle = -90f,
                sweepAngle = 360f * dwellProgress,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round),
            )
        }
    }
}

private const val ORBIT_RETICLE_RADIUS_FRACTION = 0.16f
