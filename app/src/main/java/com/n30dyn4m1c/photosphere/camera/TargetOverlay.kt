package com.n30dyn4m1c.photosphere.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import com.n30dyn4m1c.photosphere.ui.theme.SphereActive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/** How long the marker takes to hand over to the next target. */
private const val FOCUS_TRANSITION_MILLIS = 350

/** Period of the "this one is live" pulse on the active marker. */
private const val PULSE_PERIOD_MILLIS = 1600

/**
 * Colours of the overlay.
 *
 * Fixed rather than themed: the overlay is drawn on top of an arbitrary camera
 * image, so it needs contrast against whatever the user happens to be pointing
 * at rather than agreement with the app's surfaces.
 */
data class TargetOverlayColors(
    val reticle: Color,
    val reticleAligned: Color,
    val active: Color,
    val completed: Color,
    val pending: Color,
    val guide: Color,
) {
    companion object {
        val Default = TargetOverlayColors(
            reticle = Color.White.copy(alpha = 0.9f),
            reticleAligned = SphereAccent,
            active = SphereActive,
            completed = SphereAccent,
            pending = Color.White.copy(alpha = 0.32f),
            guide = Color.White.copy(alpha = 0.24f),
        )
    }
}

/**
 * The alignment HUD drawn over the viewfinder.
 *
 * A fixed reticle marks the centre of the frame; the sphere's targets are
 * projected onto the preview around it. Aiming is therefore a matter of moving
 * the phone until the two coincide, with no numbers to read.
 *
 * [orientation] and [alignment] are passed as lambdas on purpose. Both change at
 * the sensor's rate — around 50 Hz — and reading them inside the draw lambda
 * confines that to the draw phase, so a moving phone never triggers
 * recomposition.
 */
@Composable
fun TargetOverlay(
    orientation: () -> OrientationData,
    alignment: () -> AlignmentState,
    plan: SphereTargetPlan?,
    activeIndex: Int,
    captured: Set<Int>,
    fieldOfView: FieldOfView,
    modifier: Modifier = Modifier,
    colors: TargetOverlayColors = TargetOverlayColors.Default,
) {
    // Runs on every hand-over, so the incoming marker grows in rather than
    // appearing somewhere new in the same frame the last one turned green.
    val focus = remember { Animatable(0f) }
    LaunchedEffect(activeIndex) {
        focus.snapTo(0f)
        focus.animateTo(1f, animationSpec = tween(FOCUS_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
    }

    val pulse = rememberInfiniteTransition(label = "target-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MILLIS, easing = LinearEasing)),
        label = "target-pulse-phase",
    )

    // A flash of white at the moment a shutter fires: the same cue a viewfinder
    // gives, and the only feedback visible while the preview stalls. Decays in
    // a fifth of a second — `isCapturing` itself stays true for the whole burst
    // (a burst of three can take a couple of seconds), and a wash that long
    // would read as a glitch and hide the aim. Observed through snapshotFlow so
    // the sensor-rate alignment state does not recompose the overlay.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { alignment().isCapturing }.collect { capturing ->
            if (capturing) {
                flash.snapTo(1f)
                flash.animateTo(0f, animationSpec = tween(durationMillis = 200))
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val currentOrientation = orientation()
        val currentAlignment = alignment()
        val focalPx = SphereProjection.focalLengthPx(size.width, size.height, fieldOfView)
        val targets = plan?.targets.orEmpty()

        drawTargets(
            targets = targets,
            activeIndex = activeIndex,
            captured = captured,
            // The camera's axes are the same for every marker, so they are built
            // once here rather than rebuilt inside each projection: a full plan
            // is a few hundred markers and this runs at display rate.
            camera = SphereProjection.cameraFrame(currentOrientation),
            focalPx = focalPx,
            colors = colors,
            focus = focus.value,
            pulse = pulse.value,
        )

        drawReticle(alignment = currentAlignment, colors = colors)

        if (currentAlignment.isCapturing && flash.value > 0f) {
            drawRect(color = Color.White.copy(alpha = 0.18f * flash.value))
        }
    }
}

/**
 * Where the user has aimed focus, and whether the lens has locked there.
 *
 * Positions are normalised to the preview: [x] runs 0..1 from the left and [y]
 * runs 0..1 from the top, which is what the camera's metering point factory
 * wants. The reticle stays visible after the lock so it is clear what the
 * session is focused on, and a second tap simply moves the lock.
 */
data class FocusReticle(
    /** Horizontal aim within the preview, 0..1 from the left. */
    val x: Float,
    /** Vertical aim within the preview, 0..1 from the top. */
    val y: Float,
    /** A focus sweep is in flight and has not converged yet. */
    val isWorking: Boolean,
    /** The lens has converged and is holding this distance. */
    val isLocked: Boolean,
)

/**
 * The tap-to-focus square drawn where the user aimed focus.
 *
 * A single rounded square with corner brackets, like a phone camera's focus
 * box: warm amber while the sweep runs (growing slightly, so the sweep reads
 * as alive), green once the lens has locked. It is drawn on top of everything
 * else so it never hides behind the target markers.
 */
@Composable
fun FocusReticleOverlay(
    focus: FocusReticle?,
    modifier: Modifier = Modifier,
) {
    if (focus == null) return

    val appear = remember { Animatable(0f) }
    LaunchedEffect(focus.x, focus.y) {
        appear.snapTo(0f)
        appear.animateTo(1f, animationSpec = tween(240, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val color = when {
            focus.isWorking -> SphereActive
            focus.isLocked -> SphereAccent
            else -> Color.White.copy(alpha = 0.9f)
        }

        val resting = 44.dp.toPx()
        val sizePx = if (focus.isWorking) {
            resting * (1f + 0.35f * (1f - appear.value))
        } else {
            resting
        }
        val half = sizePx / 2f
        val cx = focus.x * size.width
        val cy = focus.y * size.height
        val corner = 14.dp.toPx()
        val strokeWidth = 2.5.dp.toPx()

        // A soft wash over the focused region while the sweep is deciding.
        if (focus.isWorking) {
            drawRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(cx - half, cy - half),
                size = Size(sizePx, sizePx),
            )
        }

        drawFocusCorners(
            cx = cx,
            cy = cy,
            half = half,
            corner = corner,
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}

/** Draws the four corner brackets of the focus square. */
private fun DrawScope.drawFocusCorners(
    cx: Float,
    cy: Float,
    half: Float,
    corner: Float,
    color: Color,
    strokeWidth: Float,
) {
    val path = Path()
    // Top-left.
    path.moveTo(cx - half, cy - half + corner)
    path.lineTo(cx - half, cy - half)
    path.lineTo(cx - half + corner, cy - half)
    // Top-right.
    path.moveTo(cx + half - corner, cy - half)
    path.lineTo(cx + half, cy - half)
    path.lineTo(cx + half, cy - half + corner)
    // Bottom-right.
    path.moveTo(cx + half, cy + half - corner)
    path.lineTo(cx + half, cy + half)
    path.lineTo(cx + half - corner, cy + half)
    // Bottom-left.
    path.moveTo(cx - half + corner, cy + half)
    path.lineTo(cx - half, cy + half)
    path.lineTo(cx - half, cy + half - corner)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/**
 * Draws every marker: the ones already shot, the ones still to come, and the live one.
 *
 * Following the Photo Sphere / Street View convention, each marker is a circle
 * at the screen position the camera will see it — a filled one means "this
 * direction is already covered", an outlined one is "this is the next space to
 * cover", and the live target pulses so it reads as the thing to aim the
 * reticle at.
 */
private fun DrawScope.drawTargets(
    targets: List<SphereTarget>,
    activeIndex: Int,
    captured: Set<Int>,
    camera: CameraFrame,
    focalPx: Float,
    colors: TargetOverlayColors,
    focus: Float,
    pulse: Float,
) {
    if (targets.isEmpty()) return

    val margin = EDGE_MARGIN.toPx()

    targets.forEachIndexed { index, target ->
        if (index == activeIndex && index !in captured) return@forEachIndexed

        val position = camera.project(target).screenOffset(center, focalPx)
            ?: return@forEachIndexed
        if (!position.isInside(size, margin)) return@forEachIndexed

        val isShot = index in captured
        drawTargetDot(
            position = position,
            color = if (isShot) colors.completed else colors.pending,
            filled = isShot,
            radius = (if (isShot) COMPLETED_DOT_RADIUS_DP else PENDING_DOT_RADIUS_DP).toPx(),
        )
    }

    // A finished run has no live target: the last one hands over to nothing
    // and stays drawn as covered.
    if (activeIndex in captured) return
    val activeTarget = targets.getOrNull(activeIndex) ?: return
    drawActiveTarget(
        target = activeTarget,
        camera = camera,
        colors = colors,
        focalPx = focalPx,
        focus = focus,
        pulse = pulse,
    )
}

/** A single covered or pending marker: filled for done, outlined for to-go. */
private fun DrawScope.drawTargetDot(
    position: Offset,
    color: Color,
    filled: Boolean,
    radius: Float,
) {
    if (filled) {
        drawCircle(color = color, radius = radius, center = position)
    } else {
        drawCircle(
            color = color,
            radius = radius,
            center = position,
            style = Stroke(width = PENDING_DOT_STROKE_DP.toPx()),
        )
    }
}

/**
 * Draws the target the user is being sent to.
 *
 * On screen it is a pulsing ring with a solid core — the thing the reticle has
 * to cover — plus a guide line back to the reticle; off screen — behind the
 * camera, or past the edge of the preview — it collapses to a chevron on the
 * border pointing the way to turn. Without that fallback the first frame of a
 * new target would simply leave the user with an empty viewfinder and no idea
 * which way to go.
 */
private fun DrawScope.drawActiveTarget(
    target: SphereTarget,
    camera: CameraFrame,
    colors: TargetOverlayColors,
    focalPx: Float,
    focus: Float,
    pulse: Float,
) {
    val margin = EDGE_MARGIN.toPx()
    val view = camera.project(target)
    val position = view.screenOffset(center, focalPx)

    // Behind the camera there is no screen position at all: point the way.
    if (position == null || !view.isInFront) {
        drawEdgeChevron(
            direction = view.screenDirection(),
            color = colors.active.copy(alpha = 0.55f + 0.45f * focus),
            margin = margin,
        )
        return
    }

    // Near-edge hysteresis: a target sitting right on the margin would flip
    // between dot and chevron every sensor sample (±0.5° of noise moves the
    // projection by tens of px out there). Inside the margin it is a dot; just
    // outside it stays a dot clamped to the edge so it slides instead of
    // vanishing; only clearly off-screen does it become a chevron.
    val overshoot = maxOf(
        margin - position.x,
        position.x - (size.width - margin),
        margin - position.y,
        position.y - (size.height - margin),
    )
    if (overshoot > EDGE_HYSTERESIS.toPx()) {
        drawEdgeChevron(
            direction = view.screenDirection(),
            color = colors.active.copy(alpha = 0.55f + 0.45f * focus),
            margin = margin,
        )
        return
    }
    val pinned = Offset(
        position.x.coerceIn(margin, size.width - margin),
        position.y.coerceIn(margin, size.height - margin),
    )
    val strokeWidth = 2.5.dp.toPx()

    // A dashed line from the reticle to the marker: at a glance it reads as
    // "move this way", and it disappears once the two are on top of each other.
    // The cutoff tracks the reticle's size, so the line never draws inside the
    // ring while the target is already (nearly) centred.
    val separation = (pinned - center).getDistance()
    if (separation > min(size.width, size.height) * RETICLE_RADIUS_FRACTION + 24.dp.toPx()) {
        drawLine(
            color = colors.guide.copy(alpha = colors.guide.alpha * focus),
            start = center,
            end = pinned,
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 8.dp.toPx()),
            ),
        )
    }

    // A ring that breathes with the pulse, so the live target reads as alive
    // rather than as another static marker.
    val ringRadius = (ACTIVE_RING_BASE_DP + ACTIVE_RING_BREATH_DP * pulse).toPx()
    drawCircle(
        color = colors.active.copy(alpha = (1f - pulse) * 0.6f * focus),
        radius = ringRadius,
        center = pinned,
        style = Stroke(width = strokeWidth),
    )
    drawCircle(
        color = colors.active.copy(alpha = focus),
        radius = ACTIVE_CORE_RADIUS_DP.toPx(),
        center = pinned,
    )
}

/**
 * The fixed capture reticle, sized like a Photo Sphere reticle: a large ring
 * that the aim has to hold inside, with the dwell arc filling it while the aim
 * is held steady.
 *
 * The ring warms from white toward green as the target closes, which gives the
 * user a continuous read on "am I getting closer" instead of a binary that only
 * resolves at the threshold — and once it closes, holding still is what spins
 * the arc around and fires the shutter.
 */
private fun DrawScope.drawReticle(
    alignment: AlignmentState,
    colors: TargetOverlayColors,
) {
    // Sized to the viewport rather than fixed so the reticle reads as a large
    // thing to aim rather than a fine crosshair to finesse, and stays
    // proportional on any display.
    val radius = min(size.width, size.height) * RETICLE_RADIUS_FRACTION
    val strokeWidth = 3.dp.toPx()
    val tickLength = 10.dp.toPx()

    // Fully warmed at the threshold, cold from ~4x the threshold outward.
    val closeness = if (!alignment.hasDistance) {
        0f
    } else {
        (1f - (alignment.distanceDegrees - AlignmentGate.DEFAULT_THRESHOLD_DEGREES) / 6f)
            .coerceIn(0f, 1f)
    }
    val color = lerp(colors.reticle, colors.reticleAligned, closeness)

    // A soft outer halo keeps the ring readable over a bright scene and swells
    // as the aim closes, so "getting closer" is visible even before the warm-up.
    drawCircle(
        color = colors.reticleAligned.copy(alpha = 0.10f + 0.18f * closeness),
        radius = radius + 8.dp.toPx(),
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )

    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
    drawCircle(color = color, radius = 2.5.dp.toPx(), center = center)

    // Four ticks reaching outward, so the centre stays readable over busy scenes.
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

    // The dwell arc: while the aim holds inside the threshold, it fills the
    // ring's rim, and the shutter fires the moment it completes. Drawn on top
    // of the ring so the fill and the target are the same circle.
    if (alignment.dwellProgress > 0f) {
        drawArc(
            color = colors.reticleAligned,
            startAngle = -90f,
            sweepAngle = 360f * alignment.dwellProgress,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round),
        )
    }
}

/** Arrow pinned to the border, pointing the shortest way toward an off-screen target. */
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

/**
 * Pixel position of a target, or null when it is behind the camera.
 *
 * This is the pinhole divide: a direction's screen offset from the centre is its
 * sideways component over its depth, scaled by the focal length. Y is negated
 * because the camera's up is the screen's down.
 */
private fun TargetView.screenOffset(center: Offset, focalPx: Float): Offset? =
    if (!isInFront) null else Offset(center.x + x / z * focalPx, center.y - y / z * focalPx)

/**
 * Unit vector on screen pointing toward this target.
 *
 * Valid whether or not the target is in front: behind the camera the depth flips
 * the projection, but the sideways components still say which way to turn.
 */
private fun TargetView.screenDirection(): Offset {
    val raw = Offset(x, -y)
    val length = raw.getDistance()
    // Dead ahead or dead behind has no direction to speak of; pick one.
    return if (length < 1e-4f) Offset(1f, 0f) else raw / length
}

private fun Offset.isInside(size: Size, margin: Float): Boolean =
    x >= margin && x <= size.width - margin && y >= margin && y <= size.height - margin

private val EDGE_MARGIN = 28.dp

/**
 * How far outside the margin the live marker slides along the edge before it
 * collapses to a chevron. Stateless hysteresis against sensor noise: without
 * it a target sitting exactly on the margin flips dot↔chevron every sample.
 */
private val EDGE_HYSTERESIS = 48.dp

/** How far the reticle ring's radius reaches out from the centre of the preview. */
private const val RETICLE_RADIUS_FRACTION = 0.16f

/** Radius of a covered marker's filled dot. */
private val COMPLETED_DOT_RADIUS_DP = 5.dp

/** Radius of a to-go marker's outlined dot. */
private val PENDING_DOT_RADIUS_DP = 7.dp

/** Stroke of a to-go marker's outline. */
private val PENDING_DOT_STROKE_DP = 1.5.dp

/** Resting radius of the active target's pulsing ring. */
private val ACTIVE_RING_BASE_DP = 16.dp

/** How far the active target's ring breathes each pulse. */
private val ACTIVE_RING_BREATH_DP = 6.dp

/** Radius of the solid core the reticle has to cover on the active target. */
private val ACTIVE_CORE_RADIUS_DP = 5.dp

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1B, widthDp = 360, heightDp = 640)
@Composable
private fun TargetOverlaySearchingPreview() {
    val plan = SphereTargetPlan.create(startYawDegrees = 0f)
    TargetOverlay(
        orientation = { OrientationData(yawDegrees = 14f, pitchDegrees = -6f, rollDegrees = 3f) },
        alignment = { AlignmentState(distanceDegrees = 15.4f) },
        plan = plan,
        activeIndex = 3,
        captured = setOf(0, 1, 2),
        fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1B, widthDp = 360, heightDp = 640)
@Composable
private fun TargetOverlayHoldingPreview() {
    val plan = SphereTargetPlan.create(startYawDegrees = 0f)
    TargetOverlay(
        orientation = { OrientationData(yawDegrees = 89f, pitchDegrees = 0.4f) },
        alignment = { AlignmentState(distanceDegrees = 1.2f, dwellProgress = 0.6f, isAligned = true) },
        plan = plan,
        activeIndex = 3,
        captured = setOf(0, 1, 2),
        fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
    )
}
