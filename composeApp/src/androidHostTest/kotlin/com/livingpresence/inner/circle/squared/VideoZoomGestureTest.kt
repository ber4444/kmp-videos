package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoZoomGestureTest {
    @Test
    fun pinchZoomsWithoutTogglingControlsWhenFingersLiftSeparately() = runComposeUiTest {
        val zoom = VideoZoomState()
        var taps = 0
        setContent {
            Box(
                Modifier.size(300.dp)
                    .onSizeChanged { zoom.updateBounds(it.toSize(), it.toSize()) }
                    .videoZoomGestures(zoom),
            ) {
                Box(Modifier.fillMaxSize().testTag("video").clickable { taps++ })
            }
        }

        onNodeWithTag("video").performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            updatePointerTo(0, center - Offset(80f, 0f))
            updatePointerTo(1, center + Offset(80f, 0f))
            move()
            up(1)
            advanceEventTime(50)
            up(0)
        }

        runOnIdle {
            assertEquals(2f, zoom.scale, 0.001f)
            assertEquals(0, taps)
        }
    }

    @Test
    fun singleTapStillTogglesControls() = runComposeUiTest {
        val zoom = VideoZoomState()
        var taps = 0
        setContent {
            Box(Modifier.size(300.dp).videoZoomGestures(zoom)) {
                Box(Modifier.fillMaxSize().testTag("video").clickable { taps++ })
            }
        }

        onNodeWithTag("video").performTouchInput { click(center) }

        runOnIdle {
            assertEquals(1, taps)
            assertEquals(1f, zoom.scale)
        }
    }
}
