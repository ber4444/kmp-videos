package com.livingpresence.inner.circle.squared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for [playbackTrackingFraction] — the logic behind the player's
 * "slider thumb tracks playback without scrubbing" behaviour (PR #14). The visual thumb
 * is a Compose concern that needs a device; here we pin down the pure fraction math that
 * drives it, including the live case a manual check can't reliably reproduce.
 */
class PlaybackTrackingFractionTest {

    @Test
    fun tracksPositionDuringPlayback() {
        // Halfway through a bounded VOD event → thumb sits at the midpoint.
        assertEquals(0.5f, playbackTrackingFraction(positionMs = 30_000L, durationMs = 60_000L, isScrubbing = false))
    }

    @Test
    fun advancesAsPlaybackProgresses() {
        // As the polled position grows against a fixed duration, the fraction grows too:
        // this is exactly the drift the fix cures (fraction used to be set once).
        val duration = 100_000L
        val early = playbackTrackingFraction(10_000L, duration, isScrubbing = false)!!
        val later = playbackTrackingFraction(80_000L, duration, isScrubbing = false)!!
        assertTrue(later > early, "thumb fraction should advance with playback ($later !> $early)")
    }

    @Test
    fun livePinsThumbToEdgeAsDurationGrows() {
        // Live nDVR: the position rides the live edge while the window keeps growing.
        // A fraction computed once would sag toward 0; recomputing keeps it pinned at 1.0.
        assertEquals(1f, playbackTrackingFraction(60_000L, 60_000L, isScrubbing = false))
        assertEquals(1f, playbackTrackingFraction(120_000L, 120_000L, isScrubbing = false))
        assertEquals(1f, playbackTrackingFraction(600_000L, 600_000L, isScrubbing = false))

        // Trailing the live edge by a fixed gap as the window grows → fraction rises
        // toward 1.0 rather than falling away from it.
        val nearEarly = playbackTrackingFraction(55_000L, 60_000L, isScrubbing = false)!!
        val nearLate = playbackTrackingFraction(595_000L, 600_000L, isScrubbing = false)!!
        assertTrue(nearLate > nearEarly, "thumb should stay near the growing live edge ($nearLate !> $nearEarly)")
    }

    @Test
    fun leavesThumbAloneWhileScrubbing() {
        // Returning null lets the caller keep the user's drag position untouched.
        assertNull(playbackTrackingFraction(30_000L, 60_000L, isScrubbing = true))
    }

    @Test
    fun leavesThumbAloneBeforeDurationKnown() {
        assertNull(playbackTrackingFraction(0L, 0L, isScrubbing = false))
        assertNull(playbackTrackingFraction(5_000L, -1L, isScrubbing = false))
    }

    @Test
    fun clampsIntoUnitRange() {
        // Position momentarily past the reported duration (or negative) can't push the
        // thumb off the track.
        assertEquals(1f, playbackTrackingFraction(70_000L, 60_000L, isScrubbing = false))
        assertEquals(0f, playbackTrackingFraction(-2_000L, 60_000L, isScrubbing = false))
    }
}
