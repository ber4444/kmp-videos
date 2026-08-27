package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

/** Type size and leading of one caption row; the overlay's row cap is counted in these. */
private val CaptionFontSize = 16.sp
private val CaptionLineHeight = 20.sp

/**
 * Renders the rolling in-app transcript along the bottom of the player.
 *
 * Backed by the live caption stream ([CaptionAudioRouter]); the active partial
 * (open) cue and the most recent finalized cues are shown so the user gets
 * continuous context (a single live line would lose the previous sentence). The
 * overlay is a plain no-op when captions are empty — its visibility is controlled
 * by the caller (shown only while the CC toggle is on, and on Android only while
 * the player controls are hidden, so the two never share the bottom of the frame).
 *
 * The strip is capped at [maxRows] rows so it can never grow into a wall of text
 * over the picture. The cap keeps the *newest* rows: the text is measured at its
 * full height, aligned to the bottom of a [maxRows]-tall box and clipped, so older
 * rows scroll off the top rather than the live tail being ellipsized away.
 *
 * @param captions the router's caption stream.
 * @param maxCues how many recent cues to feed the layout (the accumulator caps its history);
 *   what actually fits is decided by [maxRows].
 * @param maxRows the tallest the caption strip may get, in text rows.
 */
@Composable
internal fun CaptionOverlay(
    captions: StateFlow<List<CaptionCue>>,
    modifier: Modifier = Modifier,
    maxCues: Int = 3,
    maxRows: Int = 2,
) {
    val cues by captions.collectAsState()
    if (cues.isEmpty()) return
    val visible = cues.takeLast(maxCues)
    val joined = visible.joinToString("\n") { it.text }
    val maxTextHeight = with(LocalDensity.current) { CaptionLineHeight.toDp() } * maxRows
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = joined,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = CaptionFontSize,
                lineHeight = CaptionLineHeight,
            ),
            // heightIn caps the strip; wrapContentHeight(unbounded) still lays the text out in
            // full and pins its bottom edge, and clipToBounds cuts what rides above the cap.
            modifier = Modifier
                .heightIn(max = maxTextHeight)
                .clipToBounds()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
        )
    }
}
