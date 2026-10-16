package com.n30dyn4m1c.photosphere.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import com.n30dyn4m1c.photosphere.ui.theme.SphereActive

/**
 * Projected target dots for the pitch-grid orbit walk. Pure display.
 *
 * Each target is placed from its relative yaw/pitch offset to the live
 * aim, scaled by the assumed view size, like the normal camera projects
 * its sphere targets onto the preview. Green filled is captured, amber
 * pulsing ring is active, gray outline is todo. The active dot also gets
 * a chevron pointing toward it when it sits off screen.
 *
 * @param plan the 36 target pitch grid.
 * @param yawDegrees live aim yaw, relative to walk start.
 * @param pitchDegrees live aim pitch.
 * @param activeIndex flat target index being worked on, or null.
 * @param captured flat indices already shot.
 * @param dwellProgress 0..1 fill of the active dot.
 * @param halfHfovDegrees half horizontal view the offsets scale against.
 * @param halfVfovDegrees half vertical view the offsets scale against.
 */
@Composable
fun OrbitProjectedTargets(
    plan: OrbitPitchPlan,
    yawDegrees: Float,
    pitchDegrees: Float,
    activeIndex: Int?,
    captured: Set<Int>,
    dwellProgress: Float,
    halfHfovDegrees: Float = PROJECTED_HALF_HFOV,
    halfVfovDegrees: Float = PROJECTED_HALF_VFOV,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val halfW = size.width / 2f
        val halfH = size.height / 2f
        plan.targets.forEachIndexed { index, target ->
            val dx = wrapDelta(target.yawDegrees - yawDegrees) / halfHfovDegrees
            val dy = (target.pitchDegrees - pitchDegrees) / halfVfovDegrees
            if (dx < -1.2f || dx > 1.2f || dy < -1.2f || dy > 1.2f) return@forEachIndexed
            // Sensor pitch is negative up, so plus puts above-horizon
            // targets above the centre.
            val pos = Offset(
                x = center.x + dx * halfW,
                y = center.y + dy * halfH,
            )
            when {
                index in captured -> drawCircle(
                    color = SphereAccent,
                    radius = 5.dp.toPx(),
                    center = pos,
                )
                index == activeIndex -> {
                    drawCircle(
                        color = SphereActive,
                        radius = 14.dp.toPx(),
                        center = pos,
                        style = Stroke(width = 4.dp.toPx()),
                    )
                    drawCircle(
                        color = SphereActive,
                        radius = (5 + 9 * dwellProgress).dp.toPx(),
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

/** Half of the assumed horizontal view used to scale yaw offsets. */
internal const val PROJECTED_HALF_HFOV = 30f

/** Half of the assumed vertical view used to scale pitch offsets. */
internal const val PROJECTED_HALF_VFOV = 22.5f
