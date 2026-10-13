package com.n30dyn4m1c.photosphere.sensor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.util.Logging
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme
import kotlin.math.abs

/**
 * Developer screen rendering the live output of [OrientationTracker].
 *
 * Reached from the debug toggle in `MainActivity`. It exists to confirm on real
 * hardware that yaw, pitch and roll track the device, that the readings stay
 * sane when the display rotates, and that the sensor really is released when the
 * screen goes away — the sample counter freezes the moment the listener stops.
 */
@Composable
fun OrientationDebugScreen(modifier: Modifier = Modifier) {
    val tracker = rememberOrientationTracker()
    val orientation by tracker.orientation.collectAsStateWithLifecycle()

    // Proof of life independent of the numbers themselves. (StateFlow conflates
    // repeats, so this counts *changes* — with a real sensor's noise floor that
    // is effectively the event rate.)
    val sampleCount by produceState(initialValue = 0L, tracker) {
        tracker.orientation.collect { value += 1 }
    }

    LaunchedEffect(tracker.isSensorAvailable) {
        android.util.Log.i(
            Logging.TAG,
            "OrientationDebugScreen shown (sensor available: ${tracker.isSensorAvailable})",
        )
    }

    OrientationDebugContent(
        orientation = orientation,
        isSensorAvailable = tracker.isSensorAvailable,
        sampleCount = sampleCount,
        modifier = modifier,
    )
}

/** Stateless body, so the layout previews without a sensor behind it. */
@Composable
fun OrientationDebugContent(
    orientation: OrientationData,
    isSensorAvailable: Boolean,
    sampleCount: Long,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.orientation_debug_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            if (!isSensorAvailable) {
                Text(
                    text = stringResource(R.string.orientation_sensor_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            AttitudeIndicator(
                orientation = orientation,
                modifier = Modifier.size(200.dp),
            )

            AngleReadout(
                label = stringResource(R.string.orientation_yaw),
                caption = stringResource(R.string.orientation_yaw_caption),
                degrees = orientation.yawDegrees,
                range = 180f,
            )
            AngleReadout(
                label = stringResource(R.string.orientation_pitch),
                caption = stringResource(R.string.orientation_pitch_caption),
                degrees = orientation.pitchDegrees,
                range = 90f,
            )
            AngleReadout(
                label = stringResource(R.string.orientation_roll),
                caption = stringResource(R.string.orientation_roll_caption),
                degrees = orientation.rollDegrees,
                range = 180f,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusRow(
                        label = stringResource(R.string.orientation_accuracy),
                        value = orientation.accuracy.name,
                    )
                    StatusRow(
                        label = stringResource(R.string.orientation_samples),
                        value = sampleCount.toString(),
                    )
                    StatusRow(
                        label = stringResource(R.string.orientation_timestamp),
                        value = if (orientation.hasFix) {
                            stringResource(
                                R.string.orientation_timestamp_value,
                                orientation.timestampNanos / 1_000_000.0,
                            )
                        } else {
                            stringResource(R.string.orientation_waiting)
                        },
                    )
                }
            }
        }
    }
}

/** Label, big number, and a bar showing where the angle sits within its range. */
@Composable
private fun AngleReadout(
    label: String,
    caption: String,
    degrees: Float,
    range: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.primary
    val zeroColor = MaterialTheme.colorScheme.outline

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.orientation_degrees, degrees),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(12.dp),
        ) {
            val radius = size.height / 2f
            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(radius))
            // Centre tick, so "level" is readable at a glance.
            drawLine(
                color = zeroColor,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            val fraction = ((degrees / range).coerceIn(-1f, 1f) + 1f) / 2f
            drawCircle(
                color = markerColor,
                radius = radius,
                center = Offset(radius + fraction * (size.width - 2f * radius), radius),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Artificial-horizon style view of the current attitude.
 *
 * Roll turns the horizon, pitch slides it, and the tick outside the dial points
 * at the current yaw. It is a sanity check rather than an instrument: the point
 * is that panning the phone moves it the way you expect.
 */
@Composable
private fun AttitudeIndicator(
    orientation: OrientationData,
    modifier: Modifier = Modifier,
) {
    val sky = MaterialTheme.colorScheme.primaryContainer
    val ground = MaterialTheme.colorScheme.tertiaryContainer
    val horizonColor = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.primary

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val dialRadius = radius * 0.78f
            val overdraw = dialRadius * 3f

            val dial = Path().apply {
                addOval(
                    Rect(
                        center - Offset(dialRadius, dialRadius),
                        center + Offset(dialRadius, dialRadius),
                    )
                )
            }

            withTransform({
                clipPath(dial)
                // Rolling the device clockwise tips the horizon counter-clockwise.
                rotate(degrees = -orientation.rollDegrees, pivot = center)
            }) {
                // Pitch goes negative as the camera aims up, and the horizon has
                // to sink down the dial when that happens — hence the sign flip.
                val horizonY = center.y - (orientation.pitchDegrees / 90f) * dialRadius
                drawRect(
                    color = sky,
                    topLeft = Offset(center.x - overdraw, horizonY - overdraw),
                    size = Size(overdraw * 2f, overdraw),
                )
                drawRect(
                    color = ground,
                    topLeft = Offset(center.x - overdraw, horizonY),
                    size = Size(overdraw * 2f, overdraw),
                )
                drawLine(
                    color = horizonColor,
                    start = Offset(center.x - overdraw, horizonY),
                    end = Offset(center.x + overdraw, horizonY),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            drawCircle(
                color = outline,
                radius = dialRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            // Yaw ring: the tick rides around the dial with the compass bearing.
            rotate(degrees = orientation.yawDegrees, pivot = center) {
                drawLine(
                    color = marker,
                    start = Offset(center.x, center.y - radius),
                    end = Offset(center.x, center.y - dialRadius - 2.dp.toPx()),
                    strokeWidth = 4.dp.toPx(),
                )
            }
        }

        // Cardinal letter in the middle of the dial as a second read on yaw.
        Text(
            text = cardinalFor(orientation.yawDegrees),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Nearest cardinal point for a bearing in `[-180, 180)`. */
private fun cardinalFor(yawDegrees: Float): String = when {
    abs(yawDegrees) <= 45f -> "N"
    yawDegrees > 45f && yawDegrees <= 135f -> "E"
    yawDegrees < -45f && yawDegrees >= -135f -> "W"
    else -> "S"
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun OrientationDebugPreview() {
    PhotoSphereTheme {
        OrientationDebugContent(
            orientation = OrientationData(
                yawDegrees = 62.4f,
                pitchDegrees = -18.2f,
                rollDegrees = 4.7f,
                accuracy = OrientationAccuracy.High,
                timestampNanos = 12_345_678_900L,
            ),
            isSensorAvailable = true,
            sampleCount = 417,
        )
    }
}

@Preview(showBackground = true, name = "No rotation vector")
@Composable
private fun OrientationDebugUnavailablePreview() {
    PhotoSphereTheme {
        OrientationDebugContent(
            orientation = OrientationData(),
            isSensorAvailable = false,
            sampleCount = 0,
        )
    }
}
