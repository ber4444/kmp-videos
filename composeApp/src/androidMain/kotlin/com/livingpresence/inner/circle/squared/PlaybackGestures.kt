package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState

private const val FAST_FORWARD_SPEED = 2f

@Composable
internal fun PlaybackGestures(
    player: Player,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedState = rememberPlaybackSpeedState(player)
    val toggleControls by rememberUpdatedState(onToggleControls)
    var fastForwarding by remember(player) { mutableStateOf(false) }

    // The service can keep playing after this layer leaves composition.
    DisposableEffect(speedState) {
        onDispose { speedState.restoreOverriddenSpeed() }
    }

    Box(
        modifier = modifier
            // The inner pointer handler consumes touch. Clickable keeps the
            // same toggle available to accessibility services and keyboards.
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = "Toggle playback controls",
                onClick = onToggleControls,
            )
            .pointerInput(player, speedState) {
                detectTapGestures(
                    onTap = { toggleControls() },
                    onDoubleTap = { tap ->
                        if (tap.x < size.width / 2f) {
                            if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) player.seekBack()
                        } else if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
                            player.seekForward()
                        }
                    },
                    onLongPress = {
                        if (speedState.isEnabled && player.isPlaying && player.isCurrentMediaItemSeekable) {
                            speedState.temporarilyOverrideSpeedWith(FAST_FORWARD_SPEED)
                            fastForwarding = true
                        }
                    },
                    onPress = {
                        try {
                            tryAwaitRelease()
                        } finally {
                            if (fastForwarding) {
                                speedState.restoreOverriddenSpeed()
                                fastForwarding = false
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fastForwarding) {
            Text(
                text = "2×",
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
