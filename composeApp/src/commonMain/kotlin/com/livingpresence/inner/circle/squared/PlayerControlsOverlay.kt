package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
fun PlayerControlsOverlay(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: Long,
    isLive: Boolean,
    isSeekable: Boolean,
    isScrubbing: Boolean,
    sliderFraction: Float,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: (Float) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onJumpToLive: () -> Unit,
    onClose: () -> Unit,
    onThumbCenterXChanged: (Float) -> Unit = {},
    onTopBarBottomChanged: (Float) -> Unit = {},
    onBottomBarTopChanged: (Float) -> Unit = {},
    onBottomBarHeightChanged: (Float) -> Unit = {},
    topRightControls: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 10.dp.toPx() } // SliderThumbRadius

    Box(modifier = modifier) {
        // Top Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onGloballyPositioned { onTopBarBottomChanged(it.boundsInWindow().bottom) }
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text("Close", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                topRightControls()
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned {
                    onBottomBarTopChanged(it.boundsInWindow().top)
                    onBottomBarHeightChanged(it.size.height.toFloat())
                }
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (isSeekable && durationMs > 0L) {
                // Track geometry only. The thumb's x is derived from it below
                // rather than computed in here: onGloballyPositioned fires on
                // layout changes, and dragging the slider does not move or resize
                // the Slider itself — so reporting the thumb position from this
                // callback left it frozen at whatever fraction happened to be
                // current at the last layout pass, pinning the scrub preview to
                // that spot for the whole drag.
                var trackRootX by remember { mutableFloatStateOf(0f) }
                var trackWidthPx by remember { mutableFloatStateOf(0f) }

                Slider(
                    value = sliderFraction,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = { onSliderValueChangeFinished(sliderFraction) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                        trackRootX = coords.positionInRoot().x
                        trackWidthPx = coords.size.width.toFloat()
                    }
                )

                // Re-reports whenever the fraction moves, which is the whole point.
                LaunchedEffect(trackRootX, trackWidthPx, sliderFraction) {
                    if (trackWidthPx > 0f) {
                        val innerStart = trackRootX + thumbRadiusPx
                        val innerSpan = (trackWidthPx - 2 * thumbRadiusPx).coerceAtLeast(0f)
                        onThumbCenterXChanged(innerStart + sliderFraction * innerSpan)
                    }
                }
            }

            val displayedPosition = if (isScrubbing && durationMs > 0L) {
                (durationMs * sliderFraction).roundToLong()
            } else {
                positionMs
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPlaybackTime(displayedPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = when {
                        isSeekable && durationMs > 0L -> formatPlaybackTime(durationMs)
                        isLive -> "Live"
                        else -> "—"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPlayPauseToggle) {
                    Text(if (isPlaying) "Pause" else "Play", color = Color.White)
                }
                if (isLive && isSeekable && durationMs > 0L) {
                    Spacer(modifier = Modifier.width(16.dp))
                    FilledTonalButton(
                        onClick = onJumpToLive,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.22f),
                            // Without this the label keeps the theme's
                            // onSecondaryContainer, which over a translucent white
                            // pill on video reads as a disabled button.
                            contentColor = Color.White,
                        )
                    ) {
                        Text("Jump to live")
                    }
                }
            }
        }
    }
}

/**
 * The slider fraction that keeps the thumb tracking playback: [positionMs] over
 * [durationMs], clamped to `0f..1f`. Returns `null` when the thumb should be left
 * untouched — while the user is scrubbing ([isScrubbing]) or before the duration is
 * known ([durationMs] `<= 0`) — so automatic tracking never fights an in-progress drag.
 *
 * Extracted from the player's position-sync effect so the tricky live case can be
 * verified without a running player: a live DVR window's duration keeps growing
 * while the position rides the live edge, so a fraction computed once would drift
 * toward 0; recomputing here keeps the thumb pinned near the right edge.
 */
internal fun playbackTrackingFraction(positionMs: Long, durationMs: Long, isScrubbing: Boolean): Float? {
    if (isScrubbing || durationMs <= 0L) return null
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

internal fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "$hours:${pad2(minutes)}:${pad2(seconds)}"
    else "$minutes:${pad2(seconds)}"
}

private fun pad2(value: Long): String = if (value < 10L) "0$value" else value.toString()
