package com.livingpresence.inner.circle.squared

import com.livingpresence.mediakit.EventInfo
import com.livingpresence.mediakit.RenditionTier
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadUrlTest {
    @Test
    fun aConcertDownloadUsesItsStreamNumberAndHostInsteadOfItsFeedId() {
        val event = EventInfo(
            eventNumber = 1_000_000,
            isLive = false,
            durationMs = 1_000,
            streamUrl = "https://public.example:443/live/event15/playlist.m3u8?DVR",
        )

        assertEquals(
            "https://public.example:443/live/event15_360p/playlist.m3u8?DVR",
            event.downloadUrl(RenditionTier.P360),
        )
    }

    @Test
    fun arbitraryManifestPlaylistsAreDownloadedAsListed() {
        val event = EventInfo(
            eventNumber = 1_000_001,
            isLive = false,
            durationMs = 1_000,
            streamUrl = "https://public.example/vod/concert/playlist.m3u8?DVR",
        )

        assertEquals(event.streamUrl, event.downloadUrl(RenditionTier.P360))
    }
}

class OfflineEventsTest {
    @Test
    fun aDownloadedConcertPlaysTheRenditionItWasSavedFrom() {
        val saved = "https://public.example/live/event15_360p/playlist.m3u8?DVR"

        val events = listOf(
            EventDownloadState(1_000_000, DownloadStatus.COMPLETED, 100f, saved),
        ).toOfflineEvents()

        assertEquals(listOf(1_000_000), events.map { it.eventNumber })
        assertEquals(saved, events.single().streamUrl)
    }

    @Test
    fun aSyntheticIdWithNoSavedUrlIsLeftOutRatherThanRebuilt() {
        val events = listOf(
            EventDownloadState(1_000_000, DownloadStatus.COMPLETED, 100f),
            EventDownloadState(10_000, DownloadStatus.COMPLETED, 100f),
        ).toOfflineEvents()

        assertEquals(emptyList(), events)
    }

    @Test
    fun aNumberedEventWithNoSavedUrlIsStillRebuiltFromItsNumber() {
        val events = listOf(
            EventDownloadState(3, DownloadStatus.COMPLETED, 100f),
            EventDownloadState(4, DownloadStatus.DOWNLOADING, 50f),
        ).toOfflineEvents()

        assertEquals(listOf(3), events.map { it.eventNumber })
    }
}
