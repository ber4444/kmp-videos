package com.livingpresence.inner.circle.squared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the caption strip's bottom placement.
 *
 * Drawing the overlay needs a Compose runtime, but the placement rule — captions hug the
 * bottom edge, lifted only by a control bar that shares the screen with them — is plain
 * arithmetic that lives apart from the composable, and it is the part that broke in landscape.
 */
class CaptionPlacementTest {

    @Test
    fun ridesAboveTheControlBarItSharesTheScreenWith() {
        assertEquals(140f, captionBottomInsetDp(controlsBarHeightDp = 132f))
    }

    @Test
    fun fallsBackToTheEdgeInsetBeforeTheBarIsMeasured() {
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = 0f))
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = -10f))
        assertEquals(CAPTION_EDGE_INSET_DP, captionBottomInsetDp(controlsBarHeightDp = Float.NaN))
    }

    @Test
    fun staysWithinAShortLandscapePlayer() {
        // A landscape phone player is ~360dp tall; the lift must leave the text in the
        // bottom band rather than across the middle of the picture (the old 120dp).
        val landscapeHeightDp = 360f
        assertTrue(captionBottomInsetDp(132f) < landscapeHeightDp / 2f)
        assertTrue(CAPTION_EDGE_INSET_DP < landscapeHeightDp / 8f)
    }
}
