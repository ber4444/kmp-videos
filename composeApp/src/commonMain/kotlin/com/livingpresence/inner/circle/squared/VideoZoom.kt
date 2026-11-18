package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Screen-local zoom; only the picture is transformed, never captions or controls. */
internal class VideoZoomState {
    internal var scale: Float by mutableFloatStateOf(1f)
        private set
    internal var offset: Offset by mutableStateOf(Offset.Zero)
        private set
    internal var viewportSize: Size by mutableStateOf(Size.Zero)
        private set
    internal var contentSize: Size by mutableStateOf(Size.Zero)
        private set

    internal fun updateBounds(viewport: Size, content: Size) {
        viewportSize = viewport
        contentSize = content
        offset = clampOffset(offset, scale)
    }

    internal fun transform(zoom: Float, centroid: Offset, pan: Offset) {
        if (viewportSize.isEmpty() || !zoom.isFinite() || zoom <= 0f) return
        val newScale = (scale * zoom).coerceIn(1f, 4f)
        val relativeZoom = newScale / scale
        // Scaling around the viewport centre would pull an off-centre pinch
        // away from the fingers. Translate to keep that content point anchored.
        val focus = centroid - Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        offset = clampOffset(offset * relativeZoom + focus * (1f - relativeZoom) + pan, newScale)
        scale = newScale
    }

    private fun clampOffset(value: Offset, zoom: Float): Offset {
        if (zoom == 1f) return Offset.Zero
        val maxX = ((contentSize.width * zoom - viewportSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((contentSize.height * zoom - viewportSize.height) / 2f).coerceAtLeast(0f)
        return Offset(
            if (maxX == 0f) 0f else value.x.coerceIn(-maxX, maxX),
            if (maxY == 0f) 0f else value.y.coerceIn(-maxY, maxY),
        )
    }
}

/**
 * Observe before the child tap/hold handlers so adding a second finger cancels
 * those gestures. A single finger is left to playback controls and seeking.
 * Keep consuming until both fingers lift, so a pinch cannot finish as a tap.
 */
internal fun Modifier.videoZoomGestures(state: VideoZoomState, enabled: Boolean = true): Modifier =
    pointerInput(state, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var transforming = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    transforming = true
                    state.transform(
                        zoom = event.calculateZoom(),
                        centroid = event.calculateCentroid(useCurrent = false),
                        pan = event.calculatePan(),
                    )
                }
                if (transforming) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
