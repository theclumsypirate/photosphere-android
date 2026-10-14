package com.n30dyn4m1c.photosphere.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import com.n30dyn4m1c.photosphere.ui.theme.SphereActive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * The center aim point copied from the sphere capture screen: ring, dot,
 * four ticks and the dwell arc that fills as the shutter approaches.
 *
 * Same drawing, orbit inputs: the ring warms from white toward green as
 * [distanceDegrees] closes inside [thresholdDegrees], and [dwellProgress]
 * sweeps the arc from 0 to 1. [turnDegrees], when far off target, draws
 * the edge chevron pointing the way to turn, like the other camera.
 */
@Composable
fun OrbitReticleOverlay(
    distanceDegrees: Float,
    thresholdDegrees: Float,
    dwellProgress: Float,
    turnDegrees: Float?,
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

        if (turnDegrees != null && abs(turnDegrees) > thresholdDegrees) {
            val direction = Offset(if (turnDegrees >= 0f) 1f else -1f, 0f)
            drawEdgeChevron(
                direction = direction,
                color = SphereActive,
                margin = 24.dp.toPx(),
            )
        }
    }
}

/**
 * Chevron on the viewport border pointing the way to turn.
 *
 * Copied geometry from the sphere capture's edge chevron: same 14dp
 * arrowhead, same border-meeting position.
 */
private fun DrawScope.drawEdgeChevron(
    direction: Offset,
    color: Color,
    margin: Float,
) {
    val position = edgePosition(direction, margin)
    val length = 14.dp.toPx()
    val angle = Math.toDegrees(atan2(direction.y.toDouble(), direction.x.toDouble())).toFloat()

    rotate(degrees = angle, pivot = position) {
        val path = Path().apply {
            moveTo(position.x + length, position.y)
            lineTo(position.x - length * 0.55f, position.y - length * 0.8f)
            lineTo(position.x - length * 0.55f, position.y + length * 0.8f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

/** Where a ray leaving the centre along [direction] meets the inset viewport border. */
private fun DrawScope.edgePosition(direction: Offset, margin: Float): Offset {
    val halfWidth = (size.width / 2f - margin).coerceAtLeast(1f)
    val halfHeight = (size.height / 2f - margin).coerceAtLeast(1f)
    val scaleX = if (abs(direction.x) < 1e-4f) Float.MAX_VALUE else halfWidth / abs(direction.x)
    val scaleY = if (abs(direction.y) < 1e-4f) Float.MAX_VALUE else halfHeight / abs(direction.y)
    return center + direction * min(scaleX, scaleY)
}

private const val ORBIT_RETICLE_RADIUS_FRACTION = 0.16f
