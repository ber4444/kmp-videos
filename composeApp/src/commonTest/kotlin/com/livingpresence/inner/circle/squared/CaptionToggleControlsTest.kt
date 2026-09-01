package com.livingpresence.inner.circle.squared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the caption toggle also dismisses the player controls.
 *
 * Following [PlayerControlsOverlayTest]'s convention: the click wiring itself is a
 * Compose concern needing a device, so the decision is extracted and covered here,
 * where it runs on every platform without a runtime.
 */
class CaptionToggleControlsTest {

    /**
     * Android and iOS draw the caption overlay behind
     * `captionController.enabled && !showVideoControls`, so captions are not rendered
     * at all while the control bar is up. Without this the toggle looked broken:
     * nothing appeared until the auto-hide timer fired, and nothing appeared *ever*
     * while paused, because that timer only runs during playback.
     */
    @Test
    fun turningCaptionsOnGetsTheControlsOutOfTheWay() {
        assertTrue(dismissesControlsOnToggle(captionsWereEnabled = false))
    }

    /**
     * The other direction must not, and this is the half worth pinning: the user is
     * working in the control bar when they turn captions off, and having it vanish
     * under them as a side effect of an unrelated tap reads as a broken control.
     */
    @Test
    fun turningCaptionsOffLeavesTheControlsAlone() {
        assertFalse(dismissesControlsOnToggle(captionsWereEnabled = true))
    }
}
