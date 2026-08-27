package com.livingpresence.inner.circle.squared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the caption strip's bottom placement.
 *
 * Drawing the overlay needs a Compose runtime, but the placement rule — captions
 * hug the bottom edge and lift only for the control bar that is actually on screen
 * — is plain arithmetic, and it is the part that broke in landscape.
 */
class CaptionOverlayTest {

    @Test
    fun sitsAtTheBottomEdgeWhenControlsAreHidden() {
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = 132f, controlsVisible = false))
    }

    @Test
    fun ridesAboveTheControlBarWhenItIsShown() {
        assertEquals(140f, captionBottomInsetDp(controlsBarHeightDp = 132f, controlsVisible = true))
    }

    @Test
    fun fallsBackToTheEdgeInsetBeforeTheBarIsMeasured() {
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = 0f, controlsVisible = true))
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = -10f, controlsVisible = true))
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = Float.NaN, controlsVisible = true))
    }

    @Test
    fun staysWithinAShortLandscapePlayer() {
        // A landscape phone player is ~360dp tall; the lift must leave the text in the
        // bottom band rather than across the middle of the picture (the old 120dp).
        val landscapeHeightDp = 360f
        assertTrue(captionBottomInsetDp(132f, controlsVisible = true) < landscapeHeightDp / 2f)
        assertTrue(captionBottomInsetDp(132f, controlsVisible = false) < landscapeHeightDp / 8f)
    }
}
