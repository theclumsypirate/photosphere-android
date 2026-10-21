package com.n30dyn4m1c.photosphere.orbit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.ui.theme.ChromeScrim
import com.n30dyn4m1c.photosphere.ui.theme.GlassContent
import com.n30dyn4m1c.photosphere.ui.theme.GlassContentDim
import com.n30dyn4m1c.photosphere.ui.theme.GlassSurface
import com.n30dyn4m1c.photosphere.ui.theme.GlassSurfaceDim
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.ui.theme.SphereActive

/**
 * The capture screen's HUD, copied for the orbit walk: scrim washes,
 * undo top-left, guidance toggle top-right, progress pill, hint card,
 * save-frames, finish and restart.
 *
 * Same visuals and motion; orbit semantics: counts are angles shot,
 * finish ends the walk early with a partial set, restart clears it.
 */
@Composable
fun OrbitHud(
    capturedCount: Int,
    totalTargets: Int,
    hint: String,
    canFinish: Boolean,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
    canUndo: Boolean,
    undoEnabled: Boolean,
    onUndo: () -> Unit,
    guidanceEnabled: Boolean,
    onToggleGuidance: () -> Unit,
    evIndex: Int,
    evRange: IntRange?,
    showExposure: Boolean,
    onEvChange: (Int) -> Unit,
    frameCount: Int,
    canSaveFrames: Boolean,
    onSaveFrames: () -> Unit,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val isLandscape = maxWidth > maxHeight

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(if (isLandscape) 120.dp else 200.dp)
                .background(Brush.verticalGradient(listOf(ChromeScrim, Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (isLandscape) {
                        Modifier.fillMaxHeight().width(120.dp)
                    } else {
                        Modifier.fillMaxWidth().height(260.dp)
                    }
                )
                .background(
                    if (isLandscape) {
                        Brush.horizontalGradient(listOf(Color.Transparent, ChromeScrim))
                    } else {
                        Brush.verticalGradient(listOf(Color.Transparent, ChromeScrim))
                    }
                ),
        )

        AnimatedVisibility(
            visible = canUndo,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 22.dp),
        ) {
            OrbitUndoButton(onUndo = onUndo, enabled = undoEnabled)
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 20.dp, top = 22.dp),
            shape = CircleShape,
            color = if (guidanceEnabled) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
            } else {
                GlassSurface
            },
            shadowElevation = 8.dp,
        ) {
            IconButton(onClick = onToggleGuidance) {
                Icon(
                    imageVector = if (guidanceEnabled) {
                        Icons.Filled.VolumeUp
                    } else {
                        Icons.Filled.VolumeOff
                    },
                    contentDescription = stringResource(
                        if (guidanceEnabled) {
                            R.string.guidance_toggle_hide
                        } else {
                            R.string.guidance_toggle_show
                        }
                    ),
                    tint = if (guidanceEnabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        GlassContent
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OrbitProgressPill(
                capturedCount = capturedCount,
                totalTargets = totalTargets,
            )
        }

        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (isLandscape) {
                        Modifier.fillMaxHeight().widthIn(max = 360.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showExposure && evRange != null) {
                OrbitExposureRow(
                    evIndex = evIndex,
                    evRange = evRange,
                    onEvChange = onEvChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OrbitHintCard(text = hint)
            AnimatedVisibility(
                visible = canSaveFrames,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TextButton(onClick = onSaveFrames) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = SphereActive,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.capture_save_frames, frameCount),
                        color = GlassContent,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = canFinish,
                enter = fadeIn() + expandVertically() + scaleIn(initialScale = 0.94f),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Button(
                    onClick = onFinish,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(
                        text = stringResource(R.string.orbit_finish),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            AnimatedVisibility(
                visible = isComplete,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TextButton(onClick = onRestart) {
                    Text(
                        text = stringResource(R.string.capture_restart),
                        color = GlassContent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Shot count pill with the animated progress bar. */
@Composable
private fun OrbitProgressPill(
    capturedCount: Int,
    totalTargets: Int,
    modifier: Modifier = Modifier,
) {
    val spoken = pluralStringResource(
        R.plurals.capture_progress_description,
        capturedCount,
        capturedCount,
        totalTargets,
    )
    val fraction by animateFloatAsState(
        targetValue = if (totalTargets > 0) capturedCount.toFloat() / totalTargets else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "orbit-progress",
    )

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        shape = PillShape,
        color = GlassSurface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "$capturedCount",
                    style = MaterialTheme.typography.displaySmall,
                    color = GlassContent,
                )
                Text(
                    text = "/ $totalTargets",
                    style = MaterialTheme.typography.titleSmall,
                    color = GlassContentDim,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.capture_progress_frames,
                        capturedCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassContentDim,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            if (totalTargets > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.8f)
                        .height(3.dp)
                        .clip(PillShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = GlassContent.copy(alpha = 0.22f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** Drop the last shot and put its angle back. */
@Composable
private fun OrbitUndoButton(
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (enabled) GlassSurface else GlassSurfaceDim,
        shadowElevation = 8.dp,
    ) {
        IconButton(onClick = onUndo, enabled = enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.capture_undo),
                tint = if (enabled) GlassContent else GlassContentDim,
            )
        }
    }
}

/** The single line of guidance in a glass card. */
@Composable
private fun OrbitHintCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = GlassSurface,
        shadowElevation = 6.dp,
    ) {
        Crossfade(
            targetState = text,
            animationSpec = tween(durationMillis = 220),
            label = "orbit-hint",
        ) { current ->
            Text(
                text = current,
                style = MaterialTheme.typography.bodyLarge,
                color = GlassContent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
            )
        }
    }
}

/** Live brightness slider, same row as capture minus the AE lock. */
@Composable
private fun OrbitExposureRow(
    evIndex: Int,
    evRange: IntRange,
    onEvChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = GlassSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.exposure_brightness),
                style = MaterialTheme.typography.labelMedium,
                color = GlassContentDim,
            )
            Slider(
                value = evIndex.toFloat(),
                onValueChange = { onEvChange(it.toInt()) },
                valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                steps = (evRange.last - evRange.first - 1).coerceAtLeast(0),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
        }
    }
}
