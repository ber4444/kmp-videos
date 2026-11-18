package com.livingpresence.inner.circle.squared

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoZoomStateTest {
    @Test
    fun pinchKeepsThePointUnderTheFingersStationary() {
        val state = VideoZoomState()
        state.updateBounds(Size(400f, 300f), Size(400f, 300f))

        state.transform(2f, Offset(300f, 150f), Offset.Zero)

        assertEquals(2f, state.scale)
        assertEquals(Offset(-100f, 0f), state.offset)
    }

    @Test
    fun panStopsAtThePictureEdgesIncludingLetterboxing() {
        val state = VideoZoomState()
        state.updateBounds(Size(400f, 800f), Size(400f, 225f))

        state.transform(2f, Offset(200f, 400f), Offset(1000f, 1000f))

        assertEquals(Offset(200f, 0f), state.offset)
        state.transform(1f, Offset(200f, 400f), Offset(-2000f, -2000f))
        assertEquals(Offset(-200f, 0f), state.offset)
    }

    @Test
    fun zoomLimitsAndReturningToFitRestoreTheWholeFrame() {
        val state = VideoZoomState()
        state.updateBounds(Size(400f, 300f), Size(400f, 300f))

        state.transform(10f, Offset(200f, 150f), Offset(80f, 50f))
        assertEquals(4f, state.scale)

        state.transform(0.01f, Offset(300f, 200f), Offset(10f, 20f))
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun resizingTheViewportClampsAnExistingPan() {
        val state = VideoZoomState()
        state.updateBounds(Size(400f, 300f), Size(400f, 300f))
        state.transform(2f, Offset(200f, 150f), Offset(200f, 150f))

        state.updateBounds(Size(300f, 400f), Size(300f, 169f))

        assertEquals(2f, state.scale)
        assertEquals(Offset(150f, 0f), state.offset)
    }
}
